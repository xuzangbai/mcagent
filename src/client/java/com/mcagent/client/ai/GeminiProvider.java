package com.mcagent.client.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class GeminiProvider implements LlmProvider {
	private final String baseUrl;
	private final String apiKey;
	private final String model;
	private final double temperature;

	public GeminiProvider(String baseUrl, String apiKey, String model, double temperature) {
		this.baseUrl = baseUrl.replaceAll("/+$", "");
		this.apiKey = apiKey;
		this.model = model;
		this.temperature = temperature;
	}

	@Override
	public String chat(String systemPrompt, List<ChatMsg> history) throws Exception {
		JsonObject body = new JsonObject();
		JsonObject sys = new JsonObject();
		JsonArray sysParts = new JsonArray();
		JsonObject sysText = new JsonObject();
		sysText.addProperty("text", systemPrompt);
		sysParts.add(sysText);
		sys.add("parts", sysParts);
		body.add("systemInstruction", sys);
		JsonArray contents = new JsonArray();
		for (ChatMsg m : mergeAlternating(history)) {
			JsonObject c = new JsonObject();
			c.addProperty("role", "assistant".equals(m.role()) ? "model" : "user");
			JsonArray parts = new JsonArray();
			JsonObject part = new JsonObject();
			part.addProperty("text", m.content());
			parts.add(part);
			c.add("parts", parts);
			contents.add(c);
		}
		body.add("contents", contents);
		JsonObject gen = new JsonObject();
		gen.addProperty("temperature", temperature);
		body.add("generationConfig", gen);
		String url = baseUrl + "/v1beta/models/" + model + ":generateContent";
		Map<String, String> headers = Map.of("x-goog-api-key", apiKey);
		String resp = HttpUtil.post(url, body.toString(), headers);
		JsonObject o = com.google.gson.JsonParser.parseString(resp).getAsJsonObject();
		JsonArray candidates = o.getAsJsonArray("candidates");
		StringBuilder sb = new StringBuilder();
		if (candidates != null && candidates.size() > 0) {
			JsonArray parts = candidates.get(0).getAsJsonObject().getAsJsonObject("content").getAsJsonArray("parts");
			for (int i = 0; i < parts.size(); i++) {
				JsonObject part = parts.get(i).getAsJsonObject();
				if (part.has("text")) {
					sb.append(part.get("text").getAsString());
				}
			}
		}
		if (sb.length() == 0) {
			throw new IllegalStateException("Gemini 返回为空(可能被安全策略拦截): " + o);
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
		return out;
	}
}
