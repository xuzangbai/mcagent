package com.mcagent.client.mc;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

public final class PathFinder {

	public static final class PathResult {
		public final List<BlockPos> path;
		/** True when A* actually reached the goal, false when it stopped at the closest node. */
		public final boolean complete;
		/** Distance in blocks from the last waypoint to the goal; 0 when complete. */
		public final double missBy;

		PathResult(List<BlockPos> path, boolean complete) {
			this(path, complete, 0);
		}

		PathResult(List<BlockPos> path, boolean complete, double missBy) {
			this.path = path;
			this.complete = complete;
			this.missBy = missBy;
		}
	}

	private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

	private static final class Node {
		final long pos;
		final float g;
		final float f;
		final Node parent;

		Node(long pos, float g, float f, Node parent) {
			this.pos = pos;
			this.g = g;
			this.f = f;
			this.parent = parent;
		}
	}

	private final ClientLevel level;
	private final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
	private final Map<Long, Node> best = new HashMap<>();
	private final long goal;
	private int expansions;

	private PathFinder(ClientLevel level, BlockPos goal) {
		this.level = level;
		this.goal = goal.asLong();
	}

	public static PathResult find(ClientLevel level, BlockPos start, BlockPos goal, int maxNodes) {
		BlockPos g = standable(level, goal);
		if (g == null) {
			g = goal;
		}
		PathFinder pf = new PathFinder(level, g);
		float startH = pf.heuristic(start);
		Node s = new Node(start.asLong(), 0f, startH, null);
		pf.open.add(s);
		pf.best.put(start.asLong(), s);
		Node bestSeen = s;
		double bestH = startH;
		boolean reached = false;
		while (!pf.open.isEmpty() && pf.expansions < maxNodes) {
			Node cur = pf.open.poll();
			if (cur.pos == pf.goal) {
				reached = true;
				bestSeen = cur;
				break;
			}
			double h = pf.heuristic(BlockPos.of(cur.pos));
			if (h < bestH) {
				bestH = h;
				bestSeen = cur;
			}
			pf.expansions++;
			pf.expand(cur, BlockPos.of(cur.pos));
		}
		if (bestSeen == s) {
			return null;
		}
		List<BlockPos> path = pf.build(bestSeen);
		if (path.isEmpty()) {
			return null;
		}
		// Collapse runs of same-direction steps into single waypoints. Walking every cell
		// makes the bot stutter, and each intermediate point is a chance to overshoot and
		// circle back — the visible symptom was the character jittering in place.
		path = smooth(path);
		// A* stopping at the closest node still counts as complete when that node is on the
		// goal: the search ends on the first pop of the goal cell, so "not reached" with a
		// zero miss distance means it arrived via the best-effort branch, not that it failed.
		double missBy = 0;
		if (!path.isEmpty()) {
			BlockPos last = path.get(path.size() - 1);
			missBy = Math.sqrt(last.distSqr(BlockPos.of(pf.goal)));
		}
		boolean arrived = reached || missBy <= 1.5;
		return new PathResult(path, arrived, missBy);
	}

	/**
	 * Removes waypoints that are redundant because a straight run reaches the next one.
	 *
	 * <p>Only same-height, unobstructed straight runs are collapsed: a step that changes
	 * height or needs a jump must stay its own waypoint, because those are exactly the
	 * places where the movement code has to do something other than "hold forward".
	 */
	private static List<BlockPos> smooth(List<BlockPos> path) {
		if (path.size() <= 2) {
			return path;
		}
		List<BlockPos> out = new ArrayList<>();
		out.add(path.get(0));
		int i = 0;
		while (i < path.size() - 1) {
			int furthest = i + 1;
			// extend as far as the straight run stays level and clear
			for (int j = i + 2; j < path.size(); j++) {
				if (!sameLine(path.get(i), path.get(j))) {
					break;
				}
				if (path.get(j).getY() != path.get(i).getY()) {
					break;
				}
				furthest = j;
			}
			out.add(path.get(furthest));
			i = furthest;
		}
		return out;
	}

	/** True when a and b share a row, column or exact diagonal. */
	private static boolean sameLine(BlockPos a, BlockPos b) {
		int dx = b.getX() - a.getX();
		int dz = b.getZ() - a.getZ();
		if (dx == 0 || dz == 0) {
			return true;
		}
		return Math.abs(dx) == Math.abs(dz);
	}

	/**
	 * Finds a standable cell at or near {@code goal}.
	 *
	 * <p>Searches the requested column first, then spirals outward horizontally. The old
	 * version only scanned straight up and down, so a goal floating over a cliff, a tree or
	 * water returned null, the raw goal was used, and A* spent its whole node budget trying
	 * to stand inside solid rock — reporting "closest node" as if it were arrival.
	 */
	private static BlockPos standable(ClientLevel level, BlockPos goal) {
		if (isStandable(level, goal)) {
			return goal;
		}
		// vertical first: the common case is a goal a step or two off the ground
		for (int dy = 1; dy <= 8; dy++) {
			BlockPos up = goal.above(dy);
			if (up.getY() <= level.getMaxY() && isStandable(level, up)) {
				return up;
			}
			BlockPos down = goal.below(dy);
			if (down.getY() >= level.getMinY() && isStandable(level, down)) {
				return down;
			}
		}
		// then outward, nearest ring first
		for (int r = 1; r <= 4; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
						continue;
					}
					for (int dy = 4; dy >= -4; dy--) {
						BlockPos c = goal.offset(dx, dy, dz);
						if (isStandable(level, c)) {
							return c;
						}
					}
				}
			}
		}
		return null;
	}

	/** True when a player can stand here: body room free, floor beneath, no hazard. */
	private static boolean isStandable(ClientLevel lvl, BlockPos c) {
		if (c.getY() <= lvl.getMinY() || c.getY() >= lvl.getMaxY()) {
			return false;
		}
		if (!walkableAt(lvl, c.getX(), c.getY(), c.getZ())) {
			return false;
		}
		if (!solidGroundAt(lvl, c.getX(), c.getY() - 1, c.getZ())
				&& !waterAt(lvl, c.getX(), c.getY(), c.getZ())) {
			return false;
		}
		return !waterAt(lvl, c.getX(), c.getY(), c.getZ())
				|| waterAt(lvl, c.getX(), c.getY() - 1, c.getZ());
	}

	private void expand(Node cur, BlockPos p) {
		int x = p.getX();
		int y = p.getY();
		int z = p.getZ();
		boolean inWater = waterAt(x, y, z);
		for (int[] d : DIRS) {
			int nx = x + d[0];
			int nz = z + d[1];
			boolean nWater = waterAt(nx, y, nz);
			if (walkable(nx, y, nz) && (solidGround(nx, y - 1, nz) || nWater)) {
				add(cur, nx, y, nz, cur.g + (nWater ? 1.8f : 1.0f));
			}
			float digHead = diggable(nx, y + 1, nz);
			float digFeet = diggable(nx, y, nz);
			float digPair = Math.max(digHead, digFeet);
			if (!inWater && digPair > 0 && passable(nx, y + 2, nz)) {
				add(cur, nx, y, nz, cur.g + 1.0f + digPair);
			}
			if (!inWater && walkable(nx, y + 1, nz) && walkable(x, y + 1, z) && (solidGround(nx, y, nz) || waterAt(nx, y + 1, nz))) {
				add(cur, nx, y + 1, nz, cur.g + 1.9f);
			} else if (!inWater && walkable(x, y + 1, z) && digPair > 0 && (solidGround(nx, y, nz) || digFeet > 0 || digHead > 0)) {
				float upDig = Math.max(diggable(nx, y + 2, nz) < 0 ? 0 : diggable(nx, y + 2, nz), digPair);
				if (passable(x, y + 2, z) && upDig > 0) {
					add(cur, nx, y + 1, nz, cur.g + 1.9f + upDig);
				}
			}
			if (!inWater) {
				int maxDrop = 4;
				for (int drop = 1; drop <= 12; drop++) {
					boolean clear = true;
					for (int k = 0; k <= drop; k++) {
						if (!walkable(nx, y - k, nz)) {
							clear = false;
							break;
						}
					}
					if (!clear) {
						break;
					}
					if (waterAt(nx, y - drop, nz)) {
						add(cur, nx, y - drop, nz, cur.g + 1.0f + 0.3f * drop);
						break;
					}
					if (drop > maxDrop) {
						break;
					}
					if (solidGround(nx, y - drop - 1, nz)) {
						add(cur, nx, y - drop, nz, cur.g + 1.0f + 0.5f * drop);
						break;
					}
				}
			}
		}
		if (!inWater) {
			for (int[] a : DIRS) {
				for (int[] b : DIRS) {
					if (a[0] == 0 || b[0] == 0 || a[0] == b[0] || a[1] == b[1]) {
						continue;
					}
					int nx = x + a[0] + b[0];
					int nz = z + a[1] + b[1];
					if (walkable(x + a[0], y, z + a[1]) && walkable(x + b[0], y, z + b[1])
							&& walkable(nx, y, nz) && solidGround(nx, y - 1, nz)) {
						add(cur, nx, y, nz, cur.g + 1.45f);
					}
				}
			}
		}
	}

	private void add(Node cur, int x, int y, int z, float g) {
		long key = BlockPos.asLong(x, y, z);
		Node prev = best.get(key);
		if (prev != null && prev.g <= g) {
			return;
		}
		Node n = new Node(key, g, g + heuristic(BlockPos.of(key)), cur);
		best.put(key, n);
		open.add(n);
	}

	private float diggable(int x, int y, int z) {
		BlockPos pos = new BlockPos(x, y, z);
		BlockState st = level.getBlockState(pos);
		if (st.isAir() || st.getCollisionShape(level, pos).isEmpty()) {
			return 0;
		}
		if (st.getBlock() instanceof net.minecraft.world.level.block.BaseEntityBlock) {
			return -1;
		}
		float speed = st.getDestroySpeed(level, pos);
		if (speed < 0 || speed > 20) {
			return -1;
		}
		for (int[] d : new int[][]{{0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}}) {
			if (!level.getFluidState(pos.offset(d[0], d[1], d[2])).isEmpty()) {
				return -1;
			}
		}
		return 2.0f + speed * 2.0f;
	}

	private float heuristic(BlockPos p) {
		int dx = Math.abs(p.getX() - BlockPos.getX(goal));
		int dy = Math.abs(p.getY() - BlockPos.getY(goal));
		int dz = Math.abs(p.getZ() - BlockPos.getZ(goal));
		int min = Math.min(dx, dz);
		int max = Math.max(dx, dz);
		// Octile distance for the horizontal part, plus a small vertical term.
		//
		// The vertical weight must stay below the cheapest possible step (1.0 for flat
		// ground), otherwise the estimate can exceed the true remaining cost and A* stops
		// being optimal: it settles for the first path that looks good and reports arrival
		// far from the target. The old 1.5 did exactly that.
		return (max + 0.414f * min) + dy * 0.9f;
	}

	private List<BlockPos> build(Node end) {
		List<BlockPos> out = new ArrayList<>();
		Node n = end;
		while (n != null) {
			out.add(BlockPos.of(n.pos));
			n = n.parent;
		}
		java.util.Collections.reverse(out);
		if (!out.isEmpty()) {
			out.remove(0);
		}
		return out;
	}

	private boolean walkable(int x, int y, int z) {
		return walkableAt(level, x, y, z);
	}

	private static boolean walkableAt(ClientLevel lvl, int x, int y, int z) {
		return passableAt(lvl, x, y, z) && passableAt(lvl, x, y + 1, z);
	}

	private boolean passable(int x, int y, int z) {
		return passableAt(level, x, y, z);
	}

	private boolean waterAt(int x, int y, int z) {
		return waterAt(level, x, y, z);
	}

	private static boolean waterAt(ClientLevel lvl, int x, int y, int z) {
		FluidState fluid = lvl.getFluidState(new BlockPos(x, y, z));
		return fluid.is(FluidTags.WATER);
	}

	private static boolean passableAt(ClientLevel lvl, int x, int y, int z) {
		BlockPos pos = new BlockPos(x, y, z);
		BlockState st = lvl.getBlockState(pos);
		if (!st.getCollisionShape(lvl, pos).isEmpty()) {
			return false;
		}
		if (st.is(Blocks.FIRE) || st.is(Blocks.SOUL_FIRE) || st.is(Blocks.CACTUS) || st.is(Blocks.POWDER_SNOW)) {
			return false;
		}
		FluidState fluid = st.getFluidState();
		return !fluid.is(FluidTags.LAVA);
	}

	private boolean solidGround(int x, int y, int z) {
		return solidGroundAt(level, x, y, z);
	}

	private static boolean solidGroundAt(ClientLevel lvl, int x, int y, int z) {
		BlockPos pos = new BlockPos(x, y, z);
		BlockState st = lvl.getBlockState(pos);
		if (st.getCollisionShape(lvl, pos).isEmpty()) {
			return false;
		}
		if (st.is(Blocks.MAGMA_BLOCK) || st.is(Blocks.CACTUS)) {
			return false;
		}
		// Leaves and other foliage have a collision shape, so a naive "has a hitbox" test
		// treats a treetop as walkable ground. The bot then paths onto the canopy and gets
		// stuck there — exactly the failure seen in testing, where it stood on oak_leaves
		// and made no progress. Foliage is not a floor.
		if (st.is(Blocks.OAK_LEAVES) || st.is(Blocks.SPRUCE_LEAVES) || st.is(Blocks.BIRCH_LEAVES)
				|| st.is(Blocks.JUNGLE_LEAVES) || st.is(Blocks.ACACIA_LEAVES) || st.is(Blocks.DARK_OAK_LEAVES)
				|| st.is(Blocks.MANGROVE_LEAVES) || st.is(Blocks.CHERRY_LEAVES) || st.is(Blocks.AZALEA_LEAVES)
				|| st.is(Blocks.FLOWERING_AZALEA_LEAVES) || st.is(Blocks.PALE_OAK_LEAVES)) {
			return false;
		}
		return true;
	}
}
