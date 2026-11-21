package com.mcagent.client.ai;

import com.mcagent.config.BotConfig;
import com.mcagent.config.ConfigManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.util.Map;

public final class HttpUtil {
	private static HttpClient client;
	private static String lastProxyKey = "";

	private HttpUtil() {
	}

	private static HttpClient client() {
		BotConfig cfg = ConfigManager.get();
		String host = cfg.proxyHost == null ? "" : cfg.proxyHost.trim();
		String port = cfg.proxyPort == null ? "" : cfg.proxyPort.trim();
		String key = host + ":" + port;
		if (client == null || !key.equals(lastProxyKey)) {
			HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20));
			if (!host.isEmpty()) {
				try {
					b.proxy(ProxySelector.of(new InetSocketAddress(host, Integer.parseInt(port))));
				} catch (Exception ignored) {
				}
			}
			client = b.build();
			lastProxyKey = key;
		}
		return client;
	}

	public static String post(String url, String body, Map<String, String> headers) throws IOException, InterruptedException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(180))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8));
		for (Map.Entry<String, String> e : headers.entrySet()) {
			builder.header(e.getKey(), e.getValue());
		}
		HttpResponse<String> resp;
		try {
			resp = client().send(builder.build(), HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
		} catch (HttpConnectTimeoutException e) {
			String host = URI.create(url).getHost();
			throw new IOException("连接 " + host + " 超时，当前网络无法直连该地址。国内请换 DeepSeek/GLM/Kimi 等可直连接口（/mcagent 里点厂商预设），或在 config/mcagent.json 填 proxyHost/proxyPort 走代理后重进世界");
		} catch (java.net.ConnectException e) {
			String host = URI.create(url).getHost();
			throw new IOException("无法连接 " + host + "。国内请换可直连的接口，或在 config/mcagent.json 填 proxyHost/proxyPort 走代理后重进世界");
		}
		if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
			String excerpt = resp.body() == null ? "" : resp.body();
			if (excerpt.length() > 400) excerpt = excerpt.substring(0, 400);
			throw new IOException("API " + resp.statusCode() + ": " + excerpt);
		}
		return resp.body();
	}
}
