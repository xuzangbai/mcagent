package com.mcagent.client.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcagent.client.mc.ActionExecutor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs a list of actions back-to-back on the game thread.
 *
 * <p>Without this, every single step costs one HTTP round trip plus one model turn, so the
 * bot walks a few blocks, stops, thinks, and walks again. Chaining them here keeps the
 * client moving the way a person does: one decision, many continuous actions.
 *
 * <p>Each step still goes through {@link ActionExecutor#runTool}, so it inherits the same
 * reach checks, tool swapping and humanized aiming as a hand-issued action.
 */
public final class SequenceRunner {

	private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
	private static volatile boolean cancelRequested;

	private SequenceRunner() {
	}

	public static boolean isRunning() {
		return RUNNING.get();
	}

	public static void cancel() {
		cancelRequested = true;
		ActionExecutor.get().cancelAll();
	}

	public static CompletableFuture<JsonObject> run(JsonArray actions, boolean stopOnError) {
		CompletableFuture<JsonObject> result = new CompletableFuture<>();
		if (!RUNNING.compareAndSet(false, true)) {
			JsonObject o = new JsonObject();
			o.addProperty("ok", false);
			o.addProperty("error", "a sequence is already running; POST /v1/cancel first");
			result.complete(o);
			return result;
		}
		cancelRequested = false;

		Thread t = new Thread(() -> {
			JsonArray steps = new JsonArray();
			int done = 0;
			String stopReason = null;
			try {
				for (int i = 0; i < actions.size(); i++) {
					if (cancelRequested) {
						stopReason = "cancelled";
						break;
					}
					if (!actions.get(i).isJsonObject()) {
						stopReason = "action " + i + " is not an object";
						break;
					}
					JsonObject a = actions.get(i).getAsJsonObject();
					String type = a.has("type") && a.get("type").isJsonPrimitive() ? a.get("type").getAsString() : "";
					if (type.isEmpty()) {
						stopReason = "action " + i + " has no 'type'";
						break;
					}
					JsonObject args = a.has("args") && a.get("args").isJsonObject()
							? a.getAsJsonObject("args")
							: stripControlKeys(a);

					String raw;
					try {
						raw = ActionExecutor.runTool(type, args).get(5, java.util.concurrent.TimeUnit.MINUTES);
					} catch (Exception e) {
						raw = "{\"status\":\"error\",\"message\":\"" + escape(String.valueOf(e)) + "\"}";
					}

					JsonObject step = new JsonObject();
					step.addProperty("index", i);
					step.addProperty("type", type);
					step.addProperty("result", raw);
					steps.add(step);

					boolean failed = raw != null && (raw.contains("\"status\":\"error\"") || raw.contains("\"status\": \"error\""));
					if (failed) {
						// a step may opt out of aborting the whole run
						boolean soft = a.has("soft") && a.get("soft").isJsonPrimitive() && a.get("soft").getAsBoolean();
						if (stopOnError && !soft) {
							stopReason = "step " + i + " (" + type + ") failed";
							break;
						}
					} else {
						done++;
					}
				}
			} catch (Throwable e) {
				stopReason = String.valueOf(e);
			} finally {
				RUNNING.set(false);
			}

			JsonObject out = new JsonObject();
			out.addProperty("ok", stopReason == null);
			out.addProperty("completed", done);
			out.addProperty("total", actions.size());
			if (stopReason != null) {
				out.addProperty("stopped", stopReason);
			}
			out.add("steps", steps);
			result.complete(out);
		}, "mcagent-sequence");
		t.setDaemon(true);
		t.start();
		return result;
	}

	/** Copies an inline action object ({"type":"dig","x":..}) into its args object. */
	private static JsonObject stripControlKeys(JsonObject a) {
		JsonObject args = new JsonObject();
		for (String k : a.keySet()) {
			if (k.equals("type") || k.equals("soft")) {
				continue;
			}
			args.add(k, a.get(k));
		}
		return args;
	}

	private static String escape(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
	}
}
