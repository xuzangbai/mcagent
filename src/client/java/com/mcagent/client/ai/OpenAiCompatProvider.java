package com.mcagent.client.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OpenAiCompatProvider implements LlmProvider {
	private final String baseUrl;
	private final String apiKey;
	private final String model;
	private final double temperature;
	private final boolean azure;
	private final String apiVersion;

	public OpenAiCompatProvider(String baseUrl, String apiKey, String model, double temperature) {
		this(baseUrl, apiKey, model, temperature, false, "");
	}

	public OpenAiCompatProvider(String baseUrl, String apiKey, String model, double temperature, boolean azure, String apiVersion) {
		this.baseUrl = baseUrl.replaceAll("/+$", "");
		this.apiKey = apiKey;
		this.model = model;
		this.temperature = temperature;
		this.azure = azure;
		this.apiVersion = apiVersion;
	}

	@Override
	public String chat(String systemPrompt, List<ChatMsg> history) throws Exception {
		JsonObject body = new JsonObject();
		if (!azure) {
			body.addProperty("model", model);
		}
		body.addProperty("temperature", temperature);
		body.addProperty("stream", false);
		JsonArray msgs = new JsonArray();
		JsonObject sys = new JsonObject();
		sys.addProperty("role", "system");
		sys.addProperty("content", systemPrompt);
		msgs.add(sys);
		for (ChatMsg m : history) {
			JsonObject o = new JsonObject();
			o.addProperty("role", m.role());
			o.addProperty("content", m.content());
			msgs.add(o);
		}
		body.add("messages", msgs);
		String url;
		Map<String, String> headers = new HashMap<>();
		if (azure) {
			url = baseUrl + "/chat/completions?api-version=" + apiVersion;
			headers.put("api-key", apiKey);
		} else {
			url = baseUrl + "/chat/completions";
			if (!apiKey.isEmpty()) {
				headers.put("Authorization", "Bearer " + apiKey);
			}
		}
		String resp = HttpUtil.post(url, body.toString(), headers);
		JsonObject o = com.google.gson.JsonParser.parseString(resp).getAsJsonObject();
		return o.getAsJsonArray("choices").get(0).getAsJsonObject()
				.getAsJsonObject("message").get("content").getAsString();
	}
}
