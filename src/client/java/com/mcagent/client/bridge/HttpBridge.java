package com.mcagent.client.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcagent.MCAgentMod;
import com.mcagent.client.mc.ActionExecutor;
import com.mcagent.config.BotConfig;
import com.mcagent.config.ConfigManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Local HTTP server that lets an external agent (DeepSeek Harness) drive this client.
 *
 * <p>The game can only be touched from its own thread, so every request is forwarded to
 * {@link ActionExecutor#runTool} — which itself hops onto the game thread — and the HTTP
 * worker simply waits on the returned future. Nothing here blocks the render thread.
 *
 * <p>Bound to loopback by default. All {@code /v1/*} routes require the configured token
 * (when one is set) so another local program cannot drive the client by accident.
 */
public final class HttpBridge {

	private static HttpServer server;
	private static ThreadPoolExecutor pool;

	private HttpBridge() {
	}

	public static synchronized void start() {
		BotConfig cfg = ConfigManager.get();
		if (!cfg.bridgeEnabled) {
			MCAgentMod.LOGGER.info("[mcagent] HTTP bridge disabled in config");
			return;
		}
		if (server != null) {
			return;
		}
		try {
			InetSocketAddress addr = new InetSocketAddress(cfg.bridgeHost, cfg.bridgePort);
			server = HttpServer.create(addr, 0);
			// one request at a time per connection; actions are serialized by the executor anyway
			pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(4, r -> {
				Thread t = new Thread(r, "mcagent-bridge");
				t.setDaemon(true);
				return t;
			});
			server.setExecutor(pool);

			server.createContext("/", HttpBridge::handle);
			server.start();
			MCAgentMod.LOGGER.info("[mcagent] HTTP bridge listening on http://{}:{}", cfg.bridgeHost, cfg.bridgePort);
		} catch (IOException e) {
			MCAgentMod.LOGGER.error("[mcagent] failed to start HTTP bridge on {}:{}", cfg.bridgeHost, cfg.bridgePort, e);
			server = null;
		}
	}

	public static synchronized void stop() {
		if (server != null) {
			server.stop(0);
			server = null;
		}
		if (pool != null) {
			pool.shutdownNow();
			pool = null;
		}
	}

	public static boolean isRunning() {
		return server != null;
	}

	public static int port() {
		return server == null ? -1 : server.getAddress().getPort();
	}

	// ------------------------------------------------------------------
	// routing
	// ------------------------------------------------------------------

	private static void handle(HttpExchange ex) {
		try {
			String path = ex.getRequestURI().getPath();
			String method = ex.getRequestMethod();

			if ("OPTIONS".equals(method)) {
				ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
				ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, X-MCAgent-Token");
				ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
				ex.sendResponseHeaders(204, -1);
				return;
			}

			// unauthenticated liveness probe, so DSH can discover the bridge
			if (path.equals("/v1/health") || path.equals("/health")) {
				JsonObject o = new JsonObject();
				o.addProperty("ok", true);
				o.addProperty("mod", "mcagent");
				o.addProperty("version", "1.0.0");
				o.addProperty("authRequired", !token().isEmpty());
				send(ex, 200, o);
				return;
			}

			if (!authorized(ex)) {
				JsonObject o = new JsonObject();
				o.addProperty("ok", false);
				o.addProperty("error", "unauthorized: send the configured token in the X-MCAgent-Token header");
				send(ex, 401, o);
				return;
			}

			switch (path) {
				case "/v1/status" -> send(ex, 200, status());
				case "/v1/tool" -> {
					if (!"POST".equals(method)) {
						send(ex, 405, err("use POST"));
						return;
					}
					handleTool(ex);
				}
				case "/v1/sequence" -> {
					if (!"POST".equals(method)) {
						send(ex, 405, err("use POST"));
						return;
					}
					handleSequence(ex);
				}
				case "/v1/cancel" -> {
					ActionExecutor.get().cancelAll();
					SequenceRunner.cancel();
					JsonObject o = new JsonObject();
					o.addProperty("ok", true);
					o.addProperty("message", "cancel requested");
					send(ex, 200, o);
				}
				case "/v1/chat" -> {
					if (!"POST".equals(method)) {
						send(ex, 405, err("use POST"));
						return;
					}
					JsonObject body = body(ex);
					String msg = str(body, "message", "");
					if (msg.isEmpty()) {
						send(ex, 400, err("missing 'message'"));
						return;
					}
					JsonObject args = new JsonObject();
					args.addProperty("message", msg);
					await(ex, ActionExecutor.runTool("say", args));
				}
				default -> send(ex, 404, err("unknown path: " + path));
			}
		} catch (Exception e) {
			MCAgentMod.LOGGER.warn("[mcagent] bridge error", e);
			try {
				send(ex, 500, err(String.valueOf(e)));
			} catch (Exception ignored) {
				// response already committed
			}
		} finally {
			ex.close();
		}
	}

	/** {@code POST /v1/tool} — run one tool, e.g. {"tool":"dig","args":{"x":1,"y":2,"z":3}}. */
	private static void handleTool(HttpExchange ex) throws IOException {
		JsonObject b = body(ex);
		String tool = str(b, "tool", "");
		if (tool.isEmpty()) {
			send(ex, 400, err("missing 'tool'"));
			return;
		}
		JsonObject args = b.has("args") && b.get("args").isJsonObject() ? b.getAsJsonObject("args") : new JsonObject();
		await(ex, ActionExecutor.runTool(tool, args));
	}

	/**
	 * {@code POST /v1/sequence} — run many actions back-to-back without returning to the
	 * caller between steps. This is what makes movement look continuous instead of
	 * stop-and-go: one HTTP round trip covers the whole plan.
	 */
	private static void handleSequence(HttpExchange ex) throws IOException {
		JsonObject b = body(ex);
		if (!b.has("actions") || !b.get("actions").isJsonArray()) {
			send(ex, 400, err("missing 'actions' array"));
			return;
		}
		JsonArray actions = b.getAsJsonArray("actions");
		if (actions.isEmpty()) {
			send(ex, 400, err("'actions' is empty"));
			return;
		}
		boolean stopOnError = !b.has("stopOnError") || b.get("stopOnError").getAsBoolean();
		CompletableFuture<JsonObject> fut = SequenceRunner.run(actions, stopOnError);
		try {
			JsonObject result = fut.get(10, TimeUnit.MINUTES);
			send(ex, 200, result);
		} catch (Exception e) {
			send(ex, 500, err("sequence failed: " + e));
		}
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	/** Waits for a tool future and writes its raw JSON result back. */
	private static void await(HttpExchange ex, CompletableFuture<String> fut) throws IOException {
		try {
			String r = fut.get(5, TimeUnit.MINUTES);
			ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
			byte[] out = r.getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(200, out.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(out);
			}
		} catch (java.util.concurrent.TimeoutException e) {
			send(ex, 504, err("action timed out"));
		} catch (Exception e) {
			send(ex, 500, err(String.valueOf(e)));
		}
	}

	private static JsonObject status() {
		JsonObject o = new JsonObject();
		o.addProperty("ok", true);
		o.addProperty("bridge", isRunning());
		o.addProperty("port", port());
		o.addProperty("busy", SequenceRunner.isRunning());
		BotConfig c = ConfigManager.get();
		o.addProperty("humanizeLook", c.humanizeLook);
		o.addProperty("builtinAgentEnabled", c.builtinAgentEnabled);
		return o;
	}

	private static boolean authorized(HttpExchange ex) {
		String want = token();
		if (want.isEmpty()) {
			return true;
		}
		List<String> got = ex.getRequestHeaders().get("X-MCAgent-Token");
		return got != null && got.contains(want);
	}

	private static String token() {
		String t = ConfigManager.get().bridgeToken;
		return t == null ? "" : t.trim();
	}

	private static JsonObject body(HttpExchange ex) throws IOException {
		try (InputStream in = ex.getRequestBody()) {
			String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			if (s.isBlank()) {
				return new JsonObject();
			}
			JsonElement el = JsonParser.parseString(s);
			return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
		} catch (Exception e) {
			return new JsonObject();
		}
	}

	private static void send(HttpExchange ex, int code, JsonObject o) throws IOException {
		ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
		byte[] out = o.toString().getBytes(StandardCharsets.UTF_8);
		ex.sendResponseHeaders(code, out.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(out);
		}
	}

	private static JsonObject err(String msg) {
		JsonObject o = new JsonObject();
		o.addProperty("ok", false);
		o.addProperty("error", msg);
		return o;
	}

	private static String str(JsonObject o, String key, String def) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
	}
}
