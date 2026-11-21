package com.mcagent.client.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ChatMemory {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type TYPE = new TypeToken<LinkedHashMap<String, ArrayList<String>>>() {
	}.getType();
	private static Map<String, List<String>> data = new LinkedHashMap<>();

	private ChatMemory() {
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("mcagent_memory.json");
	}

	public static void load() {
		try {
			Path p = path();
			if (Files.exists(p)) {
				Map<String, List<String>> loaded = GSON.fromJson(Files.readString(p), TYPE);
				if (loaded != null) {
					data = loaded;
				}
			}
		} catch (Exception ignored) {
		}
	}

	private static void save() {
		try {
			Path p = path();
			Files.createDirectories(p.getParent());
			Files.writeString(p, GSON.toJson(data));
		} catch (Exception ignored) {
		}
	}

	public static synchronized void append(String player, String who, String text) {
		List<String> list = data.computeIfAbsent(player, k -> new ArrayList<>());
		list.add(who + ": " + text);
		while (list.size() > 20) {
			list.remove(0);
		}
		save();
	}

	public static synchronized String recent(String player, int n) {
		List<String> list = data.get(player);
		if (list == null || list.isEmpty()) {
			return "（无历史记录，初次交流）";
		}
		int from = Math.max(0, list.size() - n);
		return String.join("\n", list.subList(from, list.size()));
	}
}
