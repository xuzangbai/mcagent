package com.mcagent.client.mc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Swimming, surfacing and getting out of water.
 *
 * <p>The walk code assumes solid ground: it holds forward and jumps at obstacles. In water
 * that fails in three separate ways — the bot sinks instead of swimming up, it runs out of
 * air while pathing through a lake, and it reaches the shore but cannot climb out because
 * the bank is one block above the waterline. Each case is handled explicitly here.
 *
 * <p>All methods must be called on the game thread.
 */
public final class Swimmer {

	private Swimmer() {
	}

	/** Water deeper than this (in blocks) counts as real swimming, not a puddle. */
	private static final double DEEP_WATER = 1.2;

	/** Air level below which the bot heads for the surface. 300 is the full bar. */
	public static final int LOW_AIR = 140;

	/** Air level at which the bot stops everything else and rises. */
	public static final int CRITICAL_AIR = 90;

	/**
	 * True when the player's body is in water, however deep.
	 *
	 * <p>Any depth counts. A threshold on fluid height looks reasonable but opens a dead
	 * zone: a swimmer floating at the surface of a deep pool measures barely a block of
	 * water around its feet, so it fell through to the walking logic — which cannot make
	 * headway in water — and the bot sat at the waterline until the walk timed out.
	 */
	public static boolean inDeepWater(LocalPlayer p) {
		return p.isInWater();
	}

	/** True when the head is submerged and air is running out. */
	public static boolean needsAir(LocalPlayer p) {
		return p.isUnderWater() && p.getAirSupply() < LOW_AIR;
	}

	/** True when air is nearly gone and the bot must rise before anything else. */
	public static boolean criticalAir(LocalPlayer p) {
		return p.isUnderWater() && p.getAirSupply() < CRITICAL_AIR;
	}

	/**
	 * Swims toward a point, rising and advancing at the same time.
	 *
	 * <p>Holding jump for the whole stroke is what keeps a swimmer on the surface: jump is
	 * the upward stroke and forward is the horizontal one, and pressing both together makes
	 * the player porpoise along the top. Separating them — surface first, then travel, as
	 * this used to do — parks the bot in one spot while it breathes, which is why a crossing
	 * that should take seconds stalled until the walk timed out. Baritone drives water the
	 * same way.
	 */
	public static void swimToward(LocalPlayer p, Vec3 target, Minecraft mc) {
		double dx = target.x - p.getX();
		double dz = target.z - p.getZ();
		double horiz = Math.sqrt(dx * dx + dz * dz);

		if (horiz > 0.05) {
			float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
			p.absSnapRotationTo(yaw, 0.0f);
		}

		mc.options.keyUp.setDown(horiz > 0.25);
		mc.options.keyDown.setDown(false);
		mc.options.keyLeft.setDown(false);
		mc.options.keyRight.setDown(false);
		mc.options.keySprint.setDown(horiz > 4);
		// always stroke upward: this is the surface-keeping half of the swim
		mc.options.keyJump.setDown(true);
	}

	/**
	 * Keeps the head above the surface.
	 *
	 * <p>Holds jump continuously rather than only while submerged. Toggling it off the
	 * moment {@code isUnderWater()} goes false let the player sink straight back down, so
	 * the bot bobbed around a metre under the surface and kept drowning — the health bar
	 * fell while it "rested" for air.
	 */
	public static void floatAtSurface(LocalPlayer p, Minecraft mc) {
		releaseWaterKeys(mc);
		mc.options.keyJump.setDown(true);
	}

	/**
	 * Climbs out of water onto the adjacent bank.
	 *
	 * <p>Swimming into a one-block ledge does not work: the player floats against it. The
	 * way out is to jump while pressed against the bank, which lifts the player onto it.
	 *
	 * <p>Only a bank in roughly the direction of {@code toward} is considered. Climbing onto
	 * whichever shore happens to be nearest is how the bot used to scramble out sideways,
	 * away from its route, and then thrash trying to get back in.
	 *
	 * @return true once the player is standing out of the water
	 */
	public static boolean climbOutToward(LocalPlayer p, Minecraft mc, double towardX, double towardZ) {
		if (!p.isInWater()) {
			return true;
		}
		Direction best = shoreToward(p, mc, towardX, towardZ);
		if (best == null) {
			return false;
		}

		BlockPos bank = p.blockPosition().relative(best);
		double dx = bank.getX() + 0.5 - p.getX();
		double dz = bank.getZ() + 0.5 - p.getZ();
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		p.absSnapRotationTo(yaw, 0.0f);

		// press into the bank and jump: this is the move that lifts a swimmer onto land
		mc.options.keyUp.setDown(true);
		mc.options.keySprint.setDown(false);
		mc.options.keyJump.setDown(true);
		return false;
	}

	/**
	 * Swims to an exact exit cell and climbs onto it.
	 *
	 * <p>Steers at the cell itself rather than at "some adjacent bank", because the bank
	 * being climbed is often one or two blocks above the waterline — a quay, a river wall, a
	 * stepped shore — and the adjacent-cell test misses those entirely.
	 *
	 * @return true once the player is out of the water
	 */
	public static boolean climbTo(LocalPlayer p, Minecraft mc, BlockPos exit) {
		if (!p.isInWater()) {
			return true;
		}
		double tx = exit.getX() + 0.5;
		double tz = exit.getZ() + 0.5;
		double dx = tx - p.getX();
		double dz = tz - p.getZ();
		if (Math.hypot(dx, dz) > 0.05) {
			float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
			p.absSnapRotationTo(yaw, 0.0f);
		}
		// press into the bank and hold jump: that is what lifts a swimmer onto a ledge
		mc.options.keyUp.setDown(true);
		mc.options.keyDown.setDown(false);
		mc.options.keyLeft.setDown(false);
		mc.options.keyRight.setDown(false);
		mc.options.keySprint.setDown(false);
		mc.options.keyJump.setDown(true);
		return false;
	}

	/** True when there is a bank to climb onto, in roughly the direction of travel. */
	public static boolean hasShoreToward(LocalPlayer p, Minecraft mc, double towardX, double towardZ) {
		return shoreToward(p, mc, towardX, towardZ) != null;
	}

	/**
	 * Picks the bank to climb, preferring one that lies along the direction of travel.
	 *
	 * <p>Climbing onto whichever shore happens to be nearest is how the bot used to scramble
	 * out sideways, away from its route, then thrash trying to get back in.
	 */
	private static Direction shoreToward(LocalPlayer p, Minecraft mc, double towardX, double towardZ) {
		BlockPos feet = p.blockPosition();
		double wantX = towardX - p.getX();
		double wantZ = towardZ - p.getZ();
		double wantLen = Math.hypot(wantX, wantZ);

		Direction best = null;
		double bestScore = Double.MAX_VALUE;
		for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
			BlockPos bank = feet.relative(d);
			if (!isShore(mc, bank) && !isShore(mc, bank.above())) {
				continue;
			}
			if (wantLen < 0.01) {
				return d;
			}
			double dot = (d.getStepX() * wantX + d.getStepZ() * wantZ) / wantLen;
			if (dot < 0.3) {
				continue;
			}
			double score = 1.0 - dot;
			if (score < bestScore) {
				bestScore = score;
				best = d;
			}
		}
		return best;
	}

	/** True when this cell is dry, standable land — somewhere to walk, not swim. */
	public static boolean isDryLand(Minecraft mc, double x, double y, double z) {
		if (mc.level == null) {
			return false;
		}
		BlockPos c = BlockPos.containing(x, y, z);
		if (mc.level.getFluidState(c).is(FluidTags.WATER)
				|| mc.level.getFluidState(c.below()).is(FluidTags.WATER)) {
			return false;
		}
		return isShore(mc, c);
	}

	/**
	 * Finds somewhere to get out of the water.
	 *
	 * <p>Scans outward from the player and returns the closest cell that is dry, standable
	 * and touches water — the edge of the pool or lake. Ties are broken toward
	 * {@code (preferX, preferZ)} so the bot leaves on the side it was heading for instead
	 * of doubling back.
	 *
	 * <p>This is the whole water strategy in one call: the bot does not navigate through
	 * water, it just needs one coordinate on dry land it can reach.
	 *
	 * @return the exit cell, or null when no bank is within {@code radius}
	 */
	public static BlockPos findExit(Minecraft mc, LocalPlayer p, double preferX, double preferZ, int radius) {
		if (mc.level == null) {
			return null;
		}
		BlockPos feet = p.blockPosition();
		double wantX = preferX - p.getX();
		double wantZ = preferZ - p.getZ();
		double wantLen = Math.hypot(wantX, wantZ);

		for (int r = 1; r <= radius; r++) {
			BlockPos best = null;
			double bestScore = Double.MAX_VALUE;
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					// only the ring at this radius, so the nearest ring always wins
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
						continue;
					}
					for (int dy = -1; dy <= 2; dy++) {
						BlockPos c = feet.offset(dx, dy, dz);
						if (!isShore(mc, c) || !touchesWater(mc, c)) {
							continue;
						}
						// The bank must be low enough to actually climb from the water.
						//
						// A wall of stone is "standable" at its top, but a swimmer cannot get
						// onto it. Without this check the bot picks the lip of a quay, presses
						// into it forever, and never looks for the low spot a few blocks along —
						// which is exactly the case where a bank is raised everywhere but one
						// gap.
						if (c.getY() - p.getY() > 1.5) {
							continue;
						}
						double dot;
						if (wantLen < 0.01) {
							dot = 0;
						} else {
							dot = (dx * wantX + dz * wantZ) / (wantLen * Math.max(1.0, Math.hypot(dx, dz)));
						}
						// prefer banks in the direction of travel
						double score = 1.0 - dot;
						if (score < bestScore) {
							bestScore = score;
							best = c;
						}
					}
				}
			}
			if (best != null) {
				return best;
			}
		}
		return null;
	}

	/** True when one of the four neighbours is water — i.e. this is a bank, not a hill. */
	private static boolean touchesWater(Minecraft mc, BlockPos c) {
		for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
			if (mc.level.getFluidState(c.relative(d)).is(FluidTags.WATER)) {
				return true;
			}
		}
		return false;
	}

	/** True when this cell is a solid, standable bank at the waterline. */
	private static boolean isShore(Minecraft mc, BlockPos c) {
		try {
			BlockState floor = mc.level.getBlockState(c.below());
			if (floor.getCollisionShape(mc.level, c.below()).isEmpty()) {
				return false;
			}
			if (mc.level.getFluidState(c).is(FluidTags.WATER)
					|| mc.level.getFluidState(c.below()).is(FluidTags.WATER)) {
				return false;
			}
			BlockState here = mc.level.getBlockState(c);
			BlockState above = mc.level.getBlockState(c.above());
			return here.getCollisionShape(mc.level, c).isEmpty()
					&& above.getCollisionShape(mc.level, c.above()).isEmpty()
					&& !here.is(Blocks.CACTUS)
					&& !here.is(Blocks.MAGMA_BLOCK);
		} catch (Throwable t) {
			return false;
		}
	}

	/** Releases every movement key the water logic may have pressed. */
	public static void releaseWaterKeys(Minecraft mc) {
		if (mc.options == null) {
			return;
		}
		mc.options.keyUp.setDown(false);
		mc.options.keyDown.setDown(false);
		mc.options.keyLeft.setDown(false);
		mc.options.keyRight.setDown(false);
		mc.options.keyJump.setDown(false);
		mc.options.keySprint.setDown(false);
	}

	/** True when the bot is in lava — always an emergency, never a route. */
	public static boolean inLava(LocalPlayer p) {
		return p.isInLava();
	}
}
