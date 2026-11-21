package com.mcagent.client.mc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcagent.client.pathing.AStar;
import com.mcagent.client.pathing.Goal;
import com.mcagent.config.ConfigManager;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class ActionExecutor {
	private static final ActionExecutor INSTANCE = new ActionExecutor();

	private static final java.util.concurrent.ExecutorService PATH_EXEC = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "MCAgent-Path");
		t.setDaemon(true);
		return t;
	});

	private enum Task { WALK, DIG, PLACE, CRAFT, ATTACK, WAIT, USE, BARITONE, LOCAL_WALK }

	private final Minecraft mc = Minecraft.getInstance();

	private CompletableFuture<String> pending;
	private Task task;
	private int ticksLeft;

	private Vec3 walkTarget;
	/** True when the caller supplied an explicit y for the current walk. */
	private boolean exactHeight;
	/** Ticks spent in the current walk, used to pace water re-planning. */
	private int swimTicks;
	/** The bank the bot is currently swimming for; cached so the choice stays stable. */
	private BlockPos waterExit;
	/** The current route as search nodes, so each step's dig/place lists stay available. */
	private List<com.mcagent.client.pathing.Move> walkMoves;
	/** Destination handed to Baritone, and the stall counter watching it. */
	private Vec3 baritoneTarget;
	private int baritoneStall;
	private int baritoneRetries;
	/** The independent navigator (ported mineflayer-pathfinder, not Baritone). */
	private final com.mcagent.client.pathing.PathExecutor pathExec =
			new com.mcagent.client.pathing.PathExecutor(net.minecraft.client.Minecraft.getInstance());
	/** Trips shorter than this are steered directly; Baritone's startup is not worth it. */
	private static final double BARITONE_MIN_DISTANCE = 8.0;
	private List<BlockPos> walkPath;
	private int walkIdx;
	private boolean pathPartial;
	private java.util.concurrent.CompletableFuture<AStar.Result> pathFuture;
	private int replans;
	private Vec3 lastPos;
	private int stuckChecks;

	private BlockPos digPos;
	private boolean digStarted;

	private BlockPos placePos;
	private String placeItem;
	// committed support face for the current placement (see tickPlace)
	private Direction placeFace;
	private Vec3 placeHit;
	private BlockPos placeNeighbor;

	private Entity attackTarget;
	private int attackCd;

	private int waitTicks;

	private int craftPhase;
	private boolean craftUseTable;
	private BlockPos craftTablePos;
	private String[] craftGrid;
	private String craftOutput;
	private int craftBeforeCount;
	private int craftResultClicks;
	private int craftIdle;
	private Vec3 subWalkTarget;
	private List<BlockPos> subWalkPath;
	private int subWalkIdx;

	private int eatTicks;
	private int respawnCd;
	private int useTicks;
	private BlockPos pathDigPos;
	private boolean pathDigStarted;

	public static ActionExecutor get() {
		return INSTANCE;
	}

	/** The local navigator, so the renderer can draw the route it is following. */
	public com.mcagent.client.pathing.PathExecutor localNavigator() {
		return pathExec;
	}

	public void cancelAll() {
		if (pending != null) {
			CompletableFuture<String> f = pending;
			pending = null;
			releaseKeys();
			f.complete("{\"status\":\"error\",\"message\":\"任务被玩家取消\"}");
		}
	}

	public static CompletableFuture<String> runTool(String tool, JsonObject args) {
		CompletableFuture<String> future = new CompletableFuture<>();
		Minecraft.getInstance().execute(() -> {
			try {
				INSTANCE.begin(tool, args == null ? new JsonObject() : args, future);
			} catch (Exception e) {
				INSTANCE.releaseKeys();
				future.complete("{\"status\":\"error\",\"message\":\"内部异常: " + esc(String.valueOf(e)) + "\"}");
			}
		});
		return future;
	}

	/** Tools that only read state, and therefore may run while an action is in progress. */
	private static boolean isReadOnly(String tool) {
		return switch (tool) {
			case "get_status", "get_env", "find_block", "gui_list", "screen", "probe" -> true;
			default -> false;
		};
	}

	/** Executes a read-only tool immediately, without taking the action lock. */
	private String readOnly(String tool, JsonObject args) {
		try {
			if (mc.player == null || mc.level == null) {
				// the screen probe still works outside a world
				if ("screen".equals(tool)) {
					return screenInfo();
				}
				if ("gui_list".equals(tool)) {
					return GuiController.listWidgets().toString();
				}
				return "{\"status\":\"error\",\"message\":\"未进入世界\"}";
			}
			return switch (tool) {
				case "get_status" -> getStatus();
				case "get_env" -> getEnv();
				case "find_block" -> findBlock(str(args, "block", ""), intArg(args, "radius", 1024),
						Math.min(20, Math.max(1, intArg(args, "limit", 5))));
				case "gui_list" -> GuiController.listWidgets().toString();
				case "screen" -> screenInfo();
				case "probe" -> probeBlocks(args);
				default -> "{\"status\":\"error\",\"message\":\"未知只读工具\"}";
			};
		} catch (Throwable t) {
			return "{\"status\":\"error\",\"message\":\"" + esc(String.valueOf(t)) + "\"}";
		}
	}

	/**
	 * Reads raw blocks in a box, plus what the navigator's own classifier makes of them.
	 *
	 * <p>Added because diagnosing a stuck route from the outside is guesswork: the generated
	 * maze layout, the client's loaded chunks and the planner's view of a cell can all differ,
	 * and the only way to tell which is wrong is to ask the running game what is actually
	 * there. Reports block name, collision, and the Movements classification per cell.
	 */
	private String probeBlocks(JsonObject args) {
		int x0 = intArg(args, "x0", 0);
		int y0 = intArg(args, "y0", 0);
		int z0 = intArg(args, "z0", 0);
		int x1 = intArg(args, "x1", x0);
		int y1 = intArg(args, "y1", y0);
		int z1 = intArg(args, "z1", z0);
		if (Math.abs(x1 - x0) > 32 || Math.abs(y1 - y0) > 32 || Math.abs(z1 - z0) > 32) {
			return "{\"status\":\"error\",\"message\":\"探针范围过大(每轴上限32)\"}";
		}
		JsonArray cells = new JsonArray();
		for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
			for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
				for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
					BlockPos pos = new BlockPos(x, y, z);
					BlockState st = mc.level.getBlockState(pos);
					boolean solid = !st.getCollisionShape(mc.level, pos).isEmpty();
					JsonObject c = new JsonObject();
					c.addProperty("x", x);
					c.addProperty("y", y);
					c.addProperty("z", z);
					c.addProperty("block", blockName(st));
					c.addProperty("solid", solid);
					// What the navigator will conclude about standing here.
					c.addProperty("standable", isStandable(pos));
					cells.add(c);
				}
			}
		}
		JsonObject o = new JsonObject();
		o.addProperty("status", "success");
		o.addProperty("count", cells.size());
		o.add("cells", cells);
		return o.toString();
	}

	private String screenInfo() {		Minecraft m = Minecraft.getInstance();
		JsonObject o = new JsonObject();
		o.addProperty("status", "success");
		o.addProperty("screen", m.screen == null ? "(none — in world / HUD)" : m.screen.getClass().getSimpleName());
		o.addProperty("in_world", m.player != null && m.level != null);
		o.addProperty("paused", m.isPaused());
		return o.toString();
	}

	private void begin(String tool, JsonObject args, CompletableFuture<String> future) {
		// Observation tools never touch the world, so they must not be blocked by a running
		// action. Blocking them made the bot unmonitorable exactly when monitoring matters:
		// during a long walk the caller could not read position or air at all, and a bot
		// that is drowning, stuck or off course looked identical to one that was fine.
		if (isReadOnly(tool)) {
			future.complete(readOnly(tool, args));
			return;
		}
		if (pending != null) {
			future.complete("{\"status\":\"error\",\"message\":\"另一个动作正在执行\"}");
			return;
		}
		// GUI tools work at the title screen too, so they run before the in-world check.
		switch (tool) {
			case "gui_list" -> {
				future.complete(GuiController.listWidgets().toString());
				return;
			}
			case "gui_click" -> {
				future.complete(GuiController.click(args).toString());
				return;
			}
			case "gui_type" -> {
				future.complete(GuiController.type(args).toString());
				return;
			}
			case "gui_click_at" -> {
				future.complete(GuiController.clickAt(args).toString());
				return;
			}
			case "gui_close" -> {
				// With no screen open there is nothing to close, so open the pause menu
				// instead. That is what pressing Escape does, and it is the only route out of
				// a world — without it the agent can navigate every menu except the first one.
				if (mc.screen == null && mc.level != null) {
					mc.setScreen(new net.minecraft.client.gui.screens.PauseScreen(true));
					future.complete("{\"status\":\"success\",\"message\":\"已打开暂停菜单\"}");
					return;
				}
				future.complete(GuiController.close().toString());
				return;
			}
			case "gui_entries" -> {
				future.complete(GuiController.listEntries(args).toString());
				return;
			}
			case "gui_select" -> {
				future.complete(GuiController.selectEntry(args).toString());
				return;
			}
			case "gui_join" -> {
				future.complete(GuiController.join(args).toString());
				return;
			}
			case "plan_path" -> {
				// Plan without walking, and hand back every node.
				//
				// Looking at the route is the only way to tell "the search found a bad path"
				// apart from "the executor failed to follow a good one" — from the outside both
				// look like a bot that will not move. The caller can draw the result.
				int pgx = intArg(args, "x", (int) mc.player.getX());
				int pgz = intArg(args, "z", (int) mc.player.getZ());
				Integer psy = surfaceYAt(pgx, pgz, (int) mc.player.getY());
				int pgy = psy != null ? psy : (int) mc.player.getY();
				boolean pdig = args.has("dig") && args.get("dig").isJsonPrimitive()
						&& args.get("dig").getAsBoolean();
				com.mcagent.client.pathing.Goal pg = new com.mcagent.client.pathing.Goal.Near(pgx, pgy, pgz, 2);
				com.mcagent.client.pathing.AStar.Result pr = com.mcagent.client.pathing.AStar.search(
						mc.level, mc.player.blockPosition(), pg, 200000, 0, pdig);
				StringBuilder psb = new StringBuilder();
				psb.append("{\"status\":\"success\",\"complete\":").append(pr.complete)
						.append(",\"nodes\":").append(pr.path.size())
						.append(",\"visited\":").append(pr.visited)
						.append(",\"miss_by\":").append(String.format(java.util.Locale.ROOT, "%.1f", pr.missBy))
						.append(",\"start\":[").append(mc.player.blockPosition().getX()).append(",")
						.append(mc.player.blockPosition().getY()).append(",")
						.append(mc.player.blockPosition().getZ()).append("]")
						.append(",\"goal\":[").append(pgx).append(",").append(pgy).append(",").append(pgz).append("]")
						.append(",\"path\":[");
				for (int i = 0; i < pr.path.size(); i++) {
					com.mcagent.client.pathing.Move m = pr.path.get(i);
					if (i > 0) {
						psb.append(",");
					}
					psb.append("[").append(m.x).append(",").append(m.y).append(",").append(m.z).append("]");
				}
				psb.append("]}");
				future.complete(psb.toString());
				return;
			}
			case "screen" -> {
				Minecraft m = Minecraft.getInstance();
				JsonObject o = new JsonObject();
				o.addProperty("status", "success");
				o.addProperty("screen", m.screen == null ? "(none — in world / HUD)" : m.screen.getClass().getSimpleName());
				o.addProperty("in_world", m.player != null && m.level != null);
				o.addProperty("paused", m.isPaused());
				future.complete(o.toString());
				return;
			}
			default -> {
				// fall through to the world-bound tools below
			}
		}
		if (mc.player == null || mc.level == null) {
			future.complete("{\"status\":\"error\",\"message\":\"未进入世界（可用 gui_list / gui_click / gui_join 先进入游戏）\"}");
			return;
		}
		pending = future;
		ticksLeft = 0;
		switch (tool) {
			case "get_env" -> finish(getEnv());
			case "get_status" -> finish("success", getStatus());
			case "find_block" -> {
				String r = findBlock(str(args, "block", ""), intArg(args, "radius", 1024), Math.min(20, Math.max(1, intArg(args, "limit", 5))));
				finish(r.startsWith("{\"status\":\"error") ? "error" : "success", r);
			}
			case "look_at" -> {
				Vec3 t = new Vec3(doubleArg(args, "x", mc.player.getX()), doubleArg(args, "y", mc.player.getEyeY()), doubleArg(args, "z", mc.player.getZ()));
				face(t);
				finish("success", "{\"status\":\"success\",\"message\":\"已转向\"}");
			}
			case "select" -> {
				if (args.has("slot")) {
					int slot = Mth.clamp(intArg(args, "slot", 0), 0, 8);
					mc.player.getInventory().setSelectedSlot(slot);
					finish("success", "{\"status\":\"success\",\"message\":\"切换到快捷栏第" + (slot + 1) + "格: " + heldName() + "\"}");
				} else {
					String item = str(args, "item", "");
					if (item.isEmpty()) {
						finish("error", "{\"status\":\"error\",\"message\":\"缺少 item 参数\"}");
					} else {
						finish(selectItem(item));
					}
				}
			}
			case "say" -> {
				String msg = str(args, "message", "").trim();
				if (msg.isEmpty()) {
					finish("error", "{\"status\":\"error\",\"message\":\"消息为空\"}");
				} else {
					if (msg.startsWith("/")) {
						mc.player.connection.sendCommand(msg.substring(1));
					} else {
						mc.player.connection.sendChat(msg);
					}
					finish("success", "{\"status\":\"success\",\"message\":\"已发送: " + esc(msg) + "\"}");
				}
			}
			case "set_option" -> finish(applyOption(args));
			case "drop" -> {
				boolean all = boolArg(args, "all", true);
				mc.player.connection.send(new net.minecraft.network.protocol.game.ServerboundPlayerActionPacket(
						all ? net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS : net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.DROP_ITEM,
						BlockPos.ZERO, Direction.UP));
				finish("success", "{\"status\":\"success\",\"message\":\"已丢出手持物品(" + (all ? "整组" : "1个") + ")\"}");
			}
			case "swap_hands" -> {
				mc.player.connection.send(new net.minecraft.network.protocol.game.ServerboundPlayerActionPacket(
						net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
						BlockPos.ZERO, Direction.UP));
				finish("success", "{\"status\":\"success\",\"message\":\"已切换主副手物品\"}");
			}
			case "use_item" -> {
				useTicks = Mth.clamp(intArg(args, "duration", 12), 3, 200);
				task = Task.USE;
			}
			case "wait" -> {
				waitTicks = Mth.clamp(intArg(args, "ticks", 20), 1, 400);
				task = Task.WAIT;
				ticksLeft = waitTicks;
			}
			case "walk_to" -> {
				// Height is optional. Callers almost never know the real ground level — they
				// read it from an earlier status or guess it — and treating a guessed Y as a
				// hard goal makes the bot climb to nonsense elevations or grind against a
				// waypoint it can never satisfy. With y omitted the bot walks to the
				// horizontal position and settles on whatever ground is there.
				double tx = doubleArg(args, "x", mc.player.getX());
				double tz = doubleArg(args, "z", mc.player.getZ());
				double ty = args.has("y") && args.get("y").isJsonPrimitive()
						? doubleArg(args, "y", mc.player.getY())
						: mc.player.getY();
				walkTarget = new Vec3(tx, ty, tz);
				exactHeight = args.has("y") && args.get("y").isJsonPrimitive();
				replans = 0;
				if (!planPath()) {
					walkPath = null;
				}
				lastPos = mc.player.position();
				stuckChecks = 0;
				swimTicks = 0;
				waterExit = null;
				pathDigPos = null;
				pathDigStarted = false;
				ticksLeft = 20 * 180;

				// Two independent engines, chosen per call.
				//
				// "local" (default) is the in-house navigator: the ported mineflayer-pathfinder
				// search plus PathExecutor, which re-anchors and re-plans every tick. It has no
				// dependency on anything else being installed.
				//
				// "baritone" hands the trip to Baritone, which is stronger on parkour, bridging
				// and long routes. It is left as an explicit choice — a second opinion the agent
				// can reach for when the local engine reports it cannot get through.
				String engine = args.has("engine") && args.get("engine").isJsonPrimitive()
						? args.get("engine").getAsString()
						: "local";

				int gx = (int) Math.floor(tx);
				int gz = (int) Math.floor(tz);

				if ("baritone".equalsIgnoreCase(engine) && BaritoneBridge.available()) {
					double goalY = ty;
					if (!exactHeight) {
						Integer surface = surfaceYAt(gx, gz, (int) mc.player.getY());
						if (surface != null) {
							goalY = surface;
						}
					}
					baritoneTarget = new Vec3(tx, goalY, tz);
					baritoneStall = 0;
					baritoneRetries = 0;
					lastPos = mc.player.position();
					BaritoneBridge.gotoPos(tx, goalY, tz);
					task = Task.BARITONE;
				} else {
					// local engine: aim at the ground level of the target column unless the
					// caller pinned a height, and use a near-goal so a target cell occupied by a
					// block (a tree trunk, a chest) is still reachable
					int goalY;
					if (exactHeight) {
						goalY = (int) Math.floor(ty);
					} else {
						Integer surface = surfaceYAt(gx, gz, (int) mc.player.getY());
						goalY = surface != null ? surface : (int) Math.floor(mc.player.getY());
					}
					Goal g = new Goal.Near(gx, goalY, gz, 2);
					// Whether this trip may break blocks is the caller's call.
					//
					// Default is OFF. Mining is destructive and surprising — a walk across open
					// ground turned into a trench because tunnelling looked marginally cheaper —
					// so it is opt-in: pass "dig": true when digging through is actually wanted.
					// With it off, a failure means "genuinely walled in", which is far more
					// useful to the agent than a hole in the landscape.
					boolean allowDig = args.has("dig") && args.get("dig").isJsonPrimitive()
							&& args.get("dig").getAsBoolean();
					pathExec.start(g, tx, ty, tz, allowDig);
					task = Task.LOCAL_WALK;
				}
			}
			case "dig" -> {
				BlockPos cur = mc.player.blockPosition();
				digPos = new BlockPos(intArg(args, "x", cur.getX()), intArg(args, "y", cur.getY()), intArg(args, "z", cur.getZ()));
				digStarted = false;
				ticksLeft = 20 * 25;
				task = Task.DIG;
			}
			case "place" -> {
				BlockPos cur2 = mc.player.blockPosition();
				placePos = new BlockPos(intArg(args, "x", cur2.getX()), intArg(args, "y", cur2.getY()), intArg(args, "z", cur2.getZ()));
				placeItem = str(args, "item", "");
				ticksLeft = 60;
				task = Task.PLACE;
			}
			case "craft" -> beginCraft(args);
			case "attack" -> {
				String target = str(args, "target", "monster");
				int radius = Mth.clamp(intArg(args, "radius", 8), 2, 16);
				attackTarget = findTarget(target, radius);
				if (attackTarget == null) {
					finish("error", "{\"status\":\"error\",\"message\":\"半径" + radius + "内没有找到目标(" + target + ")\"}");
				} else {
					attackCd = 0;
					ticksLeft = 20 * 40;
					task = Task.ATTACK;
				}
			}
			case "baritone" -> {
				String cmd = str(args, "command", "").trim().replaceFirst("^#", "");
				if (cmd.isEmpty()) {
					finish("error", "{\"status\":\"error\",\"message\":\"缺少 command 参数，如: follow 玩家名 / goto x y z / mine 方块名 数量 / stop\"}");
				} else if (!BaritoneBridge.available()) {
					finish("error", "{\"status\":\"error\",\"message\":\"本客户端未安装Baritone，无法执行。请改用内置工具(walk_to/dig等)\"}");
				} else {
					String r = BaritoneBridge.execute(cmd);
					finish(r.startsWith("Baritone执行失败") || r.startsWith("参数格式错误") || r.startsWith("不支持的Baritone命令") ? "error" : "success",
							"{\"status\":\"" + (r.startsWith("Baritone执行失败") || r.startsWith("参数格式错误") || r.startsWith("不支持的Baritone命令") ? "error" : "success") + "\",\"message\":\"" + esc(r) + "，Baritone后台执行中，用 get_status 观察进度\"}");
				}
			}
			case "blacklist" -> {
				String op = str(args, "op", "list");
				String player = str(args, "player", "");
				String r = blacklistOp(op, player);
				boolean err = r.startsWith("{\"status\":\"error");
				finish(err ? "error" : "success", r);
			}
			case "web_search" -> {
				String q = str(args, "query", "").trim();
				if (q.isEmpty()) {
					finish("error", "{\"status\":\"error\",\"message\":\"缺少 query 参数\"}");
				} else {
					finish(webSearch(q));
				}
			}
			default -> finish("error", "{\"status\":\"error\",\"message\":\"未知工具: " + esc(tool) + "，可用: get_status,find_block,walk_to,look_at,dig,place,select,craft,attack,say,wait,web_search,done,abort\"}");
		}
	}

	private void finish(String status, String message) {
		CompletableFuture<String> f = pending;
		pending = null;
		walkPath = null;
		placeFace = null;
		placeHit = null;
		placeNeighbor = null;
		Humanizer.reset();
		releaseKeys();
		if (f != null) {
			f.complete(message);
		}
		if ("error".equals(status)) {
			chat("§c动作失败: " + stripJson(message));
		}
	}

	private void finish(String message) {
		finish("success", message);
	}

	public void tick() {
		if (mc.player == null || mc.level == null) {
			return;
		}
		LocalPlayer p = mc.player;
		if (p.isDeadOrDying()) {
			if (pending != null) {
				cancelAll();
				chat("§e玩家死亡，任务中断。已自动复活后可继续 @mcagent 派新任务或重试");
			}
			if (ConfigManager.get().autoRespawn) {
				if (mc.screen instanceof DeathScreen) {
					if (respawnCd <= 0) {
						p.connection.send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
						mc.setScreen(null);
						respawnCd = 20;
					}
				}
			}
		}
		if (respawnCd > 0) {
			respawnCd--;
		}
		tickAutoEat();
		if (pending == null || task == null) {
			return;
		}
		switch (task) {
			case WALK -> tickWalk();
			case BARITONE -> tickBaritone();
			case LOCAL_WALK -> tickLocalWalk();
			case DIG -> tickDig();
			case PLACE -> tickPlace();
			case CRAFT -> tickCraft();
			case ATTACK -> tickAttack();
			case WAIT -> tickWait();
			case USE -> tickUse();
		}
	}

	private void tickUse() {
		useTicks--;
		mc.options.keyShift.setDown(true);
		mc.options.keyUse.setDown(true);
		if (useTicks <= 0) {
			releaseKeys();
			finish("{\"status\":\"success\",\"message\":\"已长按右键使用手持物品\"}");
		}
	}

	private String applyOption(JsonObject args) {
		String key = str(args, "key", "").toLowerCase(java.util.Locale.ROOT).trim();
		double num = doubleArg(args, "value", 0);
		boolean flag = args.has("value") && args.get("value").isJsonPrimitive() && args.get("value").getAsBoolean();
		String vs = str(args, "value", "");
		var opts = mc.options;
		switch (key) {
			case "fov" -> {
				opts.fov().set((int) Mth.clamp(num, 30, 110));
				return "{\"status\":\"success\",\"message\":\"FOV 已设为 " + (int) Mth.clamp(num, 30, 110) + "\"}";
			}
			case "brightness" -> {
				opts.gamma().set(Mth.clamp(num, 0, 1));
				return "{\"status\":\"success\",\"message\":\"亮度已设为 " + String.format("%.2f", Mth.clamp(num, 0, 1)) + "\"}";
			}
			case "gui_scale" -> {
				int g = (int) Mth.clamp(num, 0, 4);
				opts.guiScale().set(g);
				mc.resizeDisplay();
				return "{\"status\":\"success\",\"message\":\"GUI缩放已设为 " + g + "\"}";
			}
			case "sensitivity" -> {
				opts.sensitivity().set(Mth.clamp(num, 0, 1));
				return "{\"status\":\"success\",\"message\":\"鼠标灵敏度已设为 " + String.format("%.2f", Mth.clamp(num, 0, 1)) + "\"}";
			}
			case "render_distance" -> {
				int r = (int) Mth.clamp(num, 2, 32);
				opts.renderDistance().set(r);
				return "{\"status\":\"success\",\"message\":\"渲染距离已设为 " + r + " 区块\"}";
			}
			case "simulation_distance" -> {
				int r = (int) Mth.clamp(num, 5, 32);
				opts.simulationDistance().set(r);
				return "{\"status\":\"success\",\"message\":\"模拟距离已设为 " + r + "\"}";
			}
			case "max_fps" -> {
				int f = (int) Mth.clamp(num, 10, 260);
				opts.framerateLimit().set(f);
				return "{\"status\":\"success\",\"message\":\"帧率上限已设为 " + f + "\"}";
			}
			case "fullscreen" -> {
				opts.fullscreen().set(flag);
				return "{\"status\":\"success\",\"message\":\"全屏已" + (flag ? "开启" : "关闭") + "\"}";
			}
			case "auto_jump" -> {
				opts.autoJump().set(flag);
				return "{\"status\":\"success\",\"message\":\"自动跳跃已" + (flag ? "开启" : "关闭") + "\"}";
			}
			case "view_bobbing" -> {
				opts.bobView().set(flag);
				return "{\"status\":\"success\",\"message\":\"视角摇晃已" + (flag ? "开启" : "关闭") + "\"}";
			}
			case "perspective" -> {
				net.minecraft.client.CameraType t = switch (vs.toLowerCase(java.util.Locale.ROOT)) {
					case "third_person_back", "第三人称" -> net.minecraft.client.CameraType.THIRD_PERSON_BACK;
					case "third_person_front" -> net.minecraft.client.CameraType.THIRD_PERSON_FRONT;
					default -> net.minecraft.client.CameraType.FIRST_PERSON;
				};
				opts.setCameraType(t);
				return "{\"status\":\"success\",\"message\":\"视角已切换为 " + t.name() + "\"}";
			}
			default -> {
				return "{\"status\":\"error\",\"message\":\"不支持的设置项: " + esc(key) + "，支持: fov, brightness, gui_scale, sensitivity, render_distance, simulation_distance, max_fps, fullscreen, auto_jump, view_bobbing, perspective\"}";
			}
		}
	}

	private void tickAutoEat() {
		LocalPlayer p = mc.player;
		if (eatTicks > 0) {
			eatTicks--;
			mc.options.keyShift.setDown(true);
			mc.options.keyUse.setDown(true);
			if (eatTicks == 0 || (eatTicks < 40 && !p.isUsingItem() && mc.options.keyUse.isDown())) {
				releaseKeys();
			}
			return;
		}
		if (!ConfigManager.get().autoEat || pending != null || mc.screen != null || p.isUsingItem()) {
			return;
		}
		if (p.getFoodData().getFoodLevel() > 14) {
			return;
		}
		int foodSlot = -1;
		for (int i = 0; i < 9; i++) {
			ItemStack st = p.getInventory().getItem(i);
			net.minecraft.world.food.FoodProperties fp = st.get(net.minecraft.core.component.DataComponents.FOOD);
			if (!st.isEmpty() && fp != null && !fp.canAlwaysEat()) {
				foodSlot = i;
				break;
			}
		}
		if (foodSlot < 0) {
			for (int i = 9; i < 36; i++) {
				ItemStack st = p.getInventory().getItem(i);
				net.minecraft.world.food.FoodProperties fp2 = st.get(net.minecraft.core.component.DataComponents.FOOD);
				if (!st.isEmpty() && fp2 != null && !fp2.canAlwaysEat()) {
					int menuSlot = findInvMenuSlot(p.inventoryMenu, i);
					if (menuSlot >= 0) {
						clickSlot(p.inventoryMenu, menuSlot, p.getInventory().getSelectedSlot(), ClickType.SWAP);
						foodSlot = p.getInventory().getSelectedSlot();
					}
					break;
				}
			}
		}
		if (foodSlot < 0) {
			return;
		}
		p.getInventory().setSelectedSlot(foodSlot);
		eatTicks = 55;
	}

	private void tickWait() {
		ticksLeft--;
		if (ticksLeft <= 0) {
			finish("{\"status\":\"success\",\"message\":\"等待结束\"}");
		}
	}

	private boolean planPath() {
		LocalPlayer p = mc.player;
		BlockPos start = p.blockPosition();
		int gx = (int) Math.floor(walkTarget.x);
		int gz = (int) Math.floor(walkTarget.z);

		// Height matters after all.
		//
		// Aiming purely at the x/z column made the bot stop at the foot of a cliff and
		// report success while the destination — a tree, a ledge, a chest — sat seven blocks
		// overhead. When the caller gives a y, honour it; when it does not, use the ground
		// level at that column, which is what "go to these coordinates" means to a player.
		int gy;
		if (exactHeight) {
			gy = (int) Math.floor(walkTarget.y);
		} else {
			Integer surface = surfaceYAt(gx, gz, start.getY());
			if (surface == null) {
				// nothing standable in a sane band: fall back to a pure column goal
				Goal fallback = new Goal.XZ(gx, gz);
				BlockPos flat = new BlockPos(gx, start.getY(), gz);
				if (start.distSqr(flat) > 512 * 512) {
					return false;
				}
				return search(start, fallback, flat, gx, gz);
			}
			gy = surface;
		}
		Goal goal = new Goal.Near(gx, gy, gz, 2);
		BlockPos flat = new BlockPos(gx, start.getY(), gz);
		if (start.distSqr(flat) > 512 * 512) {
			return false;
		}
		return search(start, goal, flat, gx, gz);
	}

	/** Runs the search, synchronously when close and on the worker thread when far. */
	private boolean search(BlockPos start, Goal goal, BlockPos flat, int gx, int gz) {
		if (start.distSqr(flat) < 32 * 32) {
			AStar.Result r = AStar.search(mc.level, start, goal, 60000, 64);
			if (r != null && !r.path.isEmpty()) {
				applyPath(r);
				return true;
			}
			return false;
		}
		final Goal fg = goal;
		pathFuture = java.util.concurrent.CompletableFuture.supplyAsync(
				() -> AStar.search(mc.level, start, goal, 200000, 64), PATH_EXEC);
		return true;
	}

	/**
	 * Ground level at a column: the nearest standable cell to the player's own height.
	 *
	 * <p>Probes outward from {@code nearY} rather than scanning down from the sky. Scanning
	 * from the top finds whatever solid surface is highest — a roof, a ceiling, a canopy —
	 * and sends the bot to stand on top of the maze instead of inside it. That produced a
	 * goal four blocks above the floor, an unreachable target, and a walk that gave up in the
	 * middle of a corridor while insisting it was on its way.
	 *
	 * <p>Downward is preferred at equal distance: "go to these coordinates" means the floor
	 * there, not the roof above it.
	 */
	private Integer surfaceYAt(int x, int z, int nearY) {
		int lo = Math.max(mc.level.getMinY() + 1, nearY - 40);
		int hi = Math.min(mc.level.getMaxY() - 2, nearY + 40);
		int reach = Math.max(hi - nearY, nearY - lo);
		for (int d = 0; d <= reach; d++) {
			int down = nearY - d;
			if (down >= lo && isStandable(new BlockPos(x, down, z))) {
				return down;
			}
			int up = nearY + d;
			if (up <= hi && isStandable(new BlockPos(x, up, z))) {
				return up;
			}
		}
		return null;
	}

	/** Adopts a search result as the active route, keeping each step's dig/place metadata. */
	private void applyPath(AStar.Result r) {
		walkMoves = r.path;
		List<BlockPos> pts = new ArrayList<>(r.path.size());
		for (com.mcagent.client.pathing.Move m : r.path) {
			pts.add(m.pos());
		}
		walkPath = pts;
		walkIdx = 0;
		pathPartial = !r.complete;
	}

	/**
	 * Finds a standable cell at (or near) {@code goal}, preferring the requested height and
	 * then searching outward. Returns null when nothing standable is within reach, in which
	 * case the original goal is used unchanged.
	 */
	private BlockPos standableNear(BlockPos goal) {
		for (int r = 0; r <= 4; r++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dx = -r; dx <= r; dx++) {
					for (int dz = -r; dz <= r; dz++) {
						// only the shell at this radius, so nearer cells win
						if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != r) {
							continue;
						}
						BlockPos c = goal.offset(dx, dy, dz);
						if (isStandable(c)) {
							return c;
						}
					}
				}
			}
		}
		return null;
	}

	/** True when a player can stand in this cell: room for the body, solid floor beneath. */
	private boolean isStandable(BlockPos c) {
		try {
			BlockPos below = c.below();
			BlockState floor = mc.level.getBlockState(below);
			boolean hasFloor = !floor.getCollisionShape(mc.level, below).isEmpty()
					|| mc.level.getFluidState(below).is(net.minecraft.tags.FluidTags.WATER);
			if (!hasFloor) {
				return false;
			}
			BlockState feet = mc.level.getBlockState(c);
			BlockState head = mc.level.getBlockState(c.above());
			boolean feetFree = feet.getCollisionShape(mc.level, c).isEmpty();
			boolean headFree = head.getCollisionShape(mc.level, c.above()).isEmpty();
			if (!feetFree || !headFree) {
				return false;
			}
			// do not path into lava or fire
			if (mc.level.getFluidState(c).is(net.minecraft.tags.FluidTags.LAVA)) {
				return false;
			}
			return !feet.is(net.minecraft.world.level.block.Blocks.FIRE)
					&& !feet.is(net.minecraft.world.level.block.Blocks.SOUL_FIRE)
					&& !feet.is(net.minecraft.world.level.block.Blocks.CACTUS);
		} catch (Throwable t) {
			return false;
		}
	}

	private void tickWalk() {
		LocalPlayer p = mc.player;
		ticksLeft--;
		if (ticksLeft <= 0) {
			finish("{\"status\":\"error\",\"message\":\"走路超时，当前 " + fmt(p.position()) + "，目标 " + fmt(walkTarget) + "\"}");
			return;
		}
		// Water first: the walking logic below assumes solid ground and would just sink.
		if (Swimmer.inDeepWater(p)) {
			tickSwim();
			return;
		}
		if (walkPath == null && pathFuture != null && pathFuture.isDone()) {
			AStar.Result r = pathFuture.join();
			pathFuture = null;
			if (r != null && !r.path.isEmpty()) {
				applyPath(r);
			}
		}
		if (p.tickCount % 20 == 0) {
			if (p.position().distanceToSqr(lastPos) < 0.06) {
				stuckChecks++;
			} else {
				stuckChecks = 0;
			}
			lastPos = p.position();
		}
		if (stuckChecks >= 4) {
			replans++;
			stuckChecks = 0;
			releaseKeys();
			// Re-plan instead of giving up. The old code abandoned the walk after three
			// attempts, which is why a bot that clipped a tree or a fence post reported
			// failure on a route that was perfectly walkable — it just needed another go
			// from where it actually stood. Give up only after many tries, and say so.
			if (replans <= 12) {
				walkPath = null;
				pathFuture = null;
				planPath();
				return;
			}
			finish("{\"status\":\"partial\",\"message\":\"重规划 " + replans + " 次仍无法前进，当前位置 " + fmt(p.position())
					+ "，目标 " + fmt(walkTarget) + "。可能需要 dig 开路或 place 搭桥\"}");
			return;
		}
		if (walkPath != null) {
			// Re-plan periodically while following, so a path that went stale (a door closed,
			// a block placed, the bot knocked off course) is replaced instead of pushed into.
			if (p.tickCount % 60 == 0 && replans < 12) {
				BlockPos here = p.blockPosition();
				BlockPos nextWp = walkIdx < walkPath.size() ? walkPath.get(walkIdx) : null;
				if (nextWp != null && here.distSqr(nextWp) > 100) {
					replans++;
					walkPath = null;
					pathFuture = null;
					planPath();
					return;
				}
			}
			followPath();
			return;
		}
		greedyWalk();
	}

	/**
	 * Crosses water: swim forward and upward at the same time, climb out on the far side.
	 *
	 * <p>Water is travelled <em>through</em>, not treated as a wall to escape. Holding jump
	 * and forward together keeps the swimmer porpoising along the surface — rising enough to
	 * breathe while still making headway — which is how a player crosses a river, and how
	 * Baritone does it. The bot used to stop and tread water to breathe, which stalled every
	 * crossing until the walk timed out.
	 *
	 * <ol>
	 *   <li>Swim at the surface toward the far bank (forward + jump, always both).</li>
	 *   <li>On reaching the bank, climb onto it.</li>
	 *   <li>Out of the water, drop the stale path and let normal walking resume.</li>
	 * </ol>
	 */
	private void tickSwim() {
		LocalPlayer p = mc.player;
		swimTicks++;

		// Pick where to come out, and stick with it: re-choosing every tick made the bot
		// change its mind mid-swim and cross back to the bank it had just left.
		if (waterExit == null || !Swimmer.isDryLand(mc, waterExit.getX() + 0.5, waterExit.getY(), waterExit.getZ() + 0.5)) {
			waterExit = Swimmer.findExit(mc, p, walkTarget.x, walkTarget.z, 12);
		}
		BlockPos exit = waterExit;

		if (exit != null) {
			double tx = exit.getX() + 0.5;
			double tz = exit.getZ() + 0.5;
			double dist = Math.hypot(tx - p.getX(), tz - p.getZ());

			// at the bank: get up onto it
			if (dist < 2.2) {
				if (Swimmer.climbTo(p, mc, exit)) {
					// out of the water — the path was planned from inside the water, so drop
					// it and let the walker plan afresh from dry land
					walkPath = null;
					pathFuture = null;
					replans = 0;
					stuckChecks = 0;
					waterExit = null;
					chat("§a已上岸，继续前往目标");
				}
				return;
			}
			// otherwise keep swimming for it, rising and advancing together
			Swimmer.swimToward(p, new Vec3(tx, p.getY(), tz), mc);
		} else {
			// no bank within reach: head for the destination and keep looking
			Swimmer.swimToward(p, new Vec3(walkTarget.x, p.getY(), walkTarget.z), mc);
			if (swimTicks % 60 == 0) {
				chat("§e附近没找到岸，往目标方向游");
			}
		}

		// watchdog: if the bot is not closing on the bank, give up on this exit and re-pick
		if (p.tickCount % 20 == 0) {
			if (p.position().distanceToSqr(lastPos) < 0.05) {
				stuckChecks++;
			} else {
				stuckChecks = 0;
			}
			lastPos = p.position();
		}
		if (stuckChecks >= 5) {
			stuckChecks = 0;
			waterExit = null;
		}
	}

	/**
	 * Drives the independent navigator.
	 *
	 * <p>All the real work happens inside {@link PathExecutor}, which re-anchors the route and
	 * re-plans as it goes. This method only translates its verdict into a tool result —
	 * keeping the runner dumb is what lets the navigator be tested and reasoned about on its
	 * own, without a task state machine wrapped around it.
	 */
	private void tickLocalWalk() {
		ticksLeft--;
		com.mcagent.client.pathing.PathExecutor.Status st = pathExec.tick();
		// One line a second on the chat HUD: without it a stalled route is
		// indistinguishable from a slow search when watching the client.
		if (ConfigManager.get().pathDebug && mc.player != null && mc.player.tickCount % 20 == 0) {
			chat("§7[path] " + pathExec.describe());
		}
		switch (st) {
			case ARRIVED -> finish("{\"status\":\"success\",\"message\":\"已到达 " + fmt(mc.player.position()) + "\"}");
			case FAILED -> finish("{\"status\":\"partial\",\"message\":\"" + esc(pathExec.failure())
					+ "\"}");
			case RUNNING -> {
				if (ticksLeft <= 0) {
					pathExec.stop();
					finish("{\"status\":\"partial\",\"message\":\"走路超时，当前 " + fmt(mc.player.position()) + "\"}");
				}
			}
		}
	}

	/**
	 * Rides along while Baritone navigates.
	 *
	 * <p>Baritone drives the player itself, so this must not touch the movement keys — doing
	 * so fights it. The job here is only supervision: notice arrival, notice that Baritone
	 * gave up (it goes idle when the goal is unreachable), and notice a stall, then re-issue
	 * the goal once before reporting partial. Re-issuing on a timer is deliberately avoided;
	 * it restarts the search every few seconds and the bot never gets anywhere.
	 */
	private void tickBaritone() {
		LocalPlayer p = mc.player;
		ticksLeft--;

		double dx = baritoneTarget.x - p.getX();
		double dz = baritoneTarget.z - p.getZ();
		double rem = Math.sqrt(dx * dx + dz * dz);

		if (rem < 2.5) {
			BaritoneBridge.execute("stop");
			finish("{\"status\":\"success\",\"message\":\"已到达 " + fmt(p.position()) + "\"}");
			return;
		}

		if (p.tickCount % 20 == 0) {
			if (p.position().distanceToSqr(lastPos) < 0.09) {
				baritoneStall++;
			} else {
				baritoneStall = 0;
			}
			lastPos = p.position();
		}

		// gave up: Baritone is idle but we are not there. One retry, then report honestly.
		if (baritoneStall >= 6) {
			baritoneStall = 0;
			if (baritoneRetries < 1) {
				baritoneRetries++;
				BaritoneBridge.gotoPos(baritoneTarget.x, baritoneTarget.y, baritoneTarget.z);
				chat("§eBaritone 停住了，重试一次");
				return;
			}
			BaritoneBridge.execute("stop");
			finish("{\"status\":\"partial\",\"message\":\"Baritone 在 " + fmt(p.position()) + " 停下，距目标 "
					+ String.format("%.0f", rem) + " 格；该处可能需要挖通、搭桥或目标不可达\"}");
			return;
		}

		if (ticksLeft <= 0) {
			BaritoneBridge.execute("stop");
			finish("{\"status\":\"partial\",\"message\":\"Baritone 超时，当前 " + fmt(p.position())
					+ "，距目标 " + String.format("%.0f", rem) + " 格\"}");
		}
	}

	private void followPath() {		LocalPlayer p = mc.player;
		if (walkIdx >= walkPath.size()) {
			double remX = walkTarget.x - p.getX();
			double remZ = walkTarget.z - p.getZ();
			double rem = Math.sqrt(remX * remX + remZ * remZ);
			double dy = Math.abs(walkTarget.y - p.getY());
			// 2.5 blocks counts as arrived: the last stretch is often a slope, a ledge or a
			// one-block step, and refusing to call that "arrived" made the bot report failure
			// on walks that had in fact reached the destination.
			if (!pathPartial && rem < 2.5) {
				// Horizontal arrival is what "walk there" means. Report the real Y so the
				// caller can correct its idea of the terrain.
				if (!exactHeight || dy < 1.5) {
					finish("{\"status\":\"success\",\"message\":\"已到达 " + fmt(p.position()) + "\"}");
				} else {
					finish("{\"status\":\"success\",\"message\":\"已到达目标水平位置 " + fmt(p.position())
							+ "（请求的高度 y=" + String.format("%.0f", walkTarget.y)
							+ "，实际站立高度 y=" + String.format("%.0f", p.getY())
							+ "；该高度不可站立，已就近落脚）\"}");
				}
			} else if (pathPartial) {
				finish("{\"status\":\"partial\",\"message\":\"A*已走到可达边界 " + fmt(p.position()) + "，距目标还剩 " + String.format("%.0f", rem) + " 格（需要跨越水域/悬崖或墙体）。下一步：dig 挖开挡路方块、place 搭桥，或把 walk_to 拆成几个中间点\"}");
			} else {
				finish("{\"status\":\"error\",\"message\":\"已到路径终点但偏离目标 " + String.format("%.0f", rem) + " 格\"}");
			}
			walkPath = null;
			return;
		}
		BlockPos wp = walkPath.get(walkIdx);
		double dx = wp.getX() + 0.5 - p.getX();
		double dz = wp.getZ() + 0.5 - p.getZ();
		double horiz = Math.sqrt(dx * dx + dz * dz);
		double dy = wp.getY() - p.getY();

		// NOTE: the search's toBreak/toPlace lists are deliberately NOT executed here yet.
		//
		// Wiring them straight into the walker looked right and behaved badly: the ported
		// movements record placements that Mineflayer's builder would carry out, not all of
		// which are needed to walk a route, so the bot spent every tick aiming at a block it
		// did not need to place, yielded the movement each time, and span on the spot.
		// Consuming the plan needs a "is this edit actually required" filter first; until
		// that exists, the physical fallback below is the safer driver.
		if (false) {
			executePlan(walkIdx);
		}

		// Advance on horizontal arrival. Requiring the waypoint's height too (the old
		// |dy| < 1.6 check) deadlocks whenever the terrain cannot offer that height: the bot
		// reaches the point, refuses to advance, and shuffles on the spot — the bug where the
		// character ran back and forth forever. Height is now handled by the jump logic.
		//
		// The radius is 0.9 rather than a tight 0.45 because the bot moves at walking speed
		// and overshoots: a tighter gate leaves it circling a waypoint it keeps missing.
		if (horiz < 0.9) {
			walkIdx++;
			pathDigPos = null;
			return;
		}
		// Fallback only: the planned edits did not apply (something moved the world since
		// the search), so dig whatever is physically in the way.
		BlockPos blocker = findPathBlocker(p, wp);
		if (blocker != null && p.getEyePosition().distanceTo(Vec3.atCenterOf(blocker)) <= 4.5) {
			digPathBlock(blocker);
			return;
		}
		if (horiz > 0.01) {
			float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
			p.absSnapRotationTo(Mth.wrapDegrees(yaw), 0.0f);
		}
		mc.options.keyUp.setDown(true);
		mc.options.keyDown.setDown(false);
		mc.options.keyLeft.setDown(false);
		mc.options.keyRight.setDown(false);
		BlockPos next = walkIdx + 1 < walkPath.size() ? walkPath.get(walkIdx + 1) : null;
		boolean sprint = horiz > 3.5 || (next != null && next.getY() == wp.getY() && p.distanceToSqr(Vec3.atCenterOf(next)) > 16);
		mc.options.keySprint.setDown(sprint);
		boolean jump = dy >= 0.5 && horiz < 1.4 && p.onGround();
		// Jumping on collision is suppressed in deep water (it would fight the swim logic),
		// but that also stopped the bot climbing out of a shallow puddle: it pressed against
		// the one-block lip forever, drifting back and forth. Shallow water is walkable, so a
		// step up out of it is a normal jump.
		boolean deepWater = p.getFluidHeight(net.minecraft.tags.FluidTags.WATER) > 1.0;
		boolean climbable = p.horizontalCollision && p.onGround() && !deepWater;
		mc.options.keyJump.setDown(jump || climbable);
	}

	/**
	 * Performs the edits the search planned for one step, and reports whether it acted.
	 *
	 * <p>Break first, then place, then let the caller walk. Doing one edit per tick keeps the
	 * client responsive and lets the world catch up, and returning true stops the walking
	 * code from pushing into a block that is still mid-break — the "starts walking before it
	 * finishes digging, then jams" failure.
	 *
	 * @return true when an edit was started and movement should wait this tick
	 */
	private boolean executePlan(int idx) {
		if (walkMoves == null || idx < 0 || idx >= walkMoves.size()) {
			return false;
		}
		com.mcagent.client.pathing.Move step = walkMoves.get(idx);
		LocalPlayer p = mc.player;

		// 1. everything the plan wants gone
		for (BlockPos b : step.toBreak) {
			if (mc.level.getBlockState(b).isAir()) {
				continue;
			}
			if (blockDiggable(b) == null) {
				continue; // not ours to break (or already handled)
			}
			if (p.getEyePosition().distanceTo(Vec3.atCenterOf(b)) > 4.5) {
				continue; // walk closer first
			}
			digPathBlock(b);
			return true;
		}

		// 2. everything the plan wants built (bridge decks, tower blocks, steps)
		for (BlockPos pl : step.toPlace) {
			BlockState cur = mc.level.getBlockState(pl);
			if (!cur.canBeReplaced()) {
				continue; // already there
			}
			if (p.getEyePosition().distanceTo(Vec3.atCenterOf(pl)) > 4.5) {
				continue;
			}
			if (placeOnce(pl)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Places one block at a position, choosing a supporting face. Returns true if a placement
	 * was attempted, so the caller can yield the tick.
	 */
	private boolean placeOnce(BlockPos target) {
		LocalPlayer p = mc.player;
		if (p.getMainHandItem().isEmpty()) {
			return false; // nothing to build with; the walker will report instead
		}
		Vec3 eye = p.getEyePosition();
		double best = Double.MAX_VALUE;
		Direction bestFace = null;
		BlockPos bestRef = null;
		Vec3 bestHit = null;
		for (Direction d : Direction.values()) {
			BlockPos ref = target.relative(d);
			if (mc.level.getBlockState(ref).getCollisionShape(mc.level, ref).isEmpty()) {
				continue;
			}
			Vec3 hit = Vec3.atCenterOf(ref).add(d.getStepX() * 0.5, d.getStepY() * 0.5, d.getStepZ() * 0.5);
			double dist = eye.distanceTo(hit);
			if (dist > 4.5) {
				continue;
			}
			if (dist < best) {
				best = dist;
				bestFace = d;
				bestRef = ref;
				bestHit = hit;
			}
		}
		if (bestFace == null) {
			return false;
		}
		Humanizer.lookAt(p, bestHit);
		if (!Humanizer.isAimedAt(p, bestHit, 8.0)) {
			return true; // still turning; come back next tick
		}
		p.setShiftKeyDown(true);
		mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
				new BlockHitResult(bestHit, bestFace.getOpposite(), bestRef, false));
		p.swing(InteractionHand.MAIN_HAND);
		p.setShiftKeyDown(false);
		return true;
	}

	private BlockPos findPathBlocker(LocalPlayer p, BlockPos wp) {		boolean up = wp.getY() > p.blockPosition().getY();
		BlockPos head = wp.above();
		BlockPos feet = wp;
		BlockPos ceiling = p.blockPosition().above(2);
		if (!up) {
			BlockPos c = blockDiggable(head);
			if (c != null) return c;
			c = blockDiggable(feet);
			if (c != null) return c;
			c = blockDiggable(ceiling);
			return c;
		}
		BlockPos c = blockDiggable(ceiling);
		if (c != null) return c;
		c = blockDiggable(head);
		if (c != null) return c;
		return blockDiggable(feet);
	}

	private BlockPos blockDiggable(BlockPos pos) {
		BlockState st = mc.level.getBlockState(pos);
		if (st.isAir() || st.getCollisionShape(mc.level, pos).isEmpty()) {
			return null;
		}
		if (st.getBlock() instanceof net.minecraft.world.level.block.BaseEntityBlock) {
			return null;
		}
		float speed = st.getDestroySpeed(mc.level, pos);
		if (speed < 0 || speed > 20) {
			return null;
		}
		for (int[] d : new int[][]{{0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}}) {
			if (!mc.level.getFluidState(pos.offset(d[0], d[1], d[2])).isEmpty()) {
				return null;
			}
		}
		return pos;
	}

	private void digPathBlock(BlockPos pos) {
		LocalPlayer p = mc.player;
		releaseMoveKeys();
		if (pathDigPos == null || !pathDigPos.equals(pos)) {
			pathDigPos = pos;
			pathDigStarted = false;
		}
		Vec3 center = Vec3.atCenterOf(pos);
		if (p.getEyePosition().distanceTo(center) > 5.0) {
			finish("{\"status\":\"error\",\"message\":\"寻路挖掘时距离过远\"}");
			return;
		}
		face(center);
		BlockState st = mc.level.getBlockState(pos);
		if (st.isAir()) {
			pathDigPos = null;
			return;
		}
		if (!pathDigStarted) {
			autoTool(st);
			pathDigStarted = true;
		}
		Direction face = Direction.getNearest((int) Math.round((p.getEyePosition().x - center.x) * 2), (int) Math.round((p.getEyePosition().y - center.y) * 2), (int) Math.round((p.getEyePosition().z - center.z) * 2), Direction.UP);
		if (mc.gameMode.continueDestroyBlock(pos, face) || mc.gameMode.isDestroying()) {
			p.swing(InteractionHand.MAIN_HAND);
		} else {
			mc.gameMode.startDestroyBlock(pos, face);
		}
		p.swing(InteractionHand.MAIN_HAND);
		if (mc.level.getBlockState(pos).isAir()) {
			pathDigPos = null;
		}
	}

	private void greedyWalk() {
		LocalPlayer p = mc.player;
		double dx = walkTarget.x - p.getX();
		double dz = walkTarget.z - p.getZ();
		double dy = walkTarget.y - p.getY();
		double horiz = Math.sqrt(dx * dx + dz * dz);
		// Arrival is horizontal, for the same reason as in followPath: a wrong Y must not
		// keep the bot pressed against a waypoint it can never satisfy, which showed up as
		// the character shuffling back and forth on the spot.
		if (horiz < 0.7) {
			finish("{\"status\":\"success\",\"message\":\"已到达 " + fmt(p.position())
					+ "（请求高度 y=" + String.format("%.0f", walkTarget.y)
					+ "，实际 y=" + String.format("%.0f", p.getY()) + "）\"}");
			return;
		}
		if (horiz > 0.01) {
			float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
			p.absSnapRotationTo(Mth.wrapDegrees(yaw), 0.0f);
		}
		mc.options.keyUp.setDown(true);
		mc.options.keyDown.setDown(false);
		mc.options.keyLeft.setDown(false);
		mc.options.keyRight.setDown(false);
		mc.options.keySprint.setDown(horiz > 4);
		boolean jump = p.onGround() && (p.horizontalCollision || Math.abs(dy) >= 0.5 && Math.abs(dy) <= 1.2 && horiz < 1.5);
		if (!jump) {
			double nx = p.getX() + dx / Math.max(horiz, 0.01) * 0.9;
			double nz = p.getZ() + dz / Math.max(horiz, 0.01) * 0.9;
			BlockPos ahead = BlockPos.containing(nx, p.getY() + 0.4, nz);
			BlockPos above = ahead.above();
			if (!mc.level.getBlockState(ahead).getCollisionShape(mc.level, ahead).isEmpty()
					&& mc.level.getBlockState(above).getCollisionShape(mc.level, above).isEmpty()) {
				jump = true;
			}
		}
		mc.options.keyJump.setDown(jump);
		// Last-resort watchdog: if the bot makes no horizontal progress while greedy-walking,
		// it is pressed against something it cannot solve (a wall it will not dig, a gap it
		// will not bridge). Stop and report, rather than shuffling until the walk times out.
		if (p.tickCount % 20 == 0) {
			if (p.position().distanceToSqr(lastPos) < 0.04) {
				stuckChecks++;
			} else {
				stuckChecks = 0;
			}
			lastPos = p.position();
		}
		if (stuckChecks >= 6) {
			releaseKeys();
			finish("{\"status\":\"partial\",\"message\":\"无法直线走到 " + fmt(walkTarget) + "，当前 " + fmt(p.position())
					+ "。中间可能有墙/水/悬崖：可用 dig 开路、place 搭桥，或分段 walk_to\"}");
		}
	}

	private void tickDig() {
		LocalPlayer p = mc.player;
		ticksLeft--;
		BlockState state = mc.level.getBlockState(digPos);
		if (state.isAir()) {
			finish("{\"status\":\"success\",\"message\":\"方块已挖掉: " + digPos.toShortString() + "\"}");
			return;
		}
		Vec3 center = Vec3.atCenterOf(digPos);
		double dist = p.getEyePosition().distanceTo(center);
		if (dist > 5.2) {
			finish("{\"status\":\"error\",\"message\":\"距离方块 " + String.format("%.1f", dist) + " 格超出触及范围(5格)，先 walk_to 靠近\"}");
			return;
		}
		if (ticksLeft <= 0) {
			finish("{\"status\":\"error\",\"message\":\"挖掘超时(25秒)，方块硬度可能太高，考虑换工具或换方法\"}");
			return;
		}
		face(center);
		// Simulated reaction time, then wait for the head to actually settle on the block.
		// A real player cannot start mining before their crosshair is on the target.
		if (!Humanizer.tickActionDelay() || !aimedAt(center)) {
			return;
		}
		if (!digStarted) {
			autoTool(state);
			digStarted = true;
		}
		Direction face = Direction.getNearest((int) Math.round((p.getEyePosition().x - center.x) * 2), (int) Math.round((p.getEyePosition().y - center.y) * 2), (int) Math.round((p.getEyePosition().z - center.z) * 2), Direction.UP);
		if (mc.gameMode.continueDestroyBlock(digPos, face) || mc.gameMode.isDestroying()) {
			p.swing(InteractionHand.MAIN_HAND);
		} else {
			mc.gameMode.startDestroyBlock(digPos, face);
		}
		p.swing(InteractionHand.MAIN_HAND);
	}

	private void tickPlace() {
		LocalPlayer p = mc.player;
		ticksLeft--;
		BlockState cur = mc.level.getBlockState(placePos);
		if (!cur.canBeReplaced()) {
			finish("{\"status\":\"success\",\"message\":\"目标位置已有方块 " + blockName(cur) + "\"}");
			return;
		}
		if (ticksLeft <= 0) {
			finish("{\"status\":\"error\",\"message\":\"放置失败：无支撑面/过远/手中物品不合适\"}");
			return;
		}
		if (!placeItem.isEmpty()) {
			ItemStack held = p.getMainHandItem();
			if (held.isEmpty() || !matchesItem(held, placeItem)) {
				if (!selectItem(placeItem).startsWith("{")) {
					finish("{\"status\":\"error\",\"message\":\"放置失败: \"}");
					return;
				}
				held = p.getMainHandItem();
				if (held.isEmpty() || !matchesItem(held, placeItem)) {
					finish("{\"status\":\"error\",\"message\":\"放置失败: 背包没有 " + placeItem + " 或无法放到快捷栏\"}");
					return;
				}
			}
		} else if (p.getMainHandItem().isEmpty()) {
			finish("{\"status\":\"error\",\"message\":\"放置失败: 手里没有物品，请用 select 或 place 的 item 参数\"}");
			return;
		}
		Vec3 eye = p.getEyePosition();
		// Pick one supporting face and commit to it, the way a player does, instead of
		// spraying right-clicks at all six faces in a single tick.
		if (placeFace == null || placeHit == null) {
			double best = Double.MAX_VALUE;
			for (Direction d : Direction.values()) {
				BlockPos neighbor = placePos.relative(d);
				BlockState ns = mc.level.getBlockState(neighbor);
				if (ns.getCollisionShape(mc.level, neighbor).isEmpty()) {
					continue;
				}
				Vec3 hv = Vec3.atCenterOf(neighbor).add(d.getStepX() * 0.5, d.getStepY() * 0.5, d.getStepZ() * 0.5);
				double dd = eye.distanceTo(hv);
				if (dd > 5.0) {
					continue;
				}
				if (dd < best) {
					best = dd;
					placeFace = d;
					placeHit = hv;
					placeNeighbor = neighbor;
				}
			}
			if (placeFace == null) {
				if (ticksLeft <= 2) {
					finish("{\"status\":\"error\",\"message\":\"放置失败: 目标 " + placePos.toShortString() + " 周围找不到有效支撑面\"}");
				}
				return;
			}
		}
		face(placeHit);
		// wait for the aim to settle before the click lands
		if (!Humanizer.tickActionDelay() || !aimedAt(placeHit)) {
			return;
		}
		p.setShiftKeyDown(true);
		BlockHitResult hit = new BlockHitResult(placeHit, placeFace.getOpposite(), placeNeighbor, false);
		mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
		p.swing(InteractionHand.MAIN_HAND);
		p.setShiftKeyDown(false);
		if (!mc.level.getBlockState(placePos).canBeReplaced()) {
			finish("{\"status\":\"success\",\"message\":\"已放置 " + blockName(mc.level.getBlockState(placePos)) + " 到 " + placePos.toShortString() + "\"}");
			return;
		}
		// the face did not take (block in the way / out of reach): forget it and re-pick
		placeFace = null;
		placeHit = null;
		placeNeighbor = null;
		if (ticksLeft <= 2) {
			finish("{\"status\":\"error\",\"message\":\"放置失败: 目标 " + placePos.toShortString() + " 周围找不到有效支撑面\"}");
		}
	}

	private void tickAttack() {
		LocalPlayer p = mc.player;
		ticksLeft--;
		if (attackCd > 0) attackCd--;
		if (attackTarget == null || !attackTarget.isAlive() || attackTarget.isRemoved()) {
			finish("{\"status\":\"success\",\"message\":\"目标已死亡/消失\"}");
			return;
		}
		if (ticksLeft <= 0) {
			finish("{\"status\":\"error\",\"message\":\"战斗超时，目标可能很强，建议先提升装备\"}");
			return;
		}
		double dist = p.distanceTo(attackTarget);
		if (dist > 3.0) {
			double dx = attackTarget.getX() - p.getX();
			double dz = attackTarget.getZ() - p.getZ();
			float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
			p.absSnapRotationTo(yaw, 0.0f);
			mc.options.keyUp.setDown(true);
			mc.options.keyJump.setDown(p.onGround() && p.horizontalCollision);
			mc.options.keySprint.setDown(dist > 6);
			return;
		}
		releaseMoveKeys();
		face(attackTarget.position().add(0, attackTarget.getBbHeight() * 0.8, 0));
		if (attackCd <= 0) {
			mc.gameMode.attack(p, attackTarget);
			p.swing(InteractionHand.MAIN_HAND);
			attackCd = 12;
		}
	}

	private void beginCraft(JsonObject args) {
		JsonArray gridArr = args.has("grid") && args.get("grid").isJsonArray() ? args.getAsJsonArray("grid") : null;
		if (gridArr == null || gridArr.isEmpty()) {
			finish("error", "{\"status\":\"error\",\"message\":\"craft 缺少 grid 参数\"}");
			return;
		}
		craftGrid = new String[9];
		for (int i = 0; i < 9; i++) {
			if (i < gridArr.size() && gridArr.get(i).isJsonPrimitive() && !gridArr.get(i).getAsString().equalsIgnoreCase("null")) {
				String s = gridArr.get(i).getAsString().trim();
				craftGrid[i] = s.isEmpty() ? null : s;
			} else {
				craftGrid[i] = null;
			}
		}
		craftOutput = str(args, "output", "");
		craftBeforeCount = craftOutput.isEmpty() ? -1 : countItem(craftOutput);
		craftUseTable = false;
		for (int i = 4; i < 9; i++) {
			if (craftGrid[i] != null) {
				craftUseTable = true;
				break;
			}
		}
		if (craftUseTable) {
			craftTablePos = findNearbyBlock(pos -> mc.level.getBlockState(pos).getBlock() instanceof CraftingTableBlock, 4);
			if (craftTablePos == null) {
				BlockPos far = findNearbyBlock(pos -> mc.level.getBlockState(pos).getBlock() instanceof CraftingTableBlock, 32);
				if (far == null) {
					finish("error", "{\"status\":\"error\",\"message\":\"32格内没有工作台，无法3x3合成。可先 place 一个工作台(需要4块木板合成工作台: grid=[木板,木板,木板,木板])再合成，或改用2x2配方\"}");
					return;
				}
				craftTablePos = far;
				craftPhase = 10;
				subWalkTarget = Vec3.atCenterOf(far);
				AStar.Result pr = AStar.search(mc.level, mc.player.blockPosition(),
						new Goal.Block(far), 60000, 64);
				subWalkPath = new ArrayList<>();
				for (com.mcagent.client.pathing.Move m : pr.path) {
					subWalkPath.add(m.pos());
				}
				subWalkIdx = 0;
				ticksLeft = 20 * 90;
				task = Task.CRAFT;
				return;
			}
			craftPhase = 0;
		} else {
			craftPhase = 2;
			mc.setScreen(new InventoryScreen(mc.player));
		}
		craftResultClicks = 0;
		craftIdle = 0;
		ticksLeft = 20 * 60;
		task = Task.CRAFT;
	}

	private void tickCraft() {
		LocalPlayer p = mc.player;
		ticksLeft--;
		if (ticksLeft <= 0) {
			if (craftUseTable && p.containerMenu != p.inventoryMenu) p.closeContainer();
			if (mc.screen != null) mc.setScreen(null);
			finish("{\"status\":\"error\",\"message\":\"合成超时\"}");
			return;
		}
		switch (craftPhase) {
			case 10 -> {
				if (!followSubWalk()) {
					return;
				}
				craftPhase = 0;
			}
			case 0 -> {
				Vec3 center = Vec3.atCenterOf(craftTablePos);
				if (p.getEyePosition().distanceTo(center) > 4.8) {
					finish("{\"status\":\"error\",\"message\":\"工作台太远\"}");
					return;
				}
				face(center);
				BlockHitResult hit = new BlockHitResult(center, Direction.UP, craftTablePos, false);
				mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
				p.swing(InteractionHand.MAIN_HAND);
				craftPhase = 1;
				craftIdle = 0;
			}
			case 1 -> {
				craftIdle++;
				if (p.containerMenu != p.inventoryMenu && p.containerMenu instanceof CraftingMenu) {
					craftPhase = 2;
				} else if (craftIdle > 30) {
					finish("{\"status\":\"error\",\"message\":\"无法打开工作台界面\"}");
				}
			}
			case 2 -> fillGrid();
			case 3 -> clickResult();
			case 4 -> {
				craftIdle++;
				AbstractContainerMenu menu = p.containerMenu;
				Slot resultSlot = menu.slots.get(0);
				if (!resultSlot.hasItem()) {
					int after = craftOutput.isEmpty() ? -1 : countItem(craftOutput);
					if (mc.screen != null) {
						p.closeContainer();
						mc.setScreen(null);
					}
					String msg = "合成完成";
					if (after >= 0) {
						msg = "合成完成，背包中 " + craftOutput + " 共 " + after + " 个";
					}
					finish("{\"status\":\"success\",\"message\":\"" + msg + "\"}");
				} else if (craftResultClicks >= 16 || craftIdle > 80) {
					if (mc.screen != null) {
						p.closeContainer();
						mc.setScreen(null);
					}
					finish("{\"status\":\"error\",\"message\":\"合成进行中但停止了，可能背包已满\"}");
				} else if (craftIdle % 8 == 0) {
					craftPhase = 3;
				}
			}
		}
	}

	private boolean followSubWalk() {
		LocalPlayer p = mc.player;
		double dx = subWalkTarget.x - p.getX();
		double dz = subWalkTarget.z - p.getZ();
		double horiz = Math.sqrt(dx * dx + dz * dz);
		if (horiz < 3.2) {
			releaseMoveKeys();
			return true;
		}
		if (subWalkPath != null && subWalkIdx < subWalkPath.size()) {
			BlockPos wp = subWalkPath.get(subWalkIdx);
			double wx = wp.getX() + 0.5 - p.getX();
			double wz = wp.getZ() + 0.5 - p.getZ();
			double wh = Math.sqrt(wx * wx + wz * wz);
			double wy = wp.getY() - p.getY();
			if (wh < 0.45 && Math.abs(wy) < 1.6) {
				subWalkIdx++;
			} else {
				if (wh > 0.01) {
					float yaw = (float) (Math.toDegrees(Math.atan2(wz, wx)) - 90.0);
					p.absSnapRotationTo(Mth.wrapDegrees(yaw), 0.0f);
				}
				mc.options.keyUp.setDown(true);
				mc.options.keyJump.setDown(wy >= 0.5 && wh < 1.4 && p.onGround() || p.onGround() && p.horizontalCollision);
				mc.options.keySprint.setDown(wh > 3.5);
			}
		} else {
			if (horiz > 0.01) {
				float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
				p.absSnapRotationTo(Mth.wrapDegrees(yaw), 0.0f);
			}
			mc.options.keyUp.setDown(true);
			mc.options.keyJump.setDown(p.onGround() && p.horizontalCollision);
		}
		return false;
	}

	private void fillGrid() {
		LocalPlayer p = mc.player;
		AbstractContainerMenu menu = p.containerMenu;
		int gridSize = craftUseTable ? 9 : 4;
		for (int i = 0; i < gridSize; i++) {
			String want = craftGrid[i];
			Slot gridSlot = menu.slots.get(1 + i);
			if (want == null) {
				if (gridSlot.hasItem()) {
					clickSlot(menu, 1 + i, 0, ClickType.PICKUP);
					if (!p.containerMenu.getCarried().isEmpty()) {
						int back = findEmptyInvMenuSlot(menu);
						if (back >= 0) {
							clickSlot(menu, back, 0, ClickType.PICKUP);
						} else {
							clickSlot(menu, 1 + i, 0, ClickType.PICKUP);
						}
					}
				}
			} else {
				boolean ok = gridSlot.hasItem() && matchesItem(gridSlot.getItem(), want);
				if (!ok) {
					int invSlot = findMenuSlotWithItem(menu, want);
					if (invSlot < 0) {
						if (mc.screen != null) mc.setScreen(null);
						finish("{\"status\":\"error\",\"message\":\"背包缺少材料: " + want + "，先去获取材料\"}");
						return;
					}
					clickSlot(menu, invSlot, 0, ClickType.PICKUP);
					clickSlot(menu, 1 + i, 0, ClickType.PICKUP);
					if (!p.containerMenu.getCarried().isEmpty()) {
						clickSlot(menu, invSlot, 0, ClickType.PICKUP);
					}
				}
			}
		}
		craftPhase = 3;
	}

	private void clickResult() {
		AbstractContainerMenu menu = mc.player.containerMenu;
		if (menu.slots.get(0).hasItem()) {
			clickSlot(menu, 0, 0, ClickType.QUICK_MOVE);
			craftResultClicks++;
			craftIdle++;
			craftPhase = 4;
		} else {
			craftIdle++;
			if (craftIdle > 40) {
				if (mc.screen != null) mc.setScreen(null);
				finish("{\"status\":\"error\",\"message\":\"配方没有产出(摆放可能不匹配任何配方)，检查 grid 布局\"}");
			} else if (craftIdle % 10 == 0) {
				craftPhase = 3;
			}
		}
	}

	private void clickSlot(AbstractContainerMenu menu, int slot, int button, ClickType type) {
		mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, button, type, mc.player);
	}

	private int findMenuSlotWithItem(AbstractContainerMenu menu, String item) {
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot s = menu.slots.get(i);
			if (!s.hasItem() || s.container != mc.player.getInventory()) {
				continue;
			}
			if (matchesItem(s.getItem(), item)) {
				return i;
			}
		}
		return -1;
	}

	private int findEmptyInvMenuSlot(AbstractContainerMenu menu) {
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot s = menu.slots.get(i);
			if (s.container == mc.player.getInventory() && !s.hasItem()) {
				return i;
			}
		}
		return -1;
	}

	private interface BlockMatch {
		boolean matches(BlockPos pos);
	}

	private BlockPos findNearbyBlock(BlockMatch test, int radius) {
		BlockPos base = mc.player.blockPosition();
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		if (radius <= 5) {
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dy = -3; dy <= 3; dy++) {
					for (int dz = -radius; dz <= radius; dz++) {
						BlockPos pos = base.offset(dx, dy, dz);
						if (test.matches(pos)) {
							double d = mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(pos));
							if (d < bestD) {
								bestD = d;
								best = pos;
							}
						}
					}
				}
			}
			return best;
		}
		int scanned = 0;
		for (int r = 0; r <= radius && scanned < 300000; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
						continue;
					}
					for (int dy = -6; dy <= 6; dy++) {
						scanned++;
						BlockPos pos = base.offset(dx, dy, dz);
						if (test.matches(pos)) {
							double d = mc.player.distanceToSqr(Vec3.atCenterOf(pos));
							if (d < bestD) {
								bestD = d;
								best = pos;
							}
						}
					}
				}
			}
			if (best != null && r > 4) {
				break;
			}
		}
		return best;
	}

	private String selectItem(String item) {
		LocalPlayer p = mc.player;
		for (int i = 0; i < 9; i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (!st.isEmpty() && matchesItem(st, item)) {
				p.getInventory().setSelectedSlot(i);
				return "{\"status\":\"success\",\"message\":\"已选中快捷栏物品: " + itemName(st) + "\"}";
			}
		}
		InventoryMenu menu = p.inventoryMenu;
		int hotbarIdx = -1;
		for (int i = 0; i < 9; i++) {
			if (p.getInventory().getItem(i).isEmpty()) {
				hotbarIdx = i;
				break;
			}
		}
		if (hotbarIdx < 0) {
			hotbarIdx = p.getInventory().getSelectedSlot();
		}
		int invIndex = -1;
		for (int i = 9; i < 36; i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (!st.isEmpty() && matchesItem(st, item)) {
				invIndex = i;
				break;
			}
		}
		if (invIndex < 0) {
			return "{\"status\":\"error\",\"message\":\"背包中没有: " + item + "\"}";
		}
		int menuSlot = findInvMenuSlot(menu, invIndex);
		if (menuSlot < 0) {
			return "{\"status\":\"error\",\"message\":\"无法定位背包槽位\"}";
		}
		clickSlot(menu, menuSlot, hotbarIdx, ClickType.SWAP);
		p.getInventory().setSelectedSlot(hotbarIdx);
		return "{\"status\":\"success\",\"message\":\"已将 " + item + " 移到快捷栏并选中\"}";
	}

	private int findInvMenuSlot(InventoryMenu menu, int invIndex) {
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot s = menu.slots.get(i);
			if (s.container == mc.player.getInventory() && s.getContainerSlot() == invIndex) {
				return i;
			}
		}
		return -1;
	}

	private void autoTool(BlockState state) {
		if (!ConfigManager.get().autoTool || mc.player.getAbilities().instabuild) {
			return;
		}
		LocalPlayer p = mc.player;
		ItemStack current = p.getMainHandItem();
		if (!current.isEmpty() && current.isCorrectToolForDrops(state)) {
			return;
		}
		for (int i = 9; i < 36; i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (!st.isEmpty() && st.isCorrectToolForDrops(state)) {
				int menuSlot = findInvMenuSlot(p.inventoryMenu, i);
				if (menuSlot >= 0) {
					clickSlot(p.inventoryMenu, menuSlot, p.getInventory().getSelectedSlot(), ClickType.SWAP);
				}
				return;
			}
		}
		for (int i = 0; i < 9; i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (!st.isEmpty() && st.isCorrectToolForDrops(state)) {
				p.getInventory().setSelectedSlot(i);
				return;
			}
		}
	}

	private Entity findTarget(String kind, int radius) {
		Entity best = null;
		double bestD = Double.MAX_VALUE;
		List<Entity> list = new ArrayList<>();
		for (Entity e : mc.level.entitiesForRendering()) {
			list.add(e);
		}
		list.sort(Comparator.comparingDouble(a -> mc.player.distanceToSqr(a)));
		for (Entity e : list) {
			if (e == mc.player || !(e instanceof LivingEntity) || !e.isAlive()) {
				continue;
			}
			if ("animal".equals(kind) && (e instanceof Enemy || e instanceof Player)) {
				continue;
			}
			if ("monster".equals(kind) && !(e instanceof Enemy)) {
				continue;
			}
			if ("any".equals(kind) && e instanceof Player) {
				continue;
			}
			double d = mc.player.distanceTo(e);
			if (d < bestD && d <= radius) {
				bestD = d;
				best = e;
			}
		}
		return best;
	}

	private void face(Vec3 target) {
		LocalPlayer p = mc.player;
		Vec3 eye = p.getEyePosition();
		// Humanizer rate-limits the turn and adds a small aiming error, so the head sweeps
		// toward the target over several ticks instead of snapping there in one.
		Humanizer.lookAt(p, target);
	}

	/** True once the head has settled close enough to the aim point to act accurately. */
	private boolean aimedAt(Vec3 target) {
		return Humanizer.isAimedAt(mc.player, target, 6.0);
	}

	private void releaseMoveKeys() {
		mc.options.keyUp.setDown(false);
		mc.options.keyDown.setDown(false);
		mc.options.keyLeft.setDown(false);
		mc.options.keyRight.setDown(false);
		mc.options.keyJump.setDown(false);
		mc.options.keySprint.setDown(false);
	}

	private void releaseKeys() {
		if (mc.options != null) {
			releaseMoveKeys();
			mc.options.keyShift.setDown(false);
			mc.options.keyUse.setDown(false);
		}
	}

	public static synchronized boolean inBlacklist(String name) {
		if (name == null || name.isBlank()) {
			return false;
		}
		com.mcagent.config.BotConfig cfg = ConfigManager.get();
		return listHas(cfg.manualBlacklist, name) || listHas(cfg.aiBlacklist, name);
	}

	private static boolean listHas(String list, String name) {
		if (list == null || name == null) {
			return false;
		}
		String target = name.trim().replaceFirst("^[|｜]+", "");
		for (String s : list.split("[,，;；|]")) {
			String t = s.trim().replaceFirst("^[|｜]+", "");
			if (!t.isEmpty() && t.equalsIgnoreCase(target)) {
				return true;
			}
		}
		return false;
	}

	private static String listAppend(String list, String name) {
		return list == null || list.isBlank() ? name : list + "," + name;
	}

	private static String listRemove(String list, String name) {
		StringBuilder sb = new StringBuilder();
		for (String s : list.split("[,，;；|]")) {
			String t = s.trim();
			if (!t.isEmpty() && !t.equalsIgnoreCase(name.trim())) {
				if (sb.length() > 0) {
					sb.append(",");
				}
				sb.append(t);
			}
		}
		return sb.toString();
	}

	public static synchronized String blacklistOp(String op, String player) {
		com.mcagent.config.BotConfig cfg = ConfigManager.get();
		String o = op == null ? "list" : op.toLowerCase(java.util.Locale.ROOT);
		switch (o) {
			case "add" -> {
				if (player == null || player.isBlank()) {
					return "{\"status\":\"error\",\"message\":\"缺少 player 参数\"}";
				}
				if (listHas(cfg.manualBlacklist, player)) {
					return "{\"status\":\"error\",\"message\":\"" + esc(player) + " 在手动黑名单中（玩家亲自添加，AI无权操作）\"}";
				}
				if (listHas(cfg.aiBlacklist, player)) {
					return "{\"status\":\"success\",\"message\":\"" + esc(player) + " 已在AI黑名单中\"}";
				}
				cfg.aiBlacklist = listAppend(cfg.aiBlacklist, player.trim());
				ConfigManager.save();
				return "{\"status\":\"success\",\"message\":\"已将 " + esc(player) + " 加入AI黑名单，不再自动回复TA\"}";
			}
			case "remove" -> {
				if (player == null || player.isBlank()) {
					return "{\"status\":\"error\",\"message\":\"缺少 player 参数\"}";
				}
				if (listHas(cfg.manualBlacklist, player)) {
					return "{\"status\":\"error\",\"message\":\"" + esc(player) + " 在手动黑名单中（玩家亲自添加），AI无法移除\"}";
				}
				if (!listHas(cfg.aiBlacklist, player)) {
					return "{\"status\":\"error\",\"message\":\"" + esc(player) + " 不在AI黑名单中\"}";
				}
				cfg.aiBlacklist = listRemove(cfg.aiBlacklist, player.trim());
				ConfigManager.save();
				return "{\"status\":\"success\",\"message\":\"已将 " + esc(player) + " 移出AI黑名单\"}";
			}
			default -> {
				return "{\"status\":\"success\",\"message\":\"手动黑名单(不可被AI修改): [" + esc(cfg.manualBlacklist) + "] | AI黑名单: [" + esc(cfg.aiBlacklist) + "]\"}";
			}
		}
	}

	public static String webSearch(String query) {
		try {
			String url = "https://cn.bing.com/search?q=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8) + "&count=10";
			java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
					.timeout(java.time.Duration.ofSeconds(15))
					.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36")
					.header("Accept-Language", "zh-CN,zh;q=0.9")
					.GET().build();
			java.net.http.HttpResponse<String> resp = java.net.http.HttpClient.newHttpClient()
					.send(req, java.net.http.HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
			String html = resp.body();
			JsonArray results = new JsonArray();
			java.util.regex.Matcher li = java.util.regex.Pattern.compile("<li class=\"b_algo\"[^>]*>(.*?)</li>", java.util.regex.Pattern.DOTALL).matcher(html);
			int count = 0;
			while (li.find() && count < 5) {
				String block = li.group(1);
				String title = firstMatch(block, "<h2[^>]*>.*?<a[^>]*>(.*?)</a>");
				String snippet = firstMatch(block, "<p[^>]*>(.*?)</p>");
				title = cleanHtml(title);
				snippet = cleanHtml(snippet);
				if (title.isEmpty() || snippet.isEmpty()) {
					continue;
				}
				if (snippet.length() > 150) {
					snippet = snippet.substring(0, 150);
				}
				JsonObject r = new JsonObject();
				r.addProperty("title", title);
				r.addProperty("snippet", snippet);
				results.add(r);
				count++;
			}
			if (count == 0) {
				return "{\"status\":\"error\",\"message\":\"搜索没有返回结果，换个关键词试试\"}";
			}
			JsonObject o = new JsonObject();
			o.add("results", results);
			o.addProperty("status", "success");
			return o.toString();
		} catch (Exception e) {
			return "{\"status\":\"error\",\"message\":\"搜索失败: " + esc(String.valueOf(e.getMessage())) + "\"}";
		}
	}

	private static String firstMatch(String text, String regex) {
		java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.DOTALL).matcher(text);
		return m.find() ? m.group(1) : "";
	}

	private static String cleanHtml(String s) {
		if (s == null) {
			return "";
		}
		s = s.replaceAll("<[^>]+>", "");
		s = s.replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'").replace("&nbsp;", " ");
		return s.replaceAll("\\s+", " ").trim();
	}

	public static String statusSnapshot() {
		return INSTANCE.getStatus();
	}

	private String getEnv() {
		LocalPlayer p = mc.player;
		JsonObject o = new JsonObject();
		o.addProperty("dimension", mc.level.dimension().identifier().toString());
		o.addProperty("biome", mc.level.getBiome(p.blockPosition()).unwrapKey().map(k -> k.identifier().getPath()).orElse("unknown"));
		o.addProperty("weather", mc.level.isThundering() ? "thunder" : mc.level.isRaining() ? "rain" : "clear");
		o.addProperty("daytime", mc.level.isBrightOutside() ? "day" : "night");
		o.addProperty("gamemode", p.getAbilities().instabuild ? "creative" : "survival");
		o.addProperty("health", String.format("%.1f", p.getHealth()));
		o.addProperty("max_health", String.format("%.1f", p.getMaxHealth()));
		o.addProperty("food", p.getFoodData().getFoodLevel());
		o.addProperty("xp_level", p.experienceLevel);
		o.addProperty("position", p.getX() + "," + String.format("%.1f", p.getY()) + "," + String.format("%.1f", p.getZ()));
		o.addProperty("held", heldName());
		o.addProperty("baritone", BaritoneBridge.available() ? "installed" : "not_installed");
		return "{\"status\":\"success\",\"environment\":" + o + "}";
	}

	public String getStatus() {
		LocalPlayer p = mc.player;
		JsonObject o = new JsonObject();
		JsonObject pos = new JsonObject();
		pos.addProperty("x", String.format("%.1f", p.getX()));
		pos.addProperty("y", String.format("%.1f", p.getY()));
		pos.addProperty("z", String.format("%.1f", p.getZ()));
		o.add("position", pos);
		BlockPos bp = p.blockPosition();
		o.addProperty("block_pos", bp.toShortString());
		if (task == Task.LOCAL_WALK) {
			o.addProperty("pathing_state", pathExec.progress());
			o.addProperty("next_waypoint", pathExec.nextWaypoint());
			o.addProperty("remaining_waypoints", pathExec.pathLength());
		}
		o.addProperty("health", String.format("%.1f", p.getHealth()));
		o.addProperty("food", p.getFoodData().getFoodLevel());
		// Air matters as soon as the bot is in water: without it a caller cannot tell a
		// bot that is swimming from one that is about to drown.
		o.addProperty("air", p.getAirSupply());
		o.addProperty("max_air", p.getMaxAirSupply());
		o.addProperty("in_water", p.isInWater());
		o.addProperty("under_water", p.isUnderWater());
		o.addProperty("mode", p.getAbilities().instabuild ? "creative" : "survival");
		o.addProperty("time", mc.level.isBrightOutside() ? "day" : "night");
		ItemStack held = p.getMainHandItem();
		o.addProperty("held", held.isEmpty() ? "空" : itemName(held) + " x" + held.getCount());
		JsonArray hotbar = new JsonArray();
		for (int i = 0; i < 9; i++) {
			ItemStack st = p.getInventory().getItem(i);
			JsonObject s = new JsonObject();
			s.addProperty("slot", i + 1);
			s.addProperty("item", st.isEmpty() ? "空" : itemName(st) + " x" + st.getCount());
			hotbar.add(s);
		}
		o.add("hotbar", hotbar);
		Map<String, Integer> inv = new HashMap<>();
		for (int i = 9; i < p.getInventory().getContainerSize(); i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (!st.isEmpty()) {
				inv.merge(itemName(st), st.getCount(), Integer::sum);
			}
		}
		JsonArray bag = new JsonArray();
		for (Map.Entry<String, Integer> e : inv.entrySet()) {
			JsonObject s = new JsonObject();
			s.addProperty("item", e.getKey());
			s.addProperty("count", e.getValue());
			bag.add(s);
		}
		o.add("inventory", bag);
		JsonArray ents = new JsonArray();
		List<Entity> list = new ArrayList<>();
		for (Entity e : mc.level.entitiesForRendering()) {
			list.add(e);
		}
		list.sort(Comparator.comparingDouble(a -> p.distanceToSqr(a)));
		int n = 0;
		for (Entity e : list) {
			if (e == p || !(e instanceof LivingEntity)) {
				continue;
			}
			if (n++ >= 10) {
				break;
			}
			JsonObject eo = new JsonObject();
			eo.addProperty("name", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath());
			eo.addProperty("dist", String.format("%.1f", p.distanceTo(e)));
			eo.addProperty("is_monster", e instanceof Enemy);
			ents.add(eo);
		}
		o.add("nearby_entities", ents);
		Map<String, Integer> near = new HashMap<>();
		for (int dx = -7; dx <= 7; dx++) {
			for (int dz = -7; dz <= 7; dz++) {
				for (int dy = -2; dy <= 3; dy++) {
					BlockPos bpos = bp.offset(dx, dy, dz);
					BlockState st = mc.level.getBlockState(bpos);
					if (st.isAir()) {
						continue;
					}
					near.merge(blockName(st), 1, Integer::sum);
				}
			}
		}
		JsonArray nb = new JsonArray();
		near.entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
				.limit(14)
				.forEach(e -> {
					JsonObject s = new JsonObject();
					s.addProperty(e.getKey(), e.getValue());
					nb.add(s);
				});
		o.add("nearby_blocks", nb);
		if (mc.hitResult instanceof BlockHitResult bhr && bhr.getBlockPos() != null) {
			BlockState st = mc.level.getBlockState(bhr.getBlockPos());
			if (!st.isAir()) {
				o.addProperty("crosshair", blockName(st) + " @ " + bhr.getBlockPos().toShortString());
			}
		}
		o.addProperty("below", blockName(mc.level.getBlockState(bp.below())));
		o.addProperty("at_feet", blockName(mc.level.getBlockState(bp)));
		o.addProperty("baritone", BaritoneBridge.available() ? "installed(优先用baritone工具)" : "not_installed(只能用walk_to/dig)");
		return o.toString();
	}

	private String findBlock(String query, int radiusBlocks, int limit) {
		if (query.isEmpty()) {
			return "{\"status\":\"error\",\"message\":\"缺少 block 参数\"}";
		}
		int renderDist = mc.options.renderDistance().get();
		int chunkRadius = Math.max(2, Math.min(renderDist, Math.max(1, radiusBlocks / 16)));
		int minY = mc.level.getMinY();
		int maxY = mc.level.getMaxY();
		int secCount = (maxY - minY) / 16;
		BlockPos base = mc.player.blockPosition();
		int pcx = base.getX() >> 4;
		int pcz = base.getZ() >> 4;
		List<BlockPos> found = new ArrayList<>();
		int reads = 0;
		int chunksScanned = 0;
		boolean partial = false;
		outer:
		for (int r = 0; r <= chunkRadius; r++) {
			for (int cx = pcx - r; cx <= pcx + r; cx++) {
				for (int cz = pcz - r; cz <= pcz + r; cz++) {
					if (Math.max(Math.abs(cx - pcx), Math.abs(cz - pcz)) != r) {
						continue;
					}
					net.minecraft.world.level.chunk.ChunkAccess chunk = mc.level.getChunk(cx, cz, ChunkStatus.FULL, false);
					if (chunk == null) {
						continue;
					}
					chunksScanned++;
					int wx0 = chunk.getPos().getMinBlockX();
					int wz0 = chunk.getPos().getMinBlockZ();
					LevelChunkSection[] secs = chunk.getSections();
					int n = Math.min(secs.length, secCount);
					for (int si = 0; si < n; si++) {
						LevelChunkSection sec = secs[si];
						if (sec == null || sec.hasOnlyAir()) {
							continue;
						}
						int y0 = minY + si * 16;
						for (int ly = 0; ly < 16; ly++) {
							for (int lz = 0; lz < 16; lz++) {
								for (int lx = 0; lx < 16; lx++) {
									BlockState st = sec.getBlockState(lx, ly, lz);
									if (st.isAir()) {
										continue;
									}
									reads++;
									if (matchesName(blockName(st), query)) {
										found.add(new BlockPos(wx0 + lx, y0 + ly, wz0 + lz));
										if (found.size() >= 512) {
											break outer;
										}
									}
								}
							}
						}
					}
				}
			}
			if (found.size() >= 256 && r >= 4) {
				break;
			}
			if (reads > 4000000) {
				partial = true;
				break;
			}
		}
		if (found.isEmpty()) {
			return "{\"status\":\"error\",\"message\":\"已搜索全部加载区块(" + chunksScanned + "个" + (partial ? "，因数据量提前截断" : "") + ")没找到 " + query + "。可能不在加载范围内，建议往一个方向 walk_to 探索后再找\"}";
		}
		found.sort(Comparator.comparingDouble(p -> mc.player.distanceToSqr(Vec3.atCenterOf(p))));
		JsonObject o = new JsonObject();
		JsonArray arr = new JsonArray();
		for (int i = 0; i < Math.min(limit, found.size()); i++) {
			BlockPos pos = found.get(i);
			JsonArray a = new JsonArray();
			a.add(pos.getX());
			a.add(pos.getY());
			a.add(pos.getZ());
			arr.add(a);
		}
		o.add("matches", arr);
		o.addProperty("status", "success");
		o.addProperty("total_found", found.size());
		o.addProperty("chunks_scanned", chunksScanned);
		if (partial) {
			o.addProperty("partial", true);
		}
		o.addProperty("note", "已搜索全部已加载区块，坐标按距离排序，可直接用于 dig/walk_to");
		return o.toString();
	}

	private int countItem(String item) {
		int n = 0;
		for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
			ItemStack st = mc.player.getInventory().getItem(i);
			if (!st.isEmpty() && matchesItem(st, item)) {
				n += st.getCount();
			}
		}
		return n;
	}

	private boolean matchesItem(ItemStack st, String query) {
		return matchesName(itemName(st), query);
	}

	private boolean matchesName(String name, String query) {
		String q = query.toLowerCase(java.util.Locale.ROOT).replace("minecraft:", "").trim();
		String n = name.toLowerCase(java.util.Locale.ROOT).replace("minecraft:", "");
		if (n.equals(q)) {
			return true;
		}
		if (n.endsWith("_" + q) || n.startsWith(q + "_") || n.contains("_" + q + "_")) {
			return true;
		}
		return n.contains(q) && q.length() >= 3;
	}

	private String itemName(ItemStack st) {
		return BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
	}

	private String blockName(BlockState st) {
		return BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
	}

	private String heldName() {
		ItemStack st = mc.player.getMainHandItem();
		return st.isEmpty() ? "空" : itemName(st);
	}

	public static void chat(String msg) {
		Minecraft mc2 = Minecraft.getInstance();
		if (mc2.player != null) {
			mc2.execute(() -> mc2.player.displayClientMessage(Component.literal("§b[MCAgent]§r " + msg), false));
		}
	}

	private static String stripJson(String s) {
		return s.replace("\"", "").replace("{", "").replace("}", "");
	}

	private static String esc(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
	}

	private static String fmt(Vec3 v) {
		return String.format("[%.0f, %.0f, %.0f]", v.x, v.y, v.z);
	}

	private static String str(JsonObject o, String key, String def) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
	}

	private static int intArg(JsonObject o, String key, int def) {
		try {
			return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : def;
		} catch (Exception e) {
			return def;
		}
	}

	private static boolean boolArg(JsonObject o, String key, boolean def) {
		try {
			return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsBoolean() : def;
		} catch (Exception e) {
			return def;
		}
	}

	private static double doubleArg(JsonObject o, String key, double def) {
		try {
			return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : def;
		} catch (Exception e) {
			return def;
		}
	}
}
