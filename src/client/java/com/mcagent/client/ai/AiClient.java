package com.mcagent.client.ai;

import com.mcagent.config.BotConfig;
import com.mcagent.config.Presets;

public final class AiClient {
	private AiClient() {
	}

	public static LlmProvider create(BotConfig cfg) {
		String provider = cfg.provider == null ? "openai" : cfg.provider;
		String base = cfg.baseUrl == null || cfg.baseUrl.isBlank() ? inferBaseUrl(cfg.model, provider) : cfg.baseUrl;
		String model = cfg.model == null || cfg.model.isBlank() ? BotConfig.defaultModel(provider) : cfg.model;
		String key = cfg.apiKey == null ? "" : cfg.apiKey.trim();
		boolean local = base.contains("localhost") || base.contains("127.0.0.1");
		boolean needKey = !"openai".equals(provider) || !local;
		if (key.isEmpty() && needKey) {
			throw new IllegalArgumentException("未配置 API Key，请按 O 打开配置界面填写（本地 Ollama/LM Studio 可留空）");
		}
		return switch (provider) {
			case "anthropic" -> new AnthropicProvider(base, key, model, cfg.temperature);
			case "gemini" -> new GeminiProvider(base, key, model, cfg.temperature);
			case "azure" -> new OpenAiCompatProvider(base, key, model, cfg.temperature, true, cfg.apiVersion == null ? "2024-10-21" : cfg.apiVersion);
			default -> new OpenAiCompatProvider(base, key, model, cfg.temperature);
		};
	}

	private static String inferBaseUrl(String model, String provider) {
		if (model != null && !model.isBlank()) {
			for (String[] p : Presets.LIST) {
				for (int i = 3; i < p.length; i++) {
					if (p[i].equalsIgnoreCase(model.trim())) {
						return p[2];
					}
				}
			}
		}
		return BotConfig.defaultBaseUrl(provider);
	}
}
