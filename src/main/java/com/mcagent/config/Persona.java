package com.mcagent.config;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class Persona {
	private static volatile String cached = "";
	private static volatile long lastLoadMs;

	private Persona() {
	}

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("mcagent_persona.txt");
	}

	public static void ensureDefault() {
		try {
			Path p = path();
			if (!Files.exists(p)) {
				Files.writeString(p, """
						# 人格设定文件 —— 写在这里的内容会注入 AI 的性格，保存后下一个任务立即生效
						# 下面是示例，可以自由修改/删除（# 开头的行会被忽略）：
						你叫小明，一个玩了十年MC的老玩家。
						性格：话痨、乐观、偶尔吐槽，干活麻利但爱吹牛。
						说话风格：口语化，偶尔用游戏梗。
						""");
			}
		} catch (Exception ignored) {
		}
	}

	public static String block() {
		long now = System.currentTimeMillis();
		if (now - lastLoadMs > 5000) {
			lastLoadMs = now;
			try {
				List<String> lines = Files.readAllLines(path());
				StringBuilder sb = new StringBuilder();
				for (String line : lines) {
					if (line.strip().startsWith("#")) {
						continue;
					}
					sb.append(line).append('\n');
				}
				cached = sb.toString().strip();
			} catch (Exception e) {
				cached = "";
			}
		}
		if (cached.isEmpty()) {
			return "";
		}
		return "\n\n## 你的人格设定（玩家自定义，优先级最高，必须始终扮演）\n" + cached;
	}
}
