package com.mcagent.client;

import com.mcagent.client.ai.AgentManager;
import com.mcagent.client.ai.AiClient;
import com.mcagent.client.ai.LlmProvider;
import com.mcagent.client.bridge.HttpBridge;
import com.mcagent.client.bridge.SequenceRunner;
import com.mcagent.client.config.ConfigScreen;
import com.mcagent.client.mc.ActionExecutor;
import com.mcagent.config.ConfigManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Locale;
import java.util.List;

public class MCAgentClient implements ClientModInitializer {

	private static KeyMapping openGuiKey;

	@Override
	public void onInitializeClient() {
		com.mcagent.config.Persona.ensureDefault();
		com.mcagent.client.ai.ChatMemory.load();
		com.mcagent.config.BotConfig cfg0 = ConfigManager.get();
		String selfId = Minecraft.getInstance().getUser().getName();
		if (!ActionExecutor.inBlacklist(selfId)) {
			cfg0.manualBlacklist = cfg0.manualBlacklist == null || cfg0.manualBlacklist.isBlank() ? selfId : cfg0.manualBlacklist + "," + selfId;
			ConfigManager.save();
		}
		// Local HTTP bridge: this is how DSH drives the client. Independent of the
		// built-in agent, so both can be on at once if you really want that.
		HttpBridge.start();
		// Draw the local navigator's route in the world, Baritone-style, so a planned path
		// can be inspected instead of inferred from where the bot happens to be standing.
		com.mcagent.client.pathing.PathRenderer.register(ActionExecutor.get().localNavigator());
		openGuiKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.mcagent.gui", GLFW.GLFW_KEY_O, KeyMapping.Category.MISC));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			ActionExecutor.get().tick();
			while (openGuiKey.consumeClick()) {
				mc.setScreen(new ConfigScreen(null));
			}
			tickBroadcast();
		});
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			nextBroadcastAt = 0;
			if (client != null) {
				client.execute(() -> ActionExecutor.chat(joinHint()));
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> SequenceRunner.cancel());
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, timestamp) -> onGameMessage(message.getString(), sender == null ? null : sender.name()));
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> onGameMessage(message.getString(), null));
		ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
			String m = message == null ? "" : message.replace('＠', '@').trim();
			String prefix = ConfigManager.get().chatPrefix;
			if (prefix == null || prefix.isBlank()) {
				prefix = "@mcagent";
			}
			if (!m.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
				return true;
			}
			// When DSH owns this client, @mcagent in chat must not spawn a second brain.
			if (!ConfigManager.get().builtinAgentEnabled) {
				Minecraft mc0 = Minecraft.getInstance();
				if (mc0.player != null) {
					mc0.execute(() -> ActionExecutor.chat("§e内置 AI 已关闭（由 DSH 接管）。如需启用：/mcagent 配置 → 打开「内置 AI」"));
				}
				return false;
			}
			String goal = m.substring(prefix.length()).trim();
			if (goal.length() >= 2 && goal.startsWith("\"") && goal.endsWith("\"")) {
				goal = goal.substring(1, goal.length() - 1).trim();
			}
			String g = goal;
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null && mc.level != null) {
				mc.execute(() -> {
					if (AgentManager.get().isRunning() && (g.equalsIgnoreCase("stop") || g.equals("停止") || g.equals("停下"))) {
						AgentManager.get().stop();
					} else {
						AgentManager.get().start(g);
					}
				});
			}
			return false;
		});
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
				ClientCommandManager.literal("mcagent")
						.executes(ctx -> {
							Minecraft.getInstance().setScreen(new ConfigScreen(null));
							return 1;
						})
						.then(ClientCommandManager.literal("gui").executes(ctx -> {
							Minecraft.getInstance().setScreen(new ConfigScreen(null));
							return 1;
						}))
						.then(ClientCommandManager.literal("run").then(ClientCommandManager.argument("goal", StringArgumentType.greedyString()).executes(ctx -> {
							if (!ConfigManager.get().builtinAgentEnabled) {
								ctx.getSource().sendFeedback(Component.literal("§e[MCAgent]§r 内置 AI 已关闭（由 DSH 接管）。如需启用：/mcagent 配置 → 打开「内置 AI」"));
								return 0;
							}
							AgentManager.get().start(ctx.getArgument("goal", String.class));
							return 1;
						})))
						.then(ClientCommandManager.literal("stop").executes(ctx -> {
							AgentManager.get().stop();
							SequenceRunner.cancel();
							return 1;
						}))
						.then(ClientCommandManager.literal("bridge").executes(ctx -> {
							boolean on = HttpBridge.isRunning();
							ctx.getSource().sendFeedback(Component.literal("§b[MCAgent]§r HTTP 桥接: "
									+ (on ? "§a运行中 §7http://" + ConfigManager.get().bridgeHost + ":" + HttpBridge.port()
									: "§c未启动 §7(配置里 bridgeEnabled=false 或端口被占用)")));
							return 1;
						}))
						.then(ClientCommandManager.literal("status").executes(ctx -> {
							boolean r = AgentManager.get().isRunning();
							String bridge = HttpBridge.isRunning() ? "§a开" : "§c关";
							String builtin = ConfigManager.get().builtinAgentEnabled ? "§a开" : "§c关";
							ctx.getSource().sendFeedback(Component.literal("§b[MCAgent]§r 内置AI:" + builtin
									+ " §7|§r HTTP桥接:" + bridge
									+ " §7|§r 序列:" + (SequenceRunner.isRunning() ? "§e执行中" : "空闲")
									+ " §7|§r " + (r ? "§e有任务" : "空闲")));
							return 1;
						}))
		));
		ActionExecutor.chat(joinHint());
	}

	/** Startup chat line: says which control path is actually live. */
	private static String joinHint() {
		StringBuilder sb = new StringBuilder();
		if (HttpBridge.isRunning()) {
			sb.append("§aHTTP 桥接已启动§r §7http://").append(ConfigManager.get().bridgeHost).append(':').append(HttpBridge.port()).append("§r");
		} else {
			sb.append("§cHTTP 桥接未启动§r");
		}
		sb.append(" §7|§r 内置AI: ").append(ConfigManager.get().builtinAgentEnabled ? "§a开" : "§c关(DSH接管)");
		sb.append(" §7|§r 按 §eO§r 打开配置");
		return sb.toString();
	}

	private String lastGoal = "";
	private long lastGoalMs;
	private static volatile long nextBroadcastAt;

	private static void tickBroadcast() {
		Minecraft mc = Minecraft.getInstance();
		com.mcagent.config.BotConfig cfg = ConfigManager.get();
		if (mc.player == null || mc.level == null || !cfg.autoBroadcast || cfg.broadcastDesc == null || cfg.broadcastDesc.isBlank()) {
			return;
		}
		long now = System.currentTimeMillis();
		if (nextBroadcastAt == 0) {
			nextBroadcastAt = now + randomInterval(cfg) * 1000L;
			return;
		}
		if (now < nextBroadcastAt) {
			return;
		}
		nextBroadcastAt = now + randomInterval(cfg) * 1000L;
		Thread t = new Thread(() -> {
			try {
				LlmProvider ai = AiClient.create(cfg);
				List<com.mcagent.client.ai.ChatMsg> hist = new ArrayList<>();
				hist.add(new com.mcagent.client.ai.ChatMsg("user",
						"根据以下描述，随机生成一条现在要发到服务器公屏的消息。每次生成的措辞都要不同，但意思要和描述一致，不超过30字，中文口语化，像真实玩家随口发的：\n" + cfg.broadcastDesc));
				String sys = "你是MC服务器玩家，根据要求生成要发到公屏的消息。只输出消息正文本身，不要引号、前缀或解释，不超过30字。" + com.mcagent.config.Persona.block();
				String msg = ai.chat(sys, hist);
				msg = msg.replaceAll("\\s+", " ").trim();
				if (msg.startsWith("\"")) msg = msg.substring(1);
				if (msg.endsWith("\"")) msg = msg.substring(0, msg.length() - 1);
				if (msg.length() > 60) msg = msg.substring(0, 60);
				if (msg.isEmpty()) return;
				final String out = msg;
				mc.execute(() -> {
					if (mc.player != null) {
						mc.player.connection.sendChat(out);
						if (cfg.verifyReply) {
							ActionExecutor.chat("§a定时广播: " + out);
						}
					}
				});
			} catch (Exception e) {
				ActionExecutor.chat("§c定时广播失败: " + e.getMessage());
			}
		}, "MCAgent-Broadcast");
		t.setDaemon(true);
		t.start();
	}

	private static int randomInterval(com.mcagent.config.BotConfig cfg) {
		int min = Math.max(3, cfg.broadcastMin);
		int max = Math.max(min, cfg.broadcastMax);
		return min + java.util.concurrent.ThreadLocalRandom.current().nextInt(max - min + 1);
	}

	private void onGameMessage(String text, String senderName) {
		if (text == null || text.isBlank()) {
			return;
		}
		com.mcagent.client.ai.ChatLog.append(text);
		if (text.contains("[MCAgent]")) {
			return;
		}
		text = text.replace('＠', '@');
		tryAutoReply(text, senderName);
		tryAgentGoal(text);
	}

	private void tryAgentGoal(String text) {
		// DSH owns this client by default; do not start a second agent from chat.
		if (!ConfigManager.get().builtinAgentEnabled) {
			return;
		}
		String prefix = ConfigManager.get().chatPrefix;
		if (prefix == null || prefix.isBlank()) {
			prefix = "@mcagent";
		}
		int idx = text.toLowerCase(Locale.ROOT).indexOf(prefix.toLowerCase(Locale.ROOT));
		if (idx < 0) {
			return;
		}
		String goal = text.substring(idx + prefix.length()).trim();
		if (goal.length() >= 2 && goal.startsWith("\"") && goal.endsWith("\"")) {
			goal = goal.substring(1, goal.length() - 1).trim();
		}
		if (goal.length() >= 2 && goal.startsWith("“") && goal.endsWith("”")) {
			goal = goal.substring(1, goal.length() - 1).trim();
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return;
		}
		String g = goal;
		long now = System.currentTimeMillis();
		if (g.equals(lastGoal) && now - lastGoalMs < 2000) {
			return;
		}
		lastGoal = g;
		lastGoalMs = now;
		mc.execute(() -> {
			if (AgentManager.get().isRunning() && (g.equalsIgnoreCase("stop") || g.equals("停止") || g.equals("停下"))) {
				AgentManager.get().stop();
			} else {
				AgentManager.get().start(g);
			}
		});
	}

	private static final String[][] BRACKETS = {{"<", ">"}, {"«", "»"}, {"【", "】"}, {"《", "》"}, {"＜", "＞"}};

	private void tryAutoReply(String text, String senderName) {
		boolean v = ConfigManager.get().verifyReply;
		if (v) {
			com.mcagent.client.mc.ActionExecutor.chat("§8[原始] " + text);
		}
		String name;
		String msg;
		if (senderName != null && !senderName.isBlank()) {
			name = senderName.trim();
			msg = cleanupMsg(text, name);
		} else {
			String[] pair = parseAngleHead(text);
			if (pair == null) {
				pair = parseUnknownHead(text);
			}
			if (pair == null) {
				pair = parseNameMsg(text);
			}
			if (pair == null) {
				if (v) {
					com.mcagent.client.mc.ActionExecutor.chat("§c[未匹配到玩家名格式]");
				}
				return;
			}
			name = pair[0];
			msg = pair[1];
		}
		if (name.isEmpty() || msg.isEmpty() || name.contains(" ") || name.length() > 20) {
			if (v) {
				com.mcagent.client.mc.ActionExecutor.chat("§c[解析异常] name=" + name + " msg=" + msg);
			}
			return;
		}
		if (v) {
			com.mcagent.client.mc.ActionExecutor.chat("§7识别到《" + name + "》: " + msg);
		}
		com.mcagent.client.ai.ChatReplier.onChat(name, msg);
	}

	private String[] parseAngleHead(String text) {
		java.util.regex.Matcher m = java.util.regex.Pattern
				.compile("«?\\[unknown player head\\](\\|{0,2}[A-Za-z0-9_]{2,20})»\\s*>?\\s*(.+)$")
				.matcher(text);
		if (m.matches()) {
			String msg = m.group(2).trim();
			if (!msg.isEmpty()) {
				return new String[]{m.group(1), msg};
			}
		}
		return null;
	}

	private String cleanupMsg(String text, String name) {
		String s = text.replaceFirst("^«?\\[unknown player head\\]", "");
		s = s.replaceFirst("^" + java.util.regex.Pattern.quote(name) + "»?\\??\\s*>?\\s*", "");
		return s.trim();
	}

	private String[] parseUnknownHead(String text) {
		if (!text.contains("[unknown player head]")) {
			return null;
		}
		String rest = text.substring(text.indexOf("[unknown player head]") + "[unknown player head]".length());
		java.util.regex.Matcher m = java.util.regex.Pattern
				.compile("^(\\|{0,2}[A-Za-z0-9_]{2,16})\\?\\s*>?\\s*(.+)$")
				.matcher(rest);
		if (m.matches()) {
			String msg = m.group(2).trim();
			if (!msg.isEmpty()) {
				return new String[]{m.group(1), msg};
			}
		}
		return null;
	}

	private String[] parseNameMsg(String text) {
		for (String[] b : BRACKETS) {
			int l = text.indexOf(b[0]);
			int r = text.indexOf(b[1], l + 1);
			if (l >= 0 && l <= 12 && r > l + 1) {
				String name = text.substring(l + 1, r).trim().replaceFirst("^\\[unknown player head\\]", "");
				if (!name.isEmpty() && !name.contains(" ") && name.matches("[|A-Za-z0-9_]{2,20}")) {
					String msg = text.substring(r + 1).trim();
					if (!msg.isEmpty()) {
						return new String[]{name, msg};
					}
				}
			}
		}
		return null;
	}
}
