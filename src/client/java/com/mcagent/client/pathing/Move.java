package com.mcagent.client.pathing;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * One reachable position in the search graph, with the work needed to get there.
 *
 * <p>Ported from mineflayer-pathfinder's {@code Move}: the important part is not the
 * coordinate but the accompanying {@link #toBreak} / {@link #toPlace} lists, which let the
 * executor carry out exactly the edits the planner assumed instead of guessing at them.
 */
public final class Move {

	public final int x;
	public final int y;
	public final int z;
	/** Scaffolding blocks still available at this point in the plan. */
	public final int remainingBlocks;
	/** Accumulated cost of the whole path to this node. */
	public final double cost;
	public final List<BlockPos> toBreak;
	public final List<BlockPos> toPlace;
	/** True when reaching this cell needs a running jump over a gap. */
	public final boolean parkour;

	public Move(int x, int y, int z, int remainingBlocks, double cost,
			List<BlockPos> toBreak, List<BlockPos> toPlace, boolean parkour) {
		this.x = x;
		this.y = y;
		this.z = z;
		this.remainingBlocks = remainingBlocks;
		this.cost = cost;
		this.toBreak = toBreak == null ? new ArrayList<>() : toBreak;
		this.toPlace = toPlace == null ? new ArrayList<>() : toPlace;
		this.parkour = parkour;
	}

	public Move(int x, int y, int z, int remainingBlocks, double cost) {
		this(x, y, z, remainingBlocks, cost, null, null, false);
	}

	public BlockPos pos() {
		return new BlockPos(x, y, z);
	}

	@Override
	public String toString() {
		return x + "," + y + "," + z + " (cost " + String.format("%.1f", cost) + ")";
	}
}
