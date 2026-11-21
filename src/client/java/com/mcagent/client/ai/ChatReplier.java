package com.mcagent.client.ai;

import com.google.gson.JsonObject;
import com.mcagent.config.ConfigManager;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

public final class ChatReplier {
	private static final String SYSTEM = """
			你是Minecraft多人服务器里一个真实玩家的伴生AI。规则：
			1. 如果玩家的问题需要实时/网络信息才能回答（新闻、价格、新梗的含义、攻略、赛事结果等），只输出: {"search":"搜索关键词"}
			2. 不需要搜索时，只输出: {"reply":"回复内容"}；回复不超过20个字，中文口语化，像真实玩家随口聊天，可以调侃、接梗
			3. 只输出一个JSON对象，不要任何其他文字、前缀、引号或解释
			""";

	private ChatReplier() {
	}

	public static void onChat(String playerName, String message) {
		com.mcagent.config.BotConfig cfg = ConfigManager.get();
		if (!cfg.autoReply || message.isBlank()) {
			return;
		}
		if (message.contains("@mcagent")) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		String self = mc.getUser().getName();
		if (playerName.replaceFirst("^[|｜]+", "").equalsIgnoreCase(self)) {
			return;
		}
		if (com.mcagent.client.mc.ActionExecutor.inBlacklist(playerName)) {
			return;
		}
		if (cfg.nameOnly) {
			boolean hit = false;
			for (String kw : cfg.replyKeywords.split("[,，;；|]")) {
				String k = kw.trim().toLowerCase(java.util.Locale.ROOT);
				if (!k.isEmpty() && message.toLowerCase(java.util.Locale.ROOT).contains(k)) {
					hit = true;
					break;
				}
			}
			if (!hit) {
				return;
			}
		}
		long cooldownMs = Math.max(1, cfg.autoReplyCooldown) * 1000L;
		long now = System.currentTimeMillis();
		if (now - lastReplyMs < cooldownMs) {
			if (cfg.verifyReply) {
				com.mcagent.client.mc.ActionExecutor.chat("§7冷却中，未回复《" + playerName + "》");
			}
			return;
		}
		lastReplyMs = now;
		Thread t = new Thread(() -> {
			try {
				LlmProvider ai = AiClient.create(cfg);
				String sys = system();
				Minecraft mc0 = Minecraft.getInstance();
				java.util.concurrent.CompletableFuture<String> statusFuture = new java.util.concurrent.CompletableFuture<>();
				mc0.execute(() -> statusFuture.complete(com.mcagent.client.mc.ActionExecutor.statusSnapshot()));
				String status;
				try {
					status = statusFuture.get(2, java.util.concurrent.TimeUnit.SECONDS);
				} catch (Exception e) {
					status = "{}";
				}
				List<ChatMsg> hist = new ArrayList<>();
				hist.add(new ChatMsg("user", "玩家 " + playerName + " 说: " + message
						+ "\n\n你(自己的角色)当前游戏状态: " + status
						+ "\n\n与该玩家的历史对话记录:\n" + ChatMemory.recent(playerName, 10)));
				String first = ai.chat(sys, hist);
				JsonObject action = AgentManager.parseAction(first);
				String reply;
				if (action != null && action.has("blacklist") && action.get("blacklist").isJsonObject()) {
					JsonObject bl = action.getAsJsonObject("blacklist");
					String result = com.mcagent.client.mc.ActionExecutor.blacklistOp(
							bl.has("op") ? bl.get("op").getAsString() : "list",
							bl.has("player") ? bl.get("player").getAsString() : "");
					if (cfg.verifyReply) {
						com.mcagent.client.mc.ActionExecutor.chat("§7黑名单操作: " + result.replaceAll("\\{\"status\":\"[a-z]+\",|\"\\}", ""));
					}
					hist.add(new ChatMsg("assistant", first));
					hist.add(new ChatMsg("user", "黑名单操作结果: " + result + "\n\n现在回复该玩家，只输出 {\"reply\":\"...\"}，不超过20字"));
					String second = ai.chat(sys, hist);
					JsonObject a2 = AgentManager.parseAction(second);
					reply = a2 != null && a2.has("reply") && a2.get("reply").isJsonPrimitive() ? a2.get("reply").getAsString() : second;
				} else if (action != null && action.has("search") && action.get("search").isJsonPrimitive()) {
					String query = action.get("search").getAsString();
					if (cfg.verifyReply) {
						com.mcagent.client.mc.ActionExecutor.chat("§7《" + playerName + "》的问题需要查资料，搜索: " + query);
					}
					String result = com.mcagent.client.mc.ActionExecutor.webSearch(query);
					hist.add(new ChatMsg("assistant", first));
					hist.add(new ChatMsg("user", "搜索结果:\n" + result + "\n\n现在根据搜索结果回复该玩家，只输出 {\"reply\":\"...\"}，不超过20字"));
					String second = ai.chat(sys, hist);
					JsonObject a2 = AgentManager.parseAction(second);
					reply = a2 != null && a2.has("reply") && a2.get("reply").isJsonPrimitive() ? a2.get("reply").getAsString() : second;
				} else if (action != null && action.has("reply") && action.get("reply").isJsonPrimitive()) {
					reply = action.get("reply").getAsString();
				} else {
					reply = first;
				}
				reply = reply.replaceAll("\\s+", " ").trim();
				if (reply.startsWith("\"")) {
					reply = reply.substring(1);
				}
				if (reply.endsWith("\"")) {
					reply = reply.substring(0, reply.length() - 1);
				}
				if (reply.length() > 20) {
					reply = reply.substring(0, 20);
				}
				if (reply.isEmpty()) {
					lastReplyMs = 0;
					return;
				}
				ChatMemory.append(playerName, playerName, message);
				ChatMemory.append(playerName, "我", reply);
				final String out = reply;
				Minecraft mc2 = Minecraft.getInstance();
				mc2.execute(() -> {
					if (mc2.player != null) {
						mc2.player.connection.sendChat(out);
						if (cfg.verifyReply) {
							com.mcagent.client.mc.ActionExecutor.chat("§a已回复《" + playerName + "》: " + out);
						}
					}
				});
			} catch (Exception e) {
				lastReplyMs = 0;
				com.mcagent.client.mc.ActionExecutor.chat("§c自动回复《" + playerName + "》失败: " + e.getMessage());
			}
		}, "MCAgent-Reply");
		t.setDaemon(true);
		t.start();
	}

	private static String system() {
		return """
				你是Minecraft多人服务器里一个真实玩家的伴生AI。你在游戏里有一个自己的角色（游戏ID就是你的名字），会有你的实时状态（坐标/生命/手持等）。
				规则：
				1. 如果玩家的问题需要实时/网络信息才能回答（新闻、价格、新梗的含义、攻略、赛事结果等），只输出: {"search":"搜索关键词"}
				2. 玩家要求"别回我/拉黑我/拉黑某人"时，输出: {"blacklist":{"op":"add","player":"玩家ID"}}；要求解除拉黑/恢复回复时输出 {"blacklist":{"op":"remove","player":"玩家ID"}}；想知道黑名单情况输出 {"blacklist":{"op":"list"}}（注意op=remove只能移除AI自己加入的，玩家手动加的移除不了）
				3. 不需要搜索或黑名单操作时，只输出: {"reply":"回复内容"}；回复不超过20个字，中文口语化，像真实玩家随口聊天，可以调侃、接梗
				4. 玩家问"你在哪/在干嘛/你多少血"等问题时，直接根据状态信息回答
				5. 只输出一个JSON对象，不要任何其他文字、前缀、引号或解释
				""" + com.mcagent.config.Persona.block();
	}

	private static volatile long lastReplyMs;
}
