package com.mcagent.client.config;

import com.mcagent.config.BotConfig;
import com.mcagent.config.ConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class AdvancedScreen extends Screen {
	private final Screen parent;

	private EditBox keywordsBox;
	private EditBox descBox;
	private EditBox minBox;
	private EditBox maxBox;
	private EditBox blacklistBox;
	private Button nameOnlyBtn;
	private Button broadcastBtn;

	public AdvancedScreen(Screen parent) {
		super(Component.literal("MCAgent · 高级设置"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		BotConfig cfg = ConfigManager.get();
		int w = 300;
		int x = (this.width - w) / 2;
		int y = 34;
		nameOnlyBtn = ConfigScreen.toggleShort(x, y, w, "仅名字模式（只回复含关键词的消息）", () -> ConfigManager.get().nameOnly, v -> ConfigManager.get().nameOnly = v);
		addRenderableWidget(nameOnlyBtn);
		y += 28;
		keywordsBox = new EditBox(this.font, x, y, w, 18, Component.literal("Keywords"));
		keywordsBox.setMaxLength(200);
		keywordsBox.setValue(cfg.replyKeywords == null ? "" : cfg.replyKeywords);
		keywordsBox.setHint(Component.literal("回复关键词，逗号/分号分隔: 葡萄,ai,机器人"));
		addRenderableWidget(keywordsBox);
		y += 28;
		broadcastBtn = ConfigScreen.toggleShort(x, y, w, "定时发消息", () -> ConfigManager.get().autoBroadcast, v -> ConfigManager.get().autoBroadcast = v);
		addRenderableWidget(broadcastBtn);
		y += 28;
		descBox = new EditBox(this.font, x, y, w, 18, Component.literal("Broadcast"));
		descBox.setMaxLength(150);
		descBox.setValue(cfg.broadcastDesc == null ? "" : cfg.broadcastDesc);
		descBox.setHint(Component.literal("消息类型描述，例: 定时发一条消息（加葡萄可以让ai回复你哦）"));
		addRenderableWidget(descBox);
		y += 28;
		minBox = new EditBox(this.font, x, y, w / 2 - 4, 18, Component.literal("Min"));
		minBox.setMaxLength(4);
		minBox.setValue(String.valueOf(Math.max(3, cfg.broadcastMin)));
		minBox.setHint(Component.literal("最小间隔(秒)"));
		minBox.setFilter(s -> s.matches("[0-9]*"));
		addRenderableWidget(minBox);
		maxBox = new EditBox(this.font, x + w / 2 + 4, y, w / 2 - 4, 18, Component.literal("Max"));
		maxBox.setMaxLength(4);
		maxBox.setValue(String.valueOf(Math.max(3, cfg.broadcastMax)));
		maxBox.setHint(Component.literal("最大间隔(秒)"));
		maxBox.setFilter(s -> s.matches("[0-9]*"));
		addRenderableWidget(maxBox);
		y += 28;
		blacklistBox = new EditBox(this.font, x, y, w, 18, Component.literal("Blacklist"));
		blacklistBox.setMaxLength(300);
		blacklistBox.setValue(cfg.manualBlacklist == null ? "" : cfg.manualBlacklist);
		blacklistBox.setHint(Component.literal("手动黑名单（逗号分隔，AI无法移除；AI自己加的另存）"));
		addRenderableWidget(blacklistBox);
		y += 30;
		addRenderableWidget(Button.builder(Component.literal("§a保存"), b -> save()).bounds(x, y, w / 2 - 4, 20).build());
		addRenderableWidget(Button.builder(Component.literal("§c取消"), b -> close()).bounds(x + w / 2 + 4, y, w / 2 - 4, 20).build());
	}

	private void save() {
		BotConfig cfg = ConfigManager.get();
		cfg.replyKeywords = keywordsBox.getValue().trim();
		cfg.broadcastDesc = descBox.getValue().trim();
		cfg.manualBlacklist = blacklistBox.getValue().trim();
		try {
			cfg.broadcastMin = MthClamp(Integer.parseInt(minBox.getValue().trim()), 3, 3600);
		} catch (Exception e) {
			cfg.broadcastMin = 10;
		}
		try {
			cfg.broadcastMax = MthClamp(Integer.parseInt(maxBox.getValue().trim()), 3, 3600);
		} catch (Exception e) {
			cfg.broadcastMax = 15;
		}
		if (cfg.broadcastMax < cfg.broadcastMin) {
			cfg.broadcastMax = cfg.broadcastMin;
		}
		ConfigManager.save();
		close();
	}

	private static int MthClamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	private void close() {
		Minecraft.getInstance().setScreen(parent);
	}

	@Override
	public void onClose() {
		close();
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
		int x = (this.width - 300) / 2 + 2;
		graphics.drawString(this.font, "回复关键词（仅名字模式用，逗号/分号分隔）", x, 62, 0x909090);
		graphics.drawString(this.font, "消息类型描述（每次AI随机生成一条意思相近的）", x, 118, 0x909090);
		graphics.drawString(this.font, "发送间隔随机区间（秒）", x, 174, 0x909090);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
