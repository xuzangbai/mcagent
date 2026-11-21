package com.mcagent.client.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcagent.client.mc.ActionExecutor;
import com.mcagent.config.ConfigManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class AgentManager {
	private static final AgentManager INSTANCE = new AgentManager();

	private volatile boolean running;
	private volatile boolean stopFlag;
	private Thread worker;

	private AgentManager() {
	}

	public static AgentManager get() {
		return INSTANCE;
	}

	public boolean isRunning() {
		return running;
	}

	public void stop() {
		if (running) {
			stopFlag = true;
			ActionExecutor.get().cancelAll();
			chat("已请求停止，等待当前步骤结束...");
		}
	}

	public synchronized void start(String goal) {
		if (goal == null || goal.isBlank()) {
			chat("目标为空，用法: @mcagent <目标> 或 /mcagent run <目标>");
			return;
		}
		if (running) {
			chat("当前已有任务在执行，先发送 /mcagent stop");
			return;
		}
		stopFlag = false;
		running = true;
		String g = goal.trim();
		chat("开始任务: " + g);
		worker = new Thread(() -> run(g), "MCAgent-Agent");
		worker.setDaemon(true);
		worker.start();
	}

	private void run(String goal) {
		try {
			com.mcagent.config.BotConfig cfg = ConfigManager.get();
			LlmProvider ai = AiClient.create(cfg);
			List<ChatMsg> history = new ArrayList<>();
			String status;
			try {
				status = ActionExecutor.runTool("get_status", new JsonObject()).get(15, TimeUnit.SECONDS);
			} catch (Exception e) {
				status = "{}";
			}
			history.add(new ChatMsg("user", "玩家目标: " + goal + "\n\n当前状态(get_status): " + status));
			int maxSteps = Math.max(5, cfg.maxSteps);
			String failTool = null;
			int failCount = 0;
			for (int step = 1; step <= maxSteps; step++) {
				if (stopFlag) {
					chat("任务已停止");
					break;
				}
				String reply;
				try {
					reply = ai.chat(Prompts.SYSTEM + com.mcagent.config.Persona.block(), history);
				} catch (Exception e) {
					chat("§cAI 请求失败: " + e.getMessage());
					break;
				}
				history.add(new ChatMsg("assistant", reply));
				JsonObject action = parseAction(reply);
				if (action == null) {
					history.add(new ChatMsg("user", "错误: 你的回复不是合法JSON。请只输出一个形如 {\"thought\":\"...\",\"tool\":\"...\",\"args\":{...}} 的JSON对象。"));
					continue;
				}
				String tool = action.has("tool") && action.get("tool").isJsonPrimitive() ? action.get("tool").getAsString() : "";
				String thought = action.has("thought") && action.get("thought").isJsonPrimitive() ? action.get("thought").getAsString() : "";
				JsonObject args = action.has("args") && action.get("args").isJsonObject() ? action.getAsJsonObject("args") : new JsonObject();
				if (cfg.showThoughts && !thought.isEmpty()) {
					chat("§7思考: " + thought);
				}
				if ("done".equalsIgnoreCase(tool)) {
					chat("§a任务完成: " + strOr(args, "message", goal));
					break;
				}
				if ("abort".equalsIgnoreCase(tool)) {
					chat("§e任务放弃: " + strOr(args, "message", "AI 未说明原因"));
					break;
				}
				String result;
				try {
					result = ActionExecutor.runTool(tool, args).get(150, TimeUnit.SECONDS);
				} catch (Exception e) {
					result = "{\"status\":\"error\",\"message\":\"工具执行异常: " + e.getMessage() + "\"}";
				}
				if (result.startsWith("{\"status\":\"error\"") || result.startsWith("{\"status\":\"partial\"")) {
					if (tool.equals(failTool)) {
						failCount++;
					} else {
						failTool = tool;
						failCount = 1;
					}
				} else {
					failTool = null;
					failCount = 0;
				}
				String extra = "";
				if (failCount >= 2) {
					extra = "\n注意: 工具<" + tool + ">已连续失败" + failCount + "次，不要重复同样的尝试。必须更换方案: 换地点 / 换工具等级 / 换获取途径(挖矿→找箱子→交易) / 换任务拆解顺序 / 用其他工具组合，或 abort 说明卡点。";
				}
				history.add(new ChatMsg("user", "第" + step + "步 工具<" + tool + "> 结果: " + result + extra));
				if (stopFlag) {
					chat("任务已停止");
					break;
				}
				if (step == maxSteps) {
					chat("§e已达最大步数 " + maxSteps + "，任务中止。可在配置中调大 maxSteps");
				}
			}
		} catch (IllegalArgumentException e) {
			chat("§c" + e.getMessage());
		} catch (Exception e) {
			chat("§c任务异常: " + e);
		} finally {
			running = false;
			ActionExecutor.get().cancelAll();
		}
	}

	public static JsonObject parseAction(String reply) {
		if (reply == null) {
			return null;
		}
		String s = reply.trim();
		s = s.replace("```json", "").replace("```JSON", "").replace("```", "");
		int start = s.indexOf('{');
		if (start < 0) {
			return null;
		}
		int depth = 0;
		boolean inStr = false;
		char prev = 0;
		for (int i = start; i < s.length(); i++) {
			char c = s.charAt(i);
			if (inStr) {
				if (c == '"' && prev != '\\') {
					inStr = false;
				}
			} else if (c == '"') {
				inStr = true;
			} else if (c == '{') {
				depth++;
			} else if (c == '}') {
				depth--;
				if (depth == 0) {
					try {
						return JsonParser.parseString(s.substring(start, i + 1)).getAsJsonObject();
					} catch (Exception e) {
						return null;
					}
				}
			}
			prev = c;
		}
		return null;
	}

	private static String strOr(JsonObject o, String key, String def) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
	}

	public static void chat(String msg) {
		ActionExecutor.chat(msg);
	}
}
