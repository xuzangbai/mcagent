package com.mcagent.client.mc;

import java.lang.reflect.Method;
import java.util.Locale;

public final class BaritoneBridge {

	private BaritoneBridge() {
	}

	public static boolean available() {
		try {
			Class.forName("baritone.api.BaritoneAPI");
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	/** Sends Baritone to a position, rounding to the block grid. */
	public static String gotoPos(double x, double y, double z) {
		return execute("goto " + (int) Math.floor(x) + " " + (int) Math.floor(y) + " " + (int) Math.floor(z));
	}

	/**
	 * True while Baritone is still working on a route.
	 *
	 * <p>Used to tell "still walking" apart from "gave up": Baritone stops on its own when it
	 * cannot reach the goal, and without this the caller would wait out its whole timeout on
	 * a route that ended minutes ago.
	 */
	public static boolean isPathing() {
		try {
			Class<?> api = Class.forName("baritone.api.BaritoneAPI");
			Object provider = api.getMethod("getProvider").invoke(null);
			Object baritone = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
			Object behavior = primary(baritone, "getPathingBehavior");
			Object r = behavior.getClass().getMethod("isPathing").invoke(behavior);
			return r instanceof Boolean b && b;
		} catch (Throwable t) {
			return false;
		}
	}

	/** True while a Baritone process (mine, follow, goal) is running. */
	public static boolean isActive() {
		if (isPathing()) {
			return true;
		}
		try {
			Class<?> api = Class.forName("baritone.api.BaritoneAPI");
			Object provider = api.getMethod("getProvider").invoke(null);
			Object baritone = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
			Object behavior = primary(baritone, "getPathingBehavior");
			Object r = behavior.getClass().getMethod("isSafeToCancel").invoke(behavior);
			// safe to cancel means nothing is running
			return r instanceof Boolean b && !b;
		} catch (Throwable t) {
			return false;
		}
	}

	public static String execute(String command) {
		try {
			Class<?> api = Class.forName("baritone.api.BaritoneAPI");
			Object provider = api.getMethod("getProvider").invoke(null);
			Object baritone = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
			String[] parts = command.trim().split("\\s+");
			String head = parts[0].toLowerCase(Locale.ROOT);
			return switch (head) {
				case "goto" -> doGoto(baritone, parts);
				case "mine" -> doMine(baritone, parts);
				case "follow" -> doFollow(baritone, parts);
				case "stop" -> doStop(baritone);
				default -> doRaw(baritone, command.trim());
			};
		} catch (Throwable t) {
			return "Baritone执行失败: " + t;
		}
	}

	private static Object primary(Object baritone, String getter) throws Exception {
		return baritone.getClass().getMethod(getter).invoke(baritone);
	}

	private static String doGoto(Object baritone, String[] parts) throws Exception {
		if (parts.length >= 3) {
			try {
				int x = Integer.parseInt(parts[parts.length - 3]);
				int y = Integer.parseInt(parts[parts.length - 2]);
				int z = Integer.parseInt(parts[parts.length - 1]);
				Object goal = Class.forName("baritone.api.pathing.goals.GoalBlock")
						.getConstructor(int.class, int.class, int.class)
						.newInstance(x, y, z);
				Object proc = primary(baritone, "getCustomGoalProcess");
				proc.getClass().getMethod("setGoal", Class.forName("baritone.api.pathing.goals.Goal")).invoke(proc, goal);
				for (Method m : proc.getClass().getMethods()) {
					if (m.getName().equals("path") && m.getParameterCount() == 0) {
						m.invoke(proc);
						break;
					}
				}
				return "Baritone开始寻路到 " + x + " " + y + " " + z;
			} catch (NumberFormatException ignored) {
			}
		}
		return "参数格式错误，应为 goto x y z";
	}

	private static String doMine(Object baritone, String[] parts) throws Exception {
		if (parts.length < 2) {
			return "参数格式错误，应为 mine 方块名 [数量]";
		}
		String block = parts[1];
		int count = 0;
		if (parts.length >= 3) {
			try {
				count = Integer.parseInt(parts[2]);
			} catch (NumberFormatException ignored) {
			}
		}
		Object proc = primary(baritone, "getMineProcess");
		proc.getClass().getMethod("mineByName", int.class, String[].class).invoke(proc, count, new String[]{block});
		return "Baritone开始挖掘 " + block + (count > 0 ? "，目标数量 " + count : "（直到手动停止）");
	}

	private static String doFollow(Object baritone, String[] parts) throws Exception {
		if (parts.length < 2) {
			return "参数格式错误，应为 follow 玩家名";
		}
		String name = parts[1];
		Object proc = primary(baritone, "getFollowProcess");
		for (Method m : proc.getClass().getMethods()) {
			if (m.getName().equals("follow") && m.getParameterCount() == 1
					&& m.getParameterTypes()[0] == java.util.function.Predicate.class) {
				m.invoke(proc, (java.util.function.Predicate<Object>) entity -> matchesName(entity, name));
				return "Baritone开始跟随 " + name;
			}
		}
		return "Baritone的follow接口不可用";
	}

	private static boolean matchesName(Object entity, String name) {
		try {
			if (entity instanceof net.minecraft.world.entity.player.Player pl) {
				if (pl.getScoreboardName().equalsIgnoreCase(name)) {
					return true;
				}
			}
			if (entity instanceof net.minecraft.world.entity.Entity e) {
				String path = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
				return path.equalsIgnoreCase(name) || path.contains(name.toLowerCase(Locale.ROOT)) && name.length() >= 4;
			}
		} catch (Throwable ignored) {
		}
		return false;
	}

	private static String doStop(Object baritone) {
		StringBuilder sb = new StringBuilder();
		for (String getter : new String[]{"getMineProcess", "getFollowProcess", "getCustomGoalProcess", "getGetToBlockProcess"}) {
			try {
				Object proc = primary(baritone, getter);
				for (Method m : proc.getClass().getMethods()) {
					if (m.getName().equals("cancel") && m.getParameterCount() == 0) {
						m.invoke(proc);
						break;
					}
				}
			} catch (Throwable ignored) {
			}
		}
		try {
			Object behavior = primary(baritone, "getPathingBehavior");
			behavior.getClass().getMethod("cancelEverything").invoke(behavior);
			sb.append("Baritone已全部停止");
		} catch (Throwable t) {
			sb.append("Baritone停止指令已发送");
		}
		return sb.toString();
	}

	private static String doRaw(Object baritone, String command) {
		try {
			Object cm = primary(baritone, "getCommandManager");
			for (Method m : cm.getClass().getMethods()) {
				if (m.getName().equals("execute") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class) {
					Object r = m.invoke(cm, command);
					return "已执行Baritone命令: " + command + (r == null ? "" : " -> " + r);
				}
			}
		} catch (Throwable ignored) {
		}
		return "不支持的Baritone命令: " + command + "，可用: goto x y z / mine 方块 [数量] / follow 玩家名 / stop";
	}
}
