package com.mcagent.client.ai;

import com.mcagent.config.ConfigManager;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class ChatLog {
	private ChatLog() {
	}

	public static void append(String text) {
		if (!ConfigManager.get().saveChatLog || text == null || text.isBlank()) {
			return;
		}
		try {
			Path p = FabricLoader.getInstance().getConfigDir().resolve("mcagent_chatlog.txt");
			Files.createDirectories(p.getParent());
			String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
			Files.writeString(p, "[" + ts + "] " + text + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (Exception ignored) {
		}
	}
}
