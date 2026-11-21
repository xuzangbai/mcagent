package com.mcagent.client.config;

import com.mcagent.config.BotConfig;
import com.mcagent.config.ConfigManager;
import com.mcagent.config.Presets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

public class ConfigScreen extends Screen {
	private final Screen parent;

	private EditBox baseUrlBox;
	private EditBox apiKeyBox;
	private EditBox modelBox;
	private EditBox apiVersionBox;
	private EditBox replyBox;
	private Button presetButton;
	private Button modelButton;

	private int presetIdx;
	private int modelIdx;
	private int yBase;
	private int yKey;
	private int yModel;
	private int ySliders;
	private int yToggles;
	private int ySave;

	public ConfigScreen(Screen parent) {
		super(Component.literal("MCAgent · AI 配置"));
		this.parent = parent;
		BotConfig cfg = ConfigManager.get();
		presetIdx = 0;
		for (int i = 0; i < Presets.LIST.length; i++) {
			if (Presets.LIST[i][1].equals(cfg.provider) && Presets.LIST[i][2].equals(cfg.baseUrl)) {
				presetIdx = i;
				break;
			}
		}
		modelIdx = 0;
		String[] models = Presets.modelsOf(presetIdx);
		for (int i = 0; i < models.length; i++) {
			if (models[i].equals(cfg.model)) {
				modelIdx = i;
				break;
			}
		}
	}

	@Override
	protected void init() {
		BotConfig cfg = ConfigManager.get();
		int w = 300;
		int x = (this.width - w) / 2;
		int y = 34;
		presetButton = Button.builder(presetLabel(), b -> cyclePreset()).bounds(x, y, w, 20).build();
		addRenderableWidget(presetButton);
		y += 24;
		modelButton = Button.builder(modelLabel(), b -> cycleModel()).bounds(x, y, w, 20).build();
		addRenderableWidget(modelButton);
		y += 28;
		yBase = y;
		baseUrlBox = new EditBox(this.font, x, y, w, 18, Component.literal("Base URL"));
		baseUrlBox.setMaxLength(300);
		baseUrlBox.setValue(cfg.baseUrl == null ? "" : cfg.baseUrl);
		baseUrlBox.setHint(Component.literal(Presets.LIST[presetIdx][2]));
		addRenderableWidget(baseUrlBox);
		y += 28;
		yKey = y;
		apiKeyBox = new EditBox(this.font, x, y, w, 18, Component.literal("API Key"));
		apiKeyBox.setMaxLength(300);
		apiKeyBox.setValue(cfg.apiKey == null ? "" : cfg.apiKey);
		apiKeyBox.setHint(Component.literal("sk-... / API 密钥 (本地运行时可留空)"));
		addRenderableWidget(apiKeyBox);
		y += 28;
		yModel = y;
		modelBox = new EditBox(this.font, x, y, w / 2 - 4, 18, Component.literal("Model"));
		modelBox.setMaxLength(150);
		modelBox.setValue(cfg.model == null ? "" : cfg.model);
		modelBox.setHint(Component.literal(Presets.LIST[presetIdx].length > 3 ? Presets.LIST[presetIdx][3] : "手填模型/部署名"));
		addRenderableWidget(modelBox);
		apiVersionBox = new EditBox(this.font, x + w / 2 + 4, y, w / 2 - 4, 18, Component.literal("API Version"));
		apiVersionBox.setMaxLength(60);
		apiVersionBox.setValue(cfg.apiVersion == null ? "" : cfg.apiVersion);
		apiVersionBox.setHint(Component.literal("仅 Azure: api-version"));
		addRenderableWidget(apiVersionBox);
		y += 26;
		ySliders = y;
		int third = w / 3 - 4;
		addRenderableWidget(new TempSlider(x, y, third, 18));
		addRenderableWidget(new StepsSlider(x + third + 6, y, third, 18));
		replyBox = new EditBox(this.font, x + (third + 6) * 2, y, third, 18, Component.literal("回复间隔"));
		replyBox.setMaxLength(4);
		replyBox.setValue(String.valueOf(Math.max(1, cfg.autoReplyCooldown)));
		replyBox.setHint(Component.literal("回复间隔(秒)"));
		replyBox.setFilter(s -> s.matches("[0-9]*"));
		addRenderableWidget(replyBox);
		y += 24;
		yToggles = y;
		int tw = (w - 36) / 7;
		addRenderableWidget(toggleShort(x, y, tw, "工具", () -> ConfigManager.get().autoTool, v -> ConfigManager.get().autoTool = v));
		addRenderableWidget(toggleShort(x + (tw + 6), y, tw, "思考", () -> ConfigManager.get().showThoughts, v -> ConfigManager.get().showThoughts = v));
		addRenderableWidget(toggleShort(x + (tw + 6) * 2, y, tw, "复活", () -> ConfigManager.get().autoRespawn, v -> ConfigManager.get().autoRespawn = v));
		addRenderableWidget(toggleShort(x + (tw + 6) * 3, y, tw, "回复", () -> ConfigManager.get().autoReply, v -> ConfigManager.get().autoReply = v));
		addRenderableWidget(toggleShort(x + (tw + 6) * 4, y, tw, "记录", () -> ConfigManager.get().saveChatLog, v -> ConfigManager.get().saveChatLog = v));
		addRenderableWidget(toggleShort(x + (tw + 6) * 5, y, tw, "验证", () -> ConfigManager.get().verifyReply, v -> ConfigManager.get().verifyReply = v));
		addRenderableWidget(Button.builder(Component.literal("更多→"), b -> Minecraft.getInstance().setScreen(new AdvancedScreen(this))).bounds(x + (tw + 6) * 6, y, tw, 20).build());
		y += 24;
		ySave = y;
		addRenderableWidget(Button.builder(Component.literal("§a保存"), b -> save()).bounds(x, y, w / 2 - 4, 20).build());
		addRenderableWidget(Button.builder(Component.literal("§c取消"), b -> close()).bounds(x + w / 2 + 4, y, w / 2 - 4, 20).build());
	}

	private Component presetLabel() {
		return Component.literal("厂商预设: " + Presets.LIST[presetIdx][0] + "  [点击切换]");
	}

	private Component modelLabel() {
		String[] models = Presets.modelsOf(presetIdx);
		if (models.length == 0) {
			return Component.literal("模型: 手动填写（该厂商无需选择）");
		}
		return Component.literal("模型: " + models[modelIdx] + "  [点击切换]");
	}

	private void cyclePreset() {
		presetIdx = (presetIdx + 1) % Presets.LIST.length;
		modelIdx = 0;
		String[] p = Presets.LIST[presetIdx];
		BotConfig c = ConfigManager.get();
		c.provider = p[1];
		if (Presets.isKnownDefaultUrl(c.baseUrl)) {
			c.baseUrl = "";
			baseUrlBox.setValue(p[2]);
		}
		baseUrlBox.setHint(Component.literal(p[2]));
		String[] models = Presets.modelsOf(presetIdx);
		if (models.length > 0) {
			c.model = "";
			modelBox.setValue(models[0]);
			modelBox.setHint(Component.literal(models[0]));
		} else {
			modelBox.setValue("");
			modelBox.setHint(Component.literal("手填模型/部署名"));
		}
		presetButton.setMessage(presetLabel());
		modelButton.setMessage(modelLabel());
	}

	private void cycleModel() {
		String[] models = Presets.modelsOf(presetIdx);
		if (models.length == 0) {
			return;
		}
		modelIdx = (modelIdx + 1) % models.length;
		modelBox.setValue(models[modelIdx]);
		modelButton.setMessage(modelLabel());
	}

	public static Button toggleShort(int x, int y, int w, String label, java.util.function.BooleanSupplier getter, java.util.function.Consumer<Boolean> setter) {
		Button b = Button.builder(shortLabel(label, getter.getAsBoolean()), btn -> {
					boolean v = !getter.getAsBoolean();
					setter.accept(v);
					ConfigManager.save();
					btn.setMessage(shortLabel(label, v));
				}).bounds(x, y, w, 20).build();
		return b;
	}

	private static Component shortLabel(String label, boolean v) {
		return Component.literal(label + (v ? "§a✔" : "§7✘"));
	}

	private static Component toggleLabel(String name, boolean v) {
		return Component.literal(name + ": " + (v ? "§a开" : "§c关"));
	}

	private void save() {
		BotConfig cfg = ConfigManager.get();
		cfg.baseUrl = baseUrlBox.getValue().trim();
		cfg.apiKey = apiKeyBox.getValue().trim();
		cfg.model = modelBox.getValue().trim();
		cfg.apiVersion = apiVersionBox.getValue().trim();
		try {
			cfg.autoReplyCooldown = Mth.clamp(Integer.parseInt(replyBox.getValue().trim()), 1, 600);
		} catch (Exception e) {
			cfg.autoReplyCooldown = 15;
		}
		ConfigManager.save();
		close();
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
		int x = (this.width - 300) / 2 + 2;
		graphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);
		graphics.drawString(this.font, "Base URL（本地 Ollama/LM Studio 可免 Key）", x, yBase - 11, 0x909090);
		graphics.drawString(this.font, "API Key（仅保存在本地 config/mcagent.json）", x, yKey - 11, 0x909090);
		graphics.drawString(this.font, "模型", x, yModel - 11, 0x909090);
		graphics.drawString(this.font, "API 版本(仅Azure)", x + 152, yModel - 11, 0x909090);
		graphics.drawString(this.font, "游戏内: 按 O 打开本界面 | 聊天 @mcagent <目标> | /mcagent stop 停止", x, ySave + 24, 0x606060);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private static class TempSlider extends AbstractSliderButton {
		TempSlider(int x, int y, int w, int h) {
			super(x, y, w, h, Component.empty(), Mth.clamp(ConfigManager.get().temperature, 0, 2) / 2.0);
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal(String.format("温度: %.2f", value * 2)));
		}

		@Override
		protected void applyValue() {
			ConfigManager.get().temperature = Math.round(value * 2 * 100.0) / 100.0;
		}
	}

	private static class StepsSlider extends AbstractSliderButton {
		StepsSlider(int x, int y, int w, int h) {
			super(x, y, w, h, Component.empty(), (Mth.clamp(ConfigManager.get().maxSteps, 5, 120) - 5) / 115.0);
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal("最大步数: " + steps()));
		}

		@Override
		protected void applyValue() {
			ConfigManager.get().maxSteps = steps();
		}

		private int steps() {
			return 5 + (int) Math.round(value * 115.0);
		}
	}
}
