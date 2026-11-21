package com.mcagent.config;

public class Presets {
	public static final String[][] LIST = {
			{"DeepSeek V4", "openai", "https://api.deepseek.com/v1", "deepseek-v4-pro", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp"},
			{"智谱 GLM", "openai", "https://open.bigmodel.cn/api/paas/v4", "glm-5.3", "glm-5.2", "glm-5.3-flash", "glm-5.1"},
			{"Kimi 月之暗面", "openai", "https://api.moonshot.cn/v1", "kimi-k3", "kimi-k2.7-code", "kimi-k2.6", "kimi-k2.5"},
			{"通义千问 Qwen", "openai", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3.8-max", "qwen3.7-plus", "qwen3.8-flash"},
			{"OpenAI", "openai", "https://api.openai.com/v1", "gpt-5.6-sol", "gpt-5.6-luna", "gpt-5.6-terra", "gpt-5.5", "gpt-5.4-mini", "gpt-5.4-nano"},
			{"Anthropic Claude", "anthropic", "https://api.anthropic.com", "claude-opus-5", "claude-sonnet-5", "claude-fable-5", "claude-opus-4.8"},
			{"Google Gemini", "gemini", "https://generativelanguage.googleapis.com", "gemini-3.7-flash", "gemini-3.6-flash", "gemini-3.5-flash"},
			{"xAI Grok", "openai", "https://api.x.ai/v1", "grok-4.6", "grok-4.5", "grok-4.3"},
			{"豆包 · 火山方舟", "openai", "https://ark.cn-beijing.volces.com/api/v3", "doubao-1-6-uni-250828", "doubao-1-5-pro-32k", "doubao-1-5-lite-32k"},
			{"MiniMax", "openai", "https://api.minimax.chat/v1", "MiniMax-M3", "MiniMax-M2.7", "MiniMax-M2.5"},
			{"硅基流动 SiliconFlow", "openai", "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V4-Flash", "Qwen/Qwen3.8-27B", "zai-org/GLM-5.3"},
			{"百度千帆", "openai", "https://qianfan.baidubce.com/v2", "ernie-5.0", "ernie-4.5-turbo-128k", "ernie-speed-pro-128k"},
			{"腾讯混元", "openai", "https://api.hunyuan.cloud.tencent.com/v1", "hunyuan-turbos-latest", "hunyuan-t1-latest"},
			{"Mistral", "openai", "https://api.mistral.ai/v1", "mistral-medium-3-5", "mistral-large-2512", "mistral-small-2603"},
			{"Groq(开源模型加速)", "openai", "https://api.groq.com/openai/v1", "meta-llama/llama-4-maverick-17b-128e-instruct", "openai/gpt-oss-120b", "meta-llama/llama-4-scout-17b-16e-instruct"},
			{"OpenRouter(聚合400+)", "openai", "https://openrouter.ai/api/v1", "openrouter/auto", "openai/gpt-5.6-sol", "anthropic/claude-opus-5", "google/gemini-3.7-flash", "z-ai/glm-5.3"},
			{"Ollama 本地(免Key)", "openai", "http://localhost:11434/v1", "qwen3.8:latest", "glm5.3:latest", "deepseek-v4:latest"},
			{"LM Studio 本地(免Key)", "openai", "http://localhost:1234/v1", "local-model"},
			{"Azure OpenAI", "azure", "https://你的资源名.openai.azure.com/openai/deployments/你的部署名", ""},
	};

	public static String[] modelsOf(int idx) {
		if (idx < 0 || idx >= LIST.length || LIST[idx].length <= 3) {
			return new String[0];
		}
		String[] out = new String[LIST[idx].length - 3];
		System.arraycopy(LIST[idx], 3, out, 0, out.length);
		return out;
	}

	public static boolean isKnownDefaultUrl(String url) {
		if (url == null || url.isBlank()) {
			return true;
		}
		for (String[] p : LIST) {
			if (p[2].equals(url) || BotConfig.defaultBaseUrl(p[1]).equals(url)) {
				return true;
			}
		}
		return false;
	}

	public static boolean isKnownDefaultModel(String model) {
		if (model == null || model.isBlank()) {
			return true;
		}
		for (String[] p : LIST) {
			for (int i = 3; i < p.length; i++) {
				if (p[i].equals(model)) {
					return true;
				}
			}
			if (BotConfig.defaultModel(p[1]).equals(model)) {
				return true;
			}
		}
		return false;
	}
}
