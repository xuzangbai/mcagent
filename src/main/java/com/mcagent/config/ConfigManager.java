package com.mcagent.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ConfigManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static BotConfig config = new BotConfig();

	public static BotConfig get() {
		return config;
	}

	public static Path configPath() {
		return FabricLoader.getInstance().getConfigDir().resolve("mcagent.json");
	}

	public static void load() {
		Path path = configPath();
		if (Files.exists(path)) {
			try {
				BotConfig loaded = GSON.fromJson(Files.readString(path), BotConfig.class);
				if (loaded != null) {
					config = loaded;
				}
			} catch (Exception e) {
				com.mcagent.MCAgentMod.LOGGER.warn("Failed to load MCAgent config, using defaults", e);
			}
		}
		save();
	}

	public static void save() {
		try {
			Path path = configPath();
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(config));
		} catch (IOException e) {
			com.mcagent.MCAgentMod.LOGGER.warn("Failed to save MCAgent config", e);
		}
	}
}
