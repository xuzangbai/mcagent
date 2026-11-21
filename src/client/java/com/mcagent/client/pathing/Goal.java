package com.mcagent.client.pathing;

import net.minecraft.core.BlockPos;

/**
 * Where a path is trying to get to.
 *
 * <p>Separated from the search so the same A* serves "stand exactly here", "get within two
 * blocks of this" and "reach this column at any height" — the three goals the agent tools
 * actually ask for.
 */
public interface Goal {

	/** Estimated remaining cost from a node. Must never overestimate. */
	double heuristic(Move node);

	/** True when this node satisfies the goal. */
	boolean isEnd(Move node);

	/** Best-effort distance used to choose a partial path when the goal is unreachable. */
	double distanceTo(Move node);

	/** Stand on this exact block. */
	final class Block implements Goal {
		private final int x;
		private final int y;
		private final int z;

		public Block(BlockPos pos) {
			this(pos.getX(), pos.getY(), pos.getZ());
		}

		public Block(int x, int y, int z) {
			this.x = x;
			this.y = y;
			this.z = z;
		}

		@Override
		public double heuristic(Move n) {
			int dx = Math.abs(n.x - x);
			int dy = Math.abs(n.y - y);
			int dz = Math.abs(n.z - z);
			// A four-block sprint jump can cost four; dropping several blocks costs
			// one. The largest possible move is four horizontally plus four down.
			return Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz) / 5.657;
		}

		@Override
		public boolean isEnd(Move n) {
			return n.x == x && n.y == y && n.z == z;
		}

		@Override
		public double distanceTo(Move n) {
			return Math.sqrt(Math.pow(n.x - x, 2) + Math.pow(n.y - y, 2) + Math.pow(n.z - z, 2));
		}

		@Override
		public String toString() {
			return "Block(" + x + "," + y + "," + z + ")";
		}
	}

	/** Get within {@code range} blocks of a point — enough to reach or use it. */
	final class Near implements Goal {
		private final int x;
		private final int y;
		private final int z;
		private final int range;

		public Near(int x, int y, int z, int range) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.range = range;
		}

		@Override
		public double heuristic(Move n) {
			double d = distanceTo(n);
			return Math.max(0, d - range) / 5.657;
		}

		@Override
		public boolean isEnd(Move n) {
			return distanceTo(n) <= range;
		}

		@Override
		public double distanceTo(Move n) {
			return Math.sqrt(Math.pow(n.x - x, 2) + Math.pow(n.y - y, 2) + Math.pow(n.z - z, 2));
		}

		@Override
		public String toString() {
			return "Near(" + x + "," + y + "," + z + " r" + range + ")";
		}
	}

	/** Reach this column at any height — what "go to these coordinates" usually means. */
	final class XZ implements Goal {
		private final int x;
		private final int z;

		public XZ(int x, int z) {
			this.x = x;
			this.z = z;
		}

		@Override
		public double heuristic(Move n) {
			int dx = Math.abs(n.x - x);
			int dz = Math.abs(n.z - z);
			return Math.hypot(dx, dz) / 4;
		}

		@Override
		public boolean isEnd(Move n) {
			return n.x == x && n.z == z;
		}

		@Override
		public double distanceTo(Move n) {
			return Math.hypot(n.x - x, n.z - z);
		}

		@Override
		public String toString() {
			return "XZ(" + x + "," + z + ")";
		}
	}
}
