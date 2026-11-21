package com.mcagent.client.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AnthropicProvider implements LlmProvider {
	private final String baseUrl;
	private final String apiKey;
	private final String model;
	private final double temperature;

	public AnthropicProvider(String baseUrl, String apiKey, String model, double temperature) {
		this.baseUrl = baseUrl.replaceAll("/+$", "");
		this.apiKey = apiKey;
		this.model = model;
		this.temperature = temperature;
	}

	@Override
	public String chat(String systemPrompt, List<ChatMsg> history) throws Exception {
		JsonObject body = new JsonObject();
		body.addProperty("model", model);
		body.addProperty("max_tokens", 2048);
		body.addProperty("temperature", temperature);
		body.addProperty("system", systemPrompt);
		List<ChatMsg> merged = mergeAlternating(history);
		JsonArray msgs = new JsonArray();
		for (ChatMsg m : merged) {
			JsonObject o = new JsonObject();
			o.addProperty("role", m.role());
			o.addProperty("content", m.content());
			msgs.add(o);
		}
		body.add("messages", msgs);
		Map<String, String> headers = Map.of(
				"x-api-key", apiKey,
				"anthropic-version", "2023-06-01"
		);
		String resp = HttpUtil.post(baseUrl + "/v1/messages", body.toString(), headers);
		JsonObject o = com.google.gson.JsonParser.parseString(resp).getAsJsonObject();
		JsonArray content = o.getAsJsonArray("content");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < content.size(); i++) {
			JsonObject part = content.get(i).getAsJsonObject();
			if (part.has("text")) {
				sb.append(part.get("text").getAsString());
			}
		}
		return sb.toString();
	}

	private static List<ChatMsg> mergeAlternating(List<ChatMsg> history) {
		List<ChatMsg> out = new ArrayList<>();
		for (ChatMsg m : history) {
			String role = "assistant".equals(m.role()) ? "assistant" : "user";
			if (!out.isEmpty() && out.get(out.size() - 1).role().equals(role)) {
				ChatMsg last = out.remove(out.size() - 1);
				out.add(new ChatMsg(role, last.content() + "\n\n" + m.content()));
			} else {
				out.add(new ChatMsg(role, m.content()));
			}
		}
		if (!out.isEmpty() && out.get(0).role().equals("assistant")) {
			out.add(0, new ChatMsg("user", "开始。"));
		}
		return out;
	}
}
