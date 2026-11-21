package com.mcagent.config;

public class BotConfig {
	public String provider = "openai";
	public String baseUrl = "";
	public String apiKey = "";
	public String model = "";
	public String apiVersion = "2024-10-21";
	public double temperature = 0.4;
	public int maxSteps = 40;
	public boolean autoTool = true;
	public boolean showThoughts = true;
	public String chatPrefix = "@mcagent";
	public boolean autoEat = true;
	public boolean autoRespawn = true;
	public boolean autoReply = false;
	public int autoReplyCooldown = 15;
	public boolean saveChatLog = false;
	public boolean verifyReply = false;
	public boolean nameOnly = false;
	public String replyKeywords = "葡萄,ai,机器人";
	public boolean autoBroadcast = false;
	public String broadcastDesc = "定时发一条消息（消息里面添加葡萄可以让ai回复你哦）";
	public int broadcastMin = 10;
	public int broadcastMax = 15;
	public String manualBlacklist = "";
	public String aiBlacklist = "";
	public String proxyHost = "";
	public String proxyPort = "";

	// ---------------------------------------------------------------------
	// MCAgent additions: remote control by DSH (DeepSeek Harness)
	// ---------------------------------------------------------------------

	/** Master switch for the built-in LLM agent (@mcagent chat / /mcagent run). Off by default: DSH drives instead. */
	public boolean builtinAgentEnabled = false;

	/** Master switch for the local HTTP bridge that lets DSH control this client. */
	public boolean bridgeEnabled = true;

	/** Bridge bind address. Keep 127.0.0.1 unless you really know why you need otherwise. */
	public String bridgeHost = "127.0.0.1";

	/** Bridge TCP port. */
	public int bridgePort = 25566;

	/** Shared secret. DSH must send it as the X-MCAgent-Token header. Empty = no auth (loopback only). */
	public String bridgeToken = "";

	// ---- human-like (anti-cheat friendly) motion ----
	/** Turn the head gradually instead of snapping, so aim looks like real mouse movement. */
	public boolean humanizeLook = true;

	/** Print one line per second describing the local navigator's search/walk state. */
	public boolean pathDebug = true;
	public double humanizeMaxYawPerTick = 12.0;
	/** Max degrees of pitch per tick when humanizing. */
	public double humanizeMaxPitchPerTick = 8.0;
	/** Random aiming error in blocks added to look targets (real players never aim perfectly). */
	public double humanizeAimJitter = 0.04;
	/** Random extra delay in ms before starting an action. */
	public int humanizeActionDelayMin = 60;
	public int humanizeActionDelayMax = 220;
	/** Random extra ticks added between repeated block interactions. */
	public int humanizeInteractJitterTicks = 2;

	public static String defaultBaseUrl(String provider) {
		return switch (provider) {
			case "anthropic" -> "https://api.anthropic.com";
			case "gemini" -> "https://generativelanguage.googleapis.com";
			case "azure" -> "https://你的资源名.openai.azure.com/openai/deployments/你的部署名";
			default -> "https://api.openai.com/v1";
		};
	}

	public static String defaultModel(String provider) {
		return switch (provider) {
			case "anthropic" -> "claude-sonnet-5";
			case "gemini" -> "gemini-3.7-flash";
			case "azure" -> "";
			default -> "gpt-5.6-sol";
		};
	}
}
