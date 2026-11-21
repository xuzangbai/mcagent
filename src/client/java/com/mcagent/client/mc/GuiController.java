package com.mcagent.client.mc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Drives the client's own screens — main menu, world select, settings, chat — from outside.
 *
 * <p>{@link ActionExecutor} only knows how to act inside a loaded world: every one of its
 * tools bails out with "未进入世界" at the title screen. That leaves the whole GUI
 * unreachable, so an agent could not even open a singleplayer world without a human
 * clicking. This class closes that gap: it walks the current screen's widget list and
 * activates widgets exactly the way a mouse click does, so anything reachable by clicking
 * is reachable by the agent — join a world, open settings, toggle options, type in fields.
 *
 * <p>Every method must be called on the game thread (ActionExecutor routes them there).
 */
public final class GuiController {

	private GuiController() {
	}

	/** Every clickable widget on the current screen, with a stable index. */
	public static JsonObject listWidgets() {
		Minecraft mc = Minecraft.getInstance();
		Screen screen = mc.screen;
		JsonObject out = new JsonObject();
		if (screen == null) {
			// no screen open: the player is in the world with the HUD up
			out.addProperty("status", "success");
			out.addProperty("screen", "(none — in world / HUD)");
			out.addProperty("in_world", mc.player != null && mc.level != null);
			out.add("widgets", new JsonArray());
			return out;
		}
		out.addProperty("status", "success");
		out.addProperty("screen", screen.getClass().getSimpleName());
		out.addProperty("title", screen.getTitle() == null ? "" : screen.getTitle().getString());
		out.addProperty("in_world", mc.player != null && mc.level != null);

		JsonArray arr = new JsonArray();
		List<AbstractWidget> ws = widgetsOf(screen);
		for (int i = 0; i < ws.size(); i++) {
			AbstractWidget w = ws.get(i);
			JsonObject o = new JsonObject();
			o.addProperty("index", i);
			o.addProperty("class", w.getClass().getSimpleName());
			o.addProperty("label", labelOf(w));
			o.addProperty("x", w.getX());
			o.addProperty("y", w.getY());
			o.addProperty("w", w.getWidth());
			o.addProperty("h", w.getHeight());
			o.addProperty("active", w.isActive());
			o.addProperty("visible", w.visible);
			if (w instanceof EditBox eb) {
				o.addProperty("value", eb.getValue());
				o.addProperty("type", "textfield");
			} else if (w instanceof Button) {
				o.addProperty("type", "button");
			} else {
				o.addProperty("type", "widget");
			}
			arr.add(o);
		}
		out.add("widgets", arr);
		return out;
	}

	/**
	 * Clicks a widget, by index (from {@code gui_list}) or by a case-insensitive substring
	 * of its label — so the agent can say "click 单人游戏" without tracking indices.
	 */
	/**
	 * Clicks raw GUI coordinates.
	 *
	 * <p>An escape hatch for interface elements that are not widgets: tab bars, custom
	 * sliders, map widgets, anything a screen paints itself. {@link #click} can only reach
	 * what is registered as a child, and some screens — the world-creation tabs among them —
	 * draw controls that never appear there.
	 */
	public static JsonObject clickAt(JsonObject args) {
		Minecraft mc = Minecraft.getInstance();
		Screen screen = mc.screen;
		if (screen == null) {
			return err("没有打开的界面");
		}
		if (!args.has("x") || !args.has("y")) {
			return err("需要 x 和 y（GUI 缩放后的坐标）");
		}
		double gx = args.get("x").getAsDouble();
		double gy = args.get("y").getAsDouble();
		int w = mc.getWindow().getGuiScaledWidth();
		int h = mc.getWindow().getGuiScaledHeight();
		if (gx < 0 || gy < 0 || gx > w || gy > h) {
			return err("坐标 (" + gx + "," + gy + ") 超出界面范围 " + w + "x" + h);
		}
		// Dispatch a single press/release pair, the same way the widget path does, so a
		// non-widget control sees exactly one click.
		try {
			net.minecraft.client.gui.screens.Screen sc = screen;
			net.minecraft.client.input.MouseButtonEvent press = new net.minecraft.client.input.MouseButtonEvent(
					gx, gy, new net.minecraft.client.input.MouseButtonInfo(0, 0));
			sc.mouseClicked(press, false);
			sc.mouseReleased(press);
		} catch (Throwable t) {
			return err("坐标点击失败: " + t);
		}
		JsonObject ok = new JsonObject();
		ok.addProperty("status", "success");
		ok.addProperty("clicked_at", gx + "," + gy);
		ok.addProperty("screen", screen.getClass().getSimpleName());
		ok.addProperty("gui_size", w + "x" + h);
		return ok;
	}

	public static JsonObject click(JsonObject args) {
		Minecraft mc = Minecraft.getInstance();
		Screen screen = mc.screen;
		if (screen == null) {
			return err("没有打开的界面（玩家在世界中，HUD 不响应点击）");
		}
		List<AbstractWidget> ws = widgetsOf(screen);

		AbstractWidget target = null;
		if (args.has("index") && args.get("index").isJsonPrimitive()) {
			int i = args.get("index").getAsInt();
			if (i < 0 || i >= ws.size()) {
				return err("index " + i + " 越界，当前界面有 " + ws.size() + " 个控件");
			}
			target = ws.get(i);
		} else if (args.has("label") && args.get("label").isJsonPrimitive()) {
			String want = args.get("label").getAsString().toLowerCase(Locale.ROOT).trim();
			for (AbstractWidget w : ws) {
				if (labelOf(w).toLowerCase(Locale.ROOT).contains(want)) {
					target = w;
					break;
				}
			}
			if (target == null) {
				StringBuilder sb = new StringBuilder();
				for (AbstractWidget w : ws) {
					if (sb.length() > 0) {
						sb.append(", ");
					}
					sb.append(labelOf(w));
				}
				return err("找不到标签包含 \"" + args.get("label").getAsString() + "\" 的控件。可选: " + sb);
			}
		} else {
			return err("需要 index 或 label 参数");
		}

		if (!target.isActive() || !target.visible) {
			return err("控件不可用: " + labelOf(target));
		}

		String label = labelOf(target);
		double cx = target.getX() + target.getWidth() / 2.0;
		double cy = target.getY() + target.getHeight() / 2.0;
		boolean clicked = false;
		try {
			// Press a button exactly once.
			//
			// Button.mouseClicked already invokes onPress, so also calling onPress on the
			// fallback path fires the action twice. On a CycleButton (game mode, difficulty,
			// allow-commands) that silently skips a value — observed as "allow commands"
			// flipping straight back off — and on a normal button it would run the action
			// twice. So: dispatch by type, one call, no retry on a successful press.
			MouseButtonEvent ev = new MouseButtonEvent(cx, cy, new MouseButtonInfo(0, 0));
			if (target instanceof Button b) {
				b.onPress(noModifiers());
				clicked = true;
			} else {
				clicked = target.mouseClicked(ev, false);
			}
		} catch (Throwable t) {
			try {
				if (target instanceof Button b) {
					b.onPress(noModifiers());
					clicked = true;
				}
			} catch (Throwable t2) {
				return err("点击失败: " + t2);
			}
		}

		JsonObject o = new JsonObject();
		o.addProperty("status", "success");
		o.addProperty("clicked", label);
		o.addProperty("class", target.getClass().getSimpleName());
		o.addProperty("handled", clicked);
		Screen after = mc.screen;
		o.addProperty("screen_after", after == null ? "(none)" : after.getClass().getSimpleName());
		return o;
	}

	/** Types text into a text field, optionally submitting with Enter. */
	public static JsonObject type(JsonObject args) {
		Minecraft mc = Minecraft.getInstance();
		Screen screen = mc.screen;
		if (screen == null) {
			return err("没有打开的界面");
		}
		String text = args.has("text") ? args.get("text").getAsString() : "";
		List<AbstractWidget> ws = widgetsOf(screen);

		EditBox box = null;
		if (args.has("index") && args.get("index").isJsonPrimitive()) {
			int i = args.get("index").getAsInt();
			if (i >= 0 && i < ws.size() && ws.get(i) instanceof EditBox eb) {
				box = eb;
			}
		} else {
			for (AbstractWidget w : ws) {
				if (w instanceof EditBox eb && eb.isActive() && eb.visible) {
					box = eb;
					break;
				}
			}
		}
		if (box == null) {
			return err("当前界面没有可输入的文本框（screen=" + screen.getClass().getSimpleName() + "）");
		}
		box.setValue(text);
		boolean submitted = false;
		if (args.has("submit") && args.get("submit").getAsBoolean()) {
			box.setFocused(false);
			try {
				// Enter, as the field's own key handler would see it
				submitted = box.keyPressed(new net.minecraft.client.input.KeyEvent(257, 0, 0));
			} catch (Throwable ignored) {
				// field has no submit action; the value is still set
			}
		}
		JsonObject o = new JsonObject();
		o.addProperty("status", "success");
		o.addProperty("value", box.getValue());
		o.addProperty("submitted", submitted);
		return o;
	}

	/** Presses Escape / closes the current screen. */
	public static JsonObject close() {
		Minecraft mc = Minecraft.getInstance();
		Screen s = mc.screen;
		if (s == null) {
			return err("没有打开的界面");
		}
		String name = s.getClass().getSimpleName();
		s.onClose();
		JsonObject o = new JsonObject();
		o.addProperty("status", "success");
		o.addProperty("closed", name);
		Screen after = mc.screen;
		o.addProperty("screen_after", after == null ? "(none)" : after.getClass().getSimpleName());
		return o;
	}

	/**
	 * Joins a world without any clicking: opens the singleplayer save list, or connects
	 * straight to a server address. This is what makes "get me into the game" one call.
	 */
	public static JsonObject join(JsonObject args) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && mc.level != null) {
			JsonObject o = new JsonObject();
			o.addProperty("status", "success");
			o.addProperty("message", "已经在世界中");
			return o;
		}
		if (args.has("server") && args.get("server").isJsonPrimitive()) {
			String addr = args.get("server").getAsString().trim();
			if (addr.isEmpty()) {
				return err("server 地址为空");
			}
			try {
				ServerData sd = new ServerData("mcagent", addr, ServerData.Type.OTHER);
				ServerAddress sa = ServerAddress.parseString(addr);
				ConnectScreen.startConnecting(new TitleScreen(), mc, sa, sd, false, null);
				JsonObject o = new JsonObject();
				o.addProperty("status", "success");
				o.addProperty("message", "正在连接服务器 " + addr);
				return o;
			} catch (Throwable t) {
				return err("连接失败: " + t);
			}
		}
		mc.setScreen(new SelectWorldScreen(new TitleScreen()));
		JsonObject o = new JsonObject();
		o.addProperty("status", "success");
		o.addProperty("message", "已打开单人世界列表，用 gui_list 查看存档，再用 gui_click 进入");
		return o;
	}

	// ------------------------------------------------------------------
	// list entries (world list, server list, resource packs, ...)
	// ------------------------------------------------------------------

	/**
	 * Lists the rows of a selection list on the current screen (saves, servers, ...).
	 *
	 * <p>These rows are not widgets, so {@code gui_list} cannot see them; without this an
	 * agent could open the world list but never pick anything from it.
	 */
	public static JsonObject listEntries(JsonObject args) {
		Minecraft mc = Minecraft.getInstance();
		Screen screen = mc.screen;
		if (screen == null) {
			return err("没有打开的界面");
		}
		AbstractSelectionList<?> list = findSelectionList(screen, args);
		if (list == null) {
			return err("当前界面没有可选的列表（screen=" + screen.getClass().getSimpleName() + "）");
		}
		JsonArray arr = new JsonArray();
		List<?> entries = list.children();
		for (int i = 0; i < entries.size(); i++) {
			Object e = entries.get(i);
			JsonObject o = new JsonObject();
			o.addProperty("index", i);
			o.addProperty("class", e.getClass().getSimpleName());
			o.addProperty("label", entryLabel(e));
			o.addProperty("selected", e == list.getSelected());
			arr.add(o);
		}
		JsonObject out = new JsonObject();
		out.addProperty("status", "success");
		out.addProperty("screen", screen.getClass().getSimpleName());
		out.addProperty("list", list.getClass().getSimpleName());
		Object sel = list.getSelected();
		out.addProperty("selected_index", sel == null ? -1 : entries.indexOf(sel));
		out.add("entries", arr);
		return out;
	}

	/**
	 * Selects a list row, and optionally activates it (double-click / Enter).
	 *
	 * <p>{@code join=true} calls the entry's own action — for a save that means loading it —
	 * which is the difference between highlighting a world and actually playing it.
	 */
	public static JsonObject selectEntry(JsonObject args) {
		Minecraft mc = Minecraft.getInstance();
		Screen screen = mc.screen;
		if (screen == null) {
			return err("没有打开的界面");
		}
		AbstractSelectionList<?> list = findSelectionList(screen, args);
		if (list == null) {
			return err("当前界面没有可选的列表");
		}
		List<?> entries = list.children();
		if (entries.isEmpty()) {
			return err("列表是空的（没有存档或服务器）");
		}

		int idx = -1;
		if (args.has("index") && args.get("index").isJsonPrimitive()) {
			idx = args.get("index").getAsInt();
		} else if (args.has("label") && args.get("label").isJsonPrimitive()) {
			String want = args.get("label").getAsString().toLowerCase(Locale.ROOT).trim();
			for (int i = 0; i < entries.size(); i++) {
				if (entryLabel(entries.get(i)).toLowerCase(Locale.ROOT).contains(want)) {
					idx = i;
					break;
				}
			}
			if (idx < 0) {
				StringBuilder sb = new StringBuilder();
				for (Object e : entries) {
					if (sb.length() > 0) {
						sb.append(", ");
					}
					sb.append(entryLabel(e));
				}
				return err("找不到包含 \"" + args.get("label").getAsString() + "\" 的条目。可选: " + sb);
			}
		} else {
			// default to the first row, which is what a player would click
			idx = 0;
		}
		if (idx < 0 || idx >= entries.size()) {
			return err("index " + idx + " 越界，列表有 " + entries.size() + " 项");
		}

		Object entry = entries.get(idx);
		String label = entryLabel(entry);
		setSelectedRaw(list, entry);

		boolean joined = false;
		if (args.has("join") && args.get("join").getAsBoolean()) {
			joined = activateEntry(entry);
		}

		JsonObject o = new JsonObject();
		o.addProperty("status", "success");
		o.addProperty("selected", label);
		o.addProperty("index", idx);
		o.addProperty("activated", joined);
		Screen after = mc.screen;
		o.addProperty("screen_after", after == null ? "(none)" : after.getClass().getSimpleName());
		return o;
	}

	/**
	 * Runs a list row's own action. Saves expose {@code joinWorld}; other lists are driven
	 * through their click handler, which is what a double-click reaches.
	 */
	private static boolean activateEntry(Object entry) {
		// preferred: the entry's own action
		for (String m : new String[]{"joinWorld", "editWorld", "recreateWorld"}) {
			try {
				Method mm = entry.getClass().getMethod(m);
				mm.invoke(entry);
				return true;
			} catch (Throwable ignored) {
				// this entry type does not expose that action
			}
		}
		// fallback: synthesise a double-click at the row's centre
		try {
			if (entry instanceof GuiEventListener gel) {
				AbstractSelectionList<?> list = null;
				// the row needs its on-screen box; ask it through the list it belongs to
				Minecraft mc = Minecraft.getInstance();
				if (mc.screen != null) {
					list = findSelectionList(mc.screen, new JsonObject());
				}
				if (list != null) {
					int i = list.children().indexOf(entry);
					if (i >= 0) {
						double cy = (list.getRowTop(i) + list.getRowBottom(i)) / 2.0;
						double cx = (list.getRowLeft() + list.getRowRight()) / 2.0;
						MouseButtonEvent ev = new MouseButtonEvent(cx, cy, new MouseButtonInfo(0, 0));
						return gel.mouseClicked(ev, true);
					}
				}
			}
		} catch (Throwable ignored) {
			// nothing else to try
		}
		return false;
	}

	/**
	 * Selects a row on a list whose element type cannot be named here.
	 *
	 * <p>{@code AbstractSelectionList<E extends Entry<E>>} binds E to a protected nested
	 * type, so no concrete type argument can be written for it and a plain call does not
	 * compile. Reflection reaches the same public method without naming the bound.
	 */
	private static void setSelectedRaw(AbstractSelectionList<?> list, Object entry) {
		try {
			Method m = list.getClass().getMethod("setSelected", Object.class);
			m.invoke(list, entry);
			return;
		} catch (Throwable ignored) {
			// the erased signature is not Object on this build; look it up by name below
		}
		for (Method m : list.getClass().getMethods()) {
			if (!m.getName().equals("setSelected") || m.getParameterCount() != 1) {
				continue;
			}
			try {
				m.invoke(list, entry);
				return;
			} catch (Throwable ignored) {
				// try the next overload
			}
		}
	}

	/** Finds the selection list on a screen, optionally by class-name substring. */
	private static AbstractSelectionList<?> findSelectionList(Screen screen, JsonObject args) {		String want = args != null && args.has("list") && args.get("list").isJsonPrimitive()
				? args.get("list").getAsString().toLowerCase(Locale.ROOT)
				: "";
		try {
			for (GuiEventListener l : screen.children()) {
				if (l instanceof AbstractSelectionList<?> list) {
					if (want.isEmpty() || list.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains(want)) {
						return list;
					}
				}
			}
		} catch (Throwable ignored) {
			// no list on this screen
		}
		return null;
	}

	private static String entryLabel(Object e) {
		// saves and servers both expose a display name
		for (String m : new String[]{"getLevelName", "getLevelSummary", "getName"}) {
			try {
				Method mm = e.getClass().getMethod(m);
				Object v = mm.invoke(e);
				if (v instanceof String s && !s.isBlank()) {
					return s;
				}
				if (v != null) {
					try {
						Method nm = v.getClass().getMethod("getLevelName");
						Object n = nm.invoke(v);
						if (n instanceof String s2 && !s2.isBlank()) {
							return s2;
						}
					} catch (Throwable ignored) {
						// not a level summary
					}
				}
			} catch (Throwable ignored) {
				// try the next accessor
			}
		}
		return e.getClass().getSimpleName();
	}

	// ------------------------------------------------------------------
	// widget discovery
	// ------------------------------------------------------------------

	/**
	 * Collects the widgets on a screen.
	 *
	 * <p>{@code Screen.children()} is the public accessor for the screen's event listeners
	 * and includes every interactive widget, so no reflection is needed. The result is
	 * filtered to {@link AbstractWidget} because only those can be clicked or labelled.
	 */
	private static List<AbstractWidget> widgetsOf(Screen screen) {
		List<AbstractWidget> out = new ArrayList<>();
		try {
			for (GuiEventListener l : screen.children()) {
				if (l instanceof AbstractWidget w) {
					out.add(w);
				}
			}
		} catch (Throwable ignored) {
			// fall through with whatever was collected
		}
		return out;
	}

	/** The text a player would read on the widget. */
	private static String labelOf(AbstractWidget w) {
		try {
			Component msg = w.getMessage();
			if (msg != null) {
				String s = msg.getString();
				if (s != null && !s.isBlank()) {
					return s;
				}
			}
		} catch (Throwable ignored) {
			// fall through
		}
		if (w instanceof EditBox eb) {
			String v = eb.getValue();
			if (v != null && !v.isBlank()) {
				return v;
			}
		}
		return w.getClass().getSimpleName();
	}

	private static JsonObject err(String msg) {
		JsonObject o = new JsonObject();
		o.addProperty("status", "error");
		o.addProperty("message", msg);
		return o;
	}

	/** A plain left click with no modifier keys held, for {@code Button.onPress}. */
	private static InputWithModifiers noModifiers() {
		return new InputWithModifiers() {
			@Override
			public int input() {
				return 0;
			}

			@Override
			public int modifiers() {
				return 0;
			}
		};
	}
}
