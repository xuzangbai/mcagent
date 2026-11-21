package com.mcagent.client.pathing;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Decides how the player should travel to the next node — walk, jump, or sprint-jump.
 *
 * <p>This replaces mineflayer-pathfinder's {@code physics.js}. That file drives
 * {@code prismarine-physics}, a full re-implementation of Minecraft movement, and simulating
 * a tick-by-tick player is the only way to answer these questions for a <em>remote</em> bot
 * that has to predict what the server will do.
 *
 * <p>A client mod does not have that problem: the player here is a real Minecraft player, and
 * the world is the real world. So the same questions are answered geometrically — is the
 * destination reachable at this height, and is the space along the way clear — which is both
 * far smaller and, for normal terrain, reaches the same decisions. The three checks keep the
 * original's meanings:
 *
 * <ul>
 *   <li>{@link #canStraightLine} — reach it without leaving the ground.</li>
 *   <li>{@link #canWalkJump} — reach it by jumping, no sprint.</li>
 *   <li>{@link #canSprintJump} — reach it by sprinting and jumping (a gap).</li>
 * </ul>
 */
public final class Physics {

	private final net.minecraft.client.Minecraft mc;

	/**
	 * Takes the client, not the level.
	 *
	 * <p>Capturing {@code mc.level} in the constructor looks harmless and is a crash: this is
	 * built while the action executor is being constructed, which happens before any world is
	 * joined, so the captured level is permanently null and the first movement check throws.
	 * The level is read per call instead.
	 */
	public Physics(net.minecraft.client.Minecraft mc) {
		this.mc = mc;
	}

	private ClientLevel level() {
		return mc.level;
	}

	/** Highest step a player climbs without jumping. */
	private static final double STEP_HEIGHT = 0.6;
	/** Highest step a player climbs with a jump. */
	private static final double JUMP_HEIGHT = 1.3;

	/** True when the destination can be walked to with both feet staying on the ground. */
	public boolean canStraightLine(LocalPlayer p, Move node) {
		double dy = node.y - p.getY();
		if (dy > STEP_HEIGHT || dy < -STEP_HEIGHT) {
			return false;
		}
		return clearBetween(p, node, dy);
	}

	/**
	 * Walk off an edge and land up to four blocks lower without trying to jump.
	 *
	 * <p>Replaces a straight-line sweep at the body's current height, which was wrong in the
	 * same way the ascend and parkour sweeps were: it modelled the step as a line at constant
	 * height, when the body actually walks off the lip and falls. The sweep carried ±0.31
	 * corner offsets, so a body standing near the edge of its own cell sampled the cell BESIDE
	 * the destination — and refused the step because of a block that is not in the path at all.
	 *
	 * <p>Concretely, the course-2 pocket: stepping down from (225,106,450) to (226,105,450), a
	 * cardinal step. With the body at z=450.99 the sweep's corner probe landed in cell 451,
	 * where (226,106,451) is stone, and the step was rejected — while the actual path, through
	 * the open cell (226,106,450) and down onto (226,105,450), is clear.
	 *
	 * <p>So the question is asked per cell, as Baritone asks it: the space the body moves
	 * through on its way off the edge must be open, and the landing must be somewhere it can
	 * stand. {@link #standable} already covers the landing column, feet and head.
	 */
	public boolean canWalkDown(LocalPlayer p, Move node) {
		double drop = p.getY() - node.y;
		if (drop <= STEP_HEIGHT || drop > 4.5 || !standable(node.x, node.y, node.z)) {
			return false;
		}
		BlockPos here = p.blockPosition();
		int dx = Integer.signum(node.x - here.getX());
		int dz = Integer.signum(node.z - here.getZ());
		if (dx == 0 && dz == 0) {
			return false; // straight down is not a walk
		}
		return descendApplies(drop, true,
				passable(here.offset(dx, 0, dz)) && passable(here.offset(dx, 1, dz)),
				dx == 0 || dz == 0 || (passable(here.offset(dx, 0, 0)) && passable(here.offset(0, 0, dz))));
	}

	/**
	 * The step-down rule as a pure function, so it can be tested without a world.
	 *
	 * <p>What is deliberately absent: any test of the cell the body is standing in. The old
	 * sweep sampled the body's own cell corners, which is how a block beside the path got
	 * blamed for blocking it.
	 *
	 * @param drop      height lost by the step
	 * @param standable whether the landing cell has room for the body and a floor
	 * @param aheadOpen whether the cell the body steps into, at its own height, is open
	 * @param flanksOpen whether both corner cells are open; true for cardinal steps
	 */
	static boolean descendApplies(double drop, boolean standable, boolean aheadOpen, boolean flanksOpen) {
		if (drop <= STEP_HEIGHT || drop > 4.5 || !standable || !aheadOpen) {
			return false;
		}
		return flanksOpen;
	}

	private boolean clearAtHeight(double sx, double sz, double ex, double ez, double height) {
		int steps = Math.max(4, (int) Math.ceil(Math.hypot(ex - sx, ez - sz) * 4));
		for (int i = 1; i <= steps; i++) {
			double t = (double) i / steps;
			double x = sx + (ex - sx) * t;
			double z = sz + (ez - sz) * t;
			for (double ox : new double[]{-0.31, 0.31}) {
				for (double oz : new double[]{-0.31, 0.31}) {
					BlockPos feet = BlockPos.containing(x + ox, height, z + oz);
					if (!passable(feet) || !passable(feet.above())) {
						return false;
					}
				}
			}
		}
		return true;
	}

	/** True when a planned gap jump is still safe from the player's actual position. */
	public boolean canSprintJump(LocalPlayer p, Move node) {
		if (!node.parkour || !p.onGround()) {
			return false;
		}
		double dy = node.y - p.getY();
		if (dy > JUMP_HEIGHT || dy < -JUMP_HEIGHT || !standable(node.x, node.y, node.z)) {
			return false;
		}
		double horiz = Math.hypot(node.x + 0.5 - p.getX(), node.z + 0.5 - p.getZ());
		if (horiz <= 1 || horiz > 4.5) {
			return false;
		}
		// Headroom over the launch cell, then the columns the body flies THROUGH.
		//
		// <p>Deliberately NOT a swept arc. The earlier version interpolated the body's height
		// linearly from take-off to landing, which is not what a jump does: the body rises fast
		// and comes down late, so near the landing it is far ABOVE that line. Sampling the line
		// put the body at y=104.9 inside the landing column — whose step block at y=104 is
		// solid — and refused the jump. The bot stood at a gap it could clear.
		//
		// <p>Baritone asks it per column instead ({@code MovementParkour.cost}): the cells the
		// body passes through must be clear at its own height and above, and the landing must
		// be standable. Note the landing column is deliberately EXCLUDED from the sweep — its
		// block at the player's own height is the very step being jumped onto, so testing it
		// here rejects every ascend. {@link #standable} already checks the space the body
		// actually needs once it is up there.
		//
		// <p>Headroom over the LAUNCH cell is likewise not asked here. It is a property of the
		// instant, not of the move: the walker may be standing in a low pocket and need to
		// stride forward into the open before the leap is possible. The driver asks it every
		// tick ({@link #canJumpNow}); asking it here as well froze the bot in exactly that
		// pocket, refusing a leap it could make one stride later.
		BlockPos here = p.blockPosition();
		int dx = Integer.signum(node.x - here.getX());
		int dz = Integer.signum(node.z - here.getZ());
		int span = Math.max(Math.abs(node.x - here.getX()), Math.abs(node.z - here.getZ()));
		for (int i = 1; i < span; i++) {
			int x = here.getX() + dx * i;
			int z = here.getZ() + dz * i;
			for (int y = here.getY(); y <= here.getY() + 2; y++) {
				if (!passable(new BlockPos(x, y, z))) {
					return false; // something in the flight path
				}
			}
		}
		return true;
	}

	/**
	 * True when the player can walk from where they actually stand to this node's centre.
	 *
	 * <p>Distinct from {@link #canStraightLineBetween}, which measures between two cell
	 * centres. Route shortening must use this for its first segment: the player is routinely
	 * off-centre — hugging a wall, mid-step, knocked sideways — and a line that is clear
	 * between two centres can still clip the cell the body currently overlaps. That mismatch
	 * produced a route whose first hop was ten blocks long, straight into a wall the walker
	 * could not pass, so it re-planned into the same route until it gave up.
	 */
	public boolean canWalkFromPlayer(LocalPlayer p, Move node) {
		double dy = node.y - p.getY();
		if (dy > STEP_HEIGHT || dy < -STEP_HEIGHT) {
			return false;
		}
		if (!clearBetween(p, node, dy)) {
			return false;
		}
		// The floor must also hold all the way, or this is a walk over a hole.
		double horiz = Math.hypot(node.x + 0.5 - p.getX(), node.z + 0.5 - p.getZ());
		int steps = Math.max(4, (int) Math.ceil(horiz * 4));
		for (int i = 1; i <= steps; i++) {
			double t = (double) i / steps;
			double x = p.getX() + (node.x + 0.5 - p.getX()) * t;
			double z = p.getZ() + (node.z + 0.5 - p.getZ()) * t;
			double y = p.getY() + dy * t;
			BlockPos under = BlockPos.containing(x, y, z);
			if (!standable(under.getX(), under.getY(), under.getZ())) {
				return false;
			}
		}
		return true;
	}

	/**
	 * True when the walker should just press forward and let the game resolve collisions.
	 *
	 * <p>This is the fallback for the case where every precise predicate fails but the step is
	 * plainly meant to work. The predicates demand a perfect swept corridor for the body's
	 * full 0.6 width from its exact position; Minecraft itself is more forgiving, sliding a
	 * body along a wall face when its corner clips. Since the walker is rarely exactly on a
	 * cell centre — it drifts, overshoots, gets pushed — the strict checks failed constantly
	 * and the bot froze against corners a player would simply walk around.
	 *
	 * <p>Deliberately bounded: the destination must be standable and close. Pressing forward
	 * at something genuinely solid costs nothing, because the stall detector re-plans when no
	 * progress follows.
	 */
	public boolean canSlideTo(LocalPlayer p, Move node) {
		double dy = node.y - p.getY();
		if (dy > STEP_HEIGHT || dy < -STEP_HEIGHT) {
			return false;
		}
		if (!standable(node.x, node.y, node.z)) {
			return false;
		}
		double horiz = Math.hypot(node.x + 0.5 - p.getX(), node.z + 0.5 - p.getZ());
		return horiz > 0.2 && horiz <= 1.8;
	}

	/**
	 * True when this node is part of a ladder route: the same column as the player, on a
	 * ladder, one to three blocks up or down.
	 *
	 * <p>Deliberately tolerant about the exact height. Climbing is continuous, not a sequence
	 * of cell-sized steps: mid-climb the body sits at y=107.5 while the next rung is y=109, so
	 * demanding a one-block difference rejects the very rung the bot is reaching for — it then
	 * released the keys and slid back down. What matters is the column and the direction.
	 */
	public boolean isClimb(Move node) {
		LocalPlayer p = mc.player;
		if (p == null) {
			return false;
		}
		BlockPos here = p.blockPosition();
		if (node.x != here.getX() || node.z != here.getZ()) {
			return false;
		}
		int dy = node.y - here.getY();
		if (dy == 0 || dy > 4 || dy < -4) {
			return false;
		}
		return isClimbable(new BlockPos(node.x, node.y, node.z))
				|| isClimbable(here)
				|| isClimbable(new BlockPos(node.x, node.y - 1, node.z))
				|| isClimbable(new BlockPos(here.getX(), here.getY() - 1, here.getZ()));
	}

	/** True when the step is a ladder move: the same column, on a ladder, going up or down. */
	public boolean canClimb(LocalPlayer p, Move node) {
		BlockPos here = p.blockPosition();
		// Same column: climbing up or down the rungs.
		if (node.x == here.getX() && node.z == here.getZ()) {
			int dy = node.y - here.getY();
			// The planner emits rungs one cell at a time, but the body can be anywhere in the
			// column — mid-rung it sits at y=107.5 while the next node is y=109 — and the route
			// may also have been re-planned from further down. A fixed three-cell window
			// rejected the very rung being reached for.
			if (dy == 0 || Math.abs(dy) > 4) {
				return false;
			}
			// A climb is legal when the rungs are here: the node's cell, the body's own cell,
			// or the cell just below either (the body's feet rest on the rung beneath).
			return isClimbable(new BlockPos(node.x, node.y, node.z))
					|| isClimbable(here)
					|| isClimbable(new BlockPos(node.x, node.y - 1, node.z))
					|| isClimbable(new BlockPos(here.getX(), here.getY() - 1, here.getZ()));
		}
		// Dismounting at the top: step sideways off the ladder onto solid ground.
		//
		// The last rung is always a sideways move — the shaft is capped by the upper floor,
		// so there is no rung above to continue to. Requiring the same column here left the
		// bot hanging at the top of a shaft it had just climbed, refusing the one move that
		// completes the climb.
		if (!isOnClimbable(p)) {
			return false;
		}
		int dx = node.x - here.getX();
		int dz = node.z - here.getZ();
		if (Math.abs(dx) + Math.abs(dz) != 1) {
			return false; // one cardinal step only
		}
		int dy = node.y - here.getY();
		if (dy < 0 || dy > 1) {
			return false;
		}
		return standable(node.x, node.y, node.z);
	}

	/** True when the player is currently holding a ladder, vine or scaffold. */
	public boolean isOnClimbable(LocalPlayer p) {
		return isClimbable(p.blockPosition()) || p.onClimbable();
	}

	private boolean isClimbable(BlockPos pos) {
		BlockState st = level().getBlockState(pos);
		return st.is(net.minecraft.world.level.block.Blocks.LADDER)
				|| st.is(net.minecraft.world.level.block.Blocks.VINE)
				|| st.is(net.minecraft.world.level.block.Blocks.SCAFFOLDING)
				|| st.is(net.minecraft.world.level.block.Blocks.CAVE_VINES)
				|| st.is(net.minecraft.world.level.block.Blocks.CAVE_VINES_PLANT);
	}

	/**
	 * True when the next node is a long fall whose landing is water.
	 *
	 * <p>Water is the one landing that makes a fall of any depth safe, so the walker steps off
	 * the ledge rather than refusing a drop far deeper than {@code maxDropDown}.
	 */
	public boolean canFallIntoWater(LocalPlayer p, Move node) {
		if (node.y >= p.getY() - 1) {
			return false; // not a fall
		}
		BlockState feet = level().getBlockState(new BlockPos(node.x, node.y, node.z));
		if (!feet.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
			return false;
		}
		// Step off into clear air, then fall: nothing solid in the column below the ledge.
		BlockPos here = p.blockPosition();
		BlockPos edge = new BlockPos(node.x, here.getY(), node.z);
		if (!passable(edge) || !passable(edge.above())) {
			return false;
		}
		for (int y = node.y + 1; y < here.getY(); y++) {
			if (!passable(new BlockPos(node.x, y, node.z))) {
				return false; // something in the way down
			}
		}
		return true;
	}

	/**
	 * True when the walker should simply steer at this node, the way a mob does.
	 *
	 * <p>This is the fallback that stops the navigator freezing on geometry that no
	 * single-step predicate happens to describe — the reason a vanilla zombie walks up a
	 * hand-built staircase this walker used to stand at. A mob's navigator has no catalogue of
	 * named moves at all: it steers at the next node, and jumps when it is on the ground and
	 * the node is above it.
	 *
	 * <p>Four conditions make steering safe rather than blind:
	 *
	 * <ul>
	 *   <li>the destination is somewhere a body can stand;</li>
	 *   <li>it is at most a jump above and not below the feet, so steering never walks off a
	 *       ledge (drops have their own predicates);</li>
	 *   <li>it is close enough that this is a step, not a journey;</li>
	 *   <li>the cell straight ahead is either standable, or open air with the node a step up —
	 *       which is exactly the cell a body jumps from, and is why a ceiling overhead is not
	 *       consulted here.</li>
	 * </ul>
	 *
	 * <p>If a steer still makes no progress, the stall detector re-plans and the transition is
	 * forbidden, so being permissive is self-correcting rather than a way to grind against a
	 * wall forever.
	 */
	public boolean canSteerToward(LocalPlayer p, Move node) {
		if (!standable(node.x, node.y, node.z)) {
			return false;
		}
		double dy = node.y - p.getY();
		if (dy < -0.5 || dy > JUMP_HEIGHT) {
			return false;
		}
		double horiz = Math.hypot(node.x + 0.5 - p.getX(), node.z + 0.5 - p.getZ());
		if (horiz <= 0.2 || horiz > 3.5) {
			return false;
		}
		BlockPos here = p.blockPosition();
		int dx = Integer.signum(node.x - here.getX());
		int dz = Integer.signum(node.z - here.getZ());
		if (dx == 0 && dz == 0) {
			return true; // the node is the cell the body is already in
		}
		BlockPos ahead = here.offset(dx, 0, dz);
		if (standable(ahead.getX(), ahead.getY(), ahead.getZ())) {
			return true; // walk straight into it
		}
		// Open air ahead with the node a step up: the body jumps from where it stands, so this
		// is a real approach and not a walk into a wall.
		return dy > STEP_HEIGHT && passable(ahead) && passable(ahead.above());
	}

	/**
	 * The yaw to steer while rubbing past a corner: the ideal, or a 45-degree escape from it.
	 *
	 * <p>This is the behaviour behind Baritone's {@code moveTowardsWithSlightRotation}, and it
	 * is the difference between a walker that rounds a single-cell corner and one that stops
	 * dead against it.
	 *
	 * <p>The problem it solves: the body is 0.6 wide and the ideal heading points at the
	 * destination's centre. At a corner that heading runs the body's own bounding box into the
	 * block diagonally beside it — the corner the route legitimately turns around. Held
	 * straight, the body is pressed against a face making no progress, the walker calls itself
	 * stuck, and re-plans. On screen: "碰到墙壁就动不了了".
	 *
	 * <p>A player angles off instead, so the wall's face redirects the motion and the body
	 * slides around — 蹭过去. Which way to angle is not a guess: one of the two 45-degree
	 * escapes leads into open space and the other into the very block being rubbed against, so
	 * the caller probes both and this picks the open one.
	 *
	 * @param idealYaw the yaw pointing at the destination centre
	 * @param leftOpen whether the cell 45 degrees to the left is clear for the body
	 * @param rightOpen whether the cell 45 degrees to the right is clear for the body
	 * @param currentYaw the yaw the body already faces, used to break a tie
	 */
	static float pickRubYaw(float idealYaw, boolean leftOpen, boolean rightOpen, float currentYaw) {
		if (leftOpen && rightOpen) {
			// Both ways round: take the one needing the smaller turn, so the body does not
			// swing across the corner it is trying to avoid.
			float left = idealYaw - 45.0f;
			float right = idealYaw + 45.0f;
			return Math.abs(angleDelta(currentYaw, left)) <= Math.abs(angleDelta(currentYaw, right))
					? left : right;
		}
		if (leftOpen) {
			return idealYaw - 45.0f;
		}
		if (rightOpen) {
			return idealYaw + 45.0f;
		}
		return idealYaw; // boxed in: hold the heading and let the stall detector re-plan
	}

	/** Shortest signed difference between two yaws, in (-180, 180]. */
	static float angleDelta(float from, float to) {
		float delta = (to - from) % 360.0f;
		if (delta > 180.0f) {
			delta -= 360.0f;
		} else if (delta < -180.0f) {
			delta += 360.0f;
		}
		return delta;
	}

	/**
	 * True when the body is pressed against something solid and should be steered to rub past.
	 *
	 * <p>Only meaningful when the body is already trying to move and is being stopped: a
	 * collision while standing still is just a wall, and angling the heading at nothing is how
	 * a bot ends up sidestepping down a corridor for no reason.
	 */
	public boolean isRubbing(LocalPlayer p) {
		return p.horizontalCollision && p.onGround();
	}

	/**
	 * Whether the body would clear the cell 45 degrees off {@code idealYaw}, on the given side.
	 *
	 * <p>Checked at the body's own height, and one cell out: that is the cell the box is being
	 * driven into when the heading is held straight.
	 */
	public boolean rubSideOpen(LocalPlayer p, float idealYaw, boolean left) {
		double rad = Math.toRadians(idealYaw + (left ? -45.0 : 45.0) + 90.0);
		BlockPos here = p.blockPosition();
		int x = here.getX() + (int) Math.round(Math.cos(rad));
		int z = here.getZ() + (int) Math.round(Math.sin(rad));
		if (x == here.getX() && z == here.getZ()) {
			return false; // the rounding collapsed the diagonal; nothing to steer at
		}
		return passable(new BlockPos(x, here.getY(), z)) && passable(new BlockPos(x, here.getY() + 1, z));
	}

	/**
	 * True when the body is airborne but still travelling toward this node.
	 *
	 * <p>This exists because of a hole between the other rules, and the hole is exactly where
	 * the user sees the bot "鬼畜" — shivering — at single-block steps. Between two cells the
	 * body is in the air: too high for {@link #canStraightLine} (which allows 0.6 of rise) and
	 * not yet low enough for {@link #canWalkDown} (which needs a drop of 0.6). So for the few
	 * ticks of the step, no mode matches, the walker releases the keys, the body loses its
	 * momentum, and the step fails — then it tries again. On screen: a bot that stutters at a
	 * step for a second before taking it.
	 *
	 * <p>A player never does this. Mid-step you keep holding forward and let the arc finish;
	 * that is what carries you up onto the block or down off it. So while airborne and heading
	 * somewhere a body can stand, keep steering.
	 */
	public boolean canRideOut(LocalPlayer p, Move node) {
		if (p.onGround() || p.isInWater() || isOnClimbable(p)) {
			return false; // on solid footing or in a medium with its own rules
		}
		if (!standable(node.x, node.y, node.z)) {
			return false;
		}
		// The body must be falling toward the node's level, not rocketing past it: an ascend
		// that is still rising will reach its own apex, and steering sideways there is what
		// makes a jump land short.
		double dy = node.y - p.getY();
		if (dy > JUMP_HEIGHT || dy < -4.5) {
			return false;
		}
		return Math.hypot(node.x + 0.5 - p.getX(), node.z + 0.5 - p.getZ()) <= 2.5;
	}

	/**
	 * True when the next node is one block up and beside the player: an ascend to jump onto.
	 *
	 * <p>This is the step a player takes without thinking — walk at the riser, jump, land on
	 * top — and the one {@link #canWalkJump} cannot express. Its swept corridor runs through
	 * the riser at the player's own height and reports a wall, because the body only clears
	 * that height <em>after</em> the jump has lifted it. Baritone models this as a movement of
	 * its own ({@code MovementAscend}): move toward the destination, and press jump once the
	 * head is clear.
	 *
	 * <p>So there is deliberately no sweep here, and no headroom requirement: the walker keeps
	 * walking toward the step and jumps when it can (see {@link #canJumpNow}). Demanding the
	 * headroom up front is what froze the bot at the foot of a step it could reach by taking
	 * one more stride — the ceiling was over the cell <em>behind</em> it, not the step.
	 *
	 * <p>Nor is a diagonal rejected for a blocked flank. The body slides along the wall face
	 * and arrives at the cardinal cell in front of the step, where the jump is available;
	 * that is what a player does at a corner, and Minecraft's own collision performs the
	 * slide. Rejecting it here sent the walker into the no-step branch instead, which forbade
	 * the transition and re-planned — a visible hitch at every corner of a staircase.
	 */
	public boolean canAscend(LocalPlayer p, Move node) {
		double dy = node.y - p.getY();
		if (dy <= STEP_HEIGHT || dy > JUMP_HEIGHT) {
			return false;
		}
		BlockPos here = p.blockPosition();
		return ascendApplies(dy, standable(node.x, node.y, node.z),
				node.x - here.getX(), node.z - here.getZ(),
				Math.hypot(node.x + 0.5 - p.getX(), node.z + 0.5 - p.getZ()));
	}

	/**
	 * The ascend rule itself, as a pure function, so it can be tested without a world.
	 *
	 * <p>Note what is NOT a parameter: the headroom over the player's own cell. That omission
	 * is the whole point — see {@link #canAscend}. Whether the jump can be started <em>this
	 * tick</em> is a separate question, asked every tick by {@link #canJumpNow}.
	 *
	 * @param dy       height of the destination above the player's feet
	 * @param standable whether the destination cell has room for the body and a floor
	 * @param dx       destination cell minus the player's cell, x
	 * @param dz       destination cell minus the player's cell, z
	 * @param horiz    distance from the player to the destination cell centre
	 */
	static boolean ascendApplies(double dy, boolean standable, int dx, int dz, double horiz) {
		if (dy <= STEP_HEIGHT || dy > JUMP_HEIGHT || !standable) {
			return false;
		}
		if (Math.abs(dx) > 1 || Math.abs(dz) > 1 || (dx == 0 && dz == 0)) {
			return false; // one step, cardinal or diagonal
		}
		return horiz <= 2.2;
	}

	/**
	 * True when the player can leave the ground right now: no ceiling over their head.
	 *
	 * <p>This is Baritone's {@code headBonkClear} in the form this walker needs. A jump that
	 * starts under a low ceiling is wasted, and pressing jump regardless is how a bot ends up
	 * hopping on the spot under an overhang. The point of checking it per tick rather than
	 * refusing the move outright is that the ceiling belongs to the cell the body is standing
	 * in: one stride forward and the same step becomes jumpable.
	 */
	public boolean canJumpNow(LocalPlayer p) {
		BlockPos here = p.blockPosition();
		return passable(here.above()) && passable(here.above(2));
	}

	/**
	 * Whether a jump may be pressed this tick, as a pure function.
	 *
	 * <p>This is Baritone's ascend rule, from {@code MovementAscend.updateState}: it presses
	 * jump only once the body is on the ground, its head is clear, and it is either close to
	 * the destination or already pressed against the riser. Each clause earns its place:
	 *
	 * <ul>
	 *   <li>{@code onGround} — jumping in mid-air does nothing; holding the key anyway makes
	 *       the bot bunny-hop its way down a corridor.</li>
	 *   <li>{@code headClear} — a jump under a ceiling is a wasted hop, and hopping on the
	 *       spot under an overhang is exactly what "it is trying to jump but cannot" looks
	 *       like. Baritone calls this {@code headBonkClear}.</li>
	 *   <li>{@code close || blocked} — jumping from too far away lands short of the step and
	 *       the bot hops backward into place. Baritone waits until the destination is within
	 *       1.2 blocks; the collision clause is the safety net for a body already leaning on
	 *       the step, which can never satisfy a distance test.</li>
	 * </ul>
	 *
	 * @param horiz distance from the body to the destination cell centre
	 */
	static boolean jumpAllowedNow(boolean onGround, boolean headClear, double horiz, boolean blocked) {
		return onGround && headClear && (horiz <= 1.25 || blocked);
	}

	/**
	 * True when the walker has any legal way to take this step from where it stands.
	 *
	 * <p>The same set of modes {@link PathExecutor} steers with, so route bookkeeping and
	 * movement agree on what "reachable" means. Kept here rather than duplicated there: when
	 * the two disagree, a route gets shortened to a hop the walker then refuses to take.
	 */
	public boolean canReachFrom(LocalPlayer p, Move node) {
		return canStraightLine(p, node)
				|| canAscend(p, node)
				|| canWalkDown(p, node)
				|| canSprintJump(p, node)
				|| canSlideTo(p, node)
				|| canClimb(p, node)
				|| canFallIntoWater(p, node);
	}

	/** True when the straight line between two nodes is walkable, for path shortcutting. */
	public boolean canStraightLineBetween(LocalPlayer p, Move a, Move b) {
		double dy = b.y - a.y;
		if (Math.abs(dy) > STEP_HEIGHT) {
			return false;
		}
		int steps = Math.max(4, (int) Math.ceil(Math.hypot(b.x - a.x, b.z - a.z) * 4));
		final double half = 0.31;
		for (int i = 1; i <= steps; i++) {
			double t = (double) i / steps;
			double x = a.x + 0.5 + (b.x - a.x) * t;
			double z = a.z + 0.5 + (b.z - a.z) * t;
			double y = a.y + dy * t;
			for (double ox = -half; ox <= half; ox += half) {
				for (double oz = -half; oz <= half; oz += half) {
					BlockPos feet = BlockPos.containing(x + ox, y, z + oz);
					if (!passable(feet) || !passable(feet.above())) {
						return false;
					}
				}
			}
			// the ground must not vanish underneath
			BlockPos under = BlockPos.containing(x, y, z);
			if (!standable(under.getX(), under.getY(), under.getZ())) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Walks the segment between the player and a node, checking the space the body will
	 * actually occupy.
	 *
	 * <p>Two things make a naive version lie, and both produce the same symptom — the bot
	 * decides it can walk a line that is blocked, keeps holding forward, and grinds into a
	 * wall:
	 *
	 * <ul>
	 *   <li><b>Sampling density.</b> Probing twice per block leaves gaps, and a wall corner or
	 *       a one-block pillar sits comfortably inside one. Samples are taken four times per
	 *       block with a hard floor of four, so even a single-block step gets several probes.</li>
	 *   <li><b>Body width.</b> The player is 0.6 blocks wide, so a centre-line check calls a
	 *       path clear when the shoulders are inside a wall. Every sample probes the body's
	 *       four corners, not just its middle.</li>
	 * </ul>
	 *
	 * <p>The check is deliberately conservative: wrongly answering "blocked" costs a jump or a
	 * re-plan, while wrongly answering "clear" walks the bot into masonry.
	 */
	private boolean clearBetween(LocalPlayer p, Move node, double dy) {
		double sx = p.getX();
		double sy = p.getY();
		double sz = p.getZ();
		double ex = node.x + 0.5;
		double ey = node.y;
		double ez = node.z + 0.5;
		double horiz = Math.hypot(ex - sx, ez - sz);
		int steps = Math.max(4, (int) Math.ceil(horiz * 4));
		final double half = 0.31; // just over half the player's 0.6 width
		for (int i = 1; i <= steps; i++) {
			double t = (double) i / steps;
			double x = sx + (ex - sx) * t;
			double y = sy + (ey - sy) * t;
			double z = sz + (ez - sz) * t;
			for (double ox = -half; ox <= half; ox += half) {
				for (double oz = -half; oz <= half; oz += half) {
					BlockPos feet = BlockPos.containing(x + ox, y, z + oz);
					if (!passable(feet) || !passable(feet.above())) {
						return false;
					}
				}
			}
		}
		return true;
	}

	/**
	 * True when a body can occupy this cell (nothing solid in the way).
	 *
	 * <p>Climbables count as open. A ladder or vine is a thin box bolted to a wall, so a plain
	 * collision test reports the cell as blocked and the walker refuses to step into a shaft
	 * the planner has already routed it through. Keeping this in step with
	 * {@code Movements#getBlock} is what stops the two halves of the navigator disagreeing.
	 */
	/**
	 * True when a body can occupy this cell (nothing solid in the way).
	 *
	 * <p>Climbables count as open. A ladder or vine is a thin box bolted to a wall, so a plain
	 * collision test reports the cell as blocked and the walker refuses to step into a shaft
	 * the planner has already routed it through. Keeping this in step with
	 * {@code Movements#getBlock} is what stops the two halves of the navigator disagreeing.
	 */
	public boolean openAt(int x, int y, int z) {
		return passable(new BlockPos(x, y, z));
	}

	private boolean passable(BlockPos pos) {
		BlockState st = level().getBlockState(pos);
		if (st.is(net.minecraft.world.level.block.Blocks.LADDER)
				|| st.is(net.minecraft.world.level.block.Blocks.VINE)
				|| st.is(net.minecraft.world.level.block.Blocks.SCAFFOLDING)
				|| st.is(net.minecraft.world.level.block.Blocks.CAVE_VINES)
				|| st.is(net.minecraft.world.level.block.Blocks.CAVE_VINES_PLANT)
				|| st.is(net.minecraft.world.level.block.Blocks.TWISTING_VINES)
				|| st.is(net.minecraft.world.level.block.Blocks.WEEPING_VINES)) {
			return true;
		}
		return st.getCollisionShape(level(), pos).isEmpty();
	}

	/**
	 * True when the player can occupy this cell: room for the body, and footing below.
	 *
	 * <p>Kept deliberately in step with the planner's own occupancy rule
	 * ({@code Movements#occupiableAfter}). When these two disagree the navigator tears itself
	 * apart: A* proposes a cell the walker then refuses, and the bot re-plans into the same
	 * route until it gives up. Water counts as footing on both sides, and a full-height
	 * block in either the feet or head cell is fatal on both sides.
	 */
	public boolean standable(int x, int y, int z) {
		BlockPos pos = new BlockPos(x, y, z);
		if (!passable(pos) || !passable(pos.above())) {
			return false;
		}
		BlockPos floor = pos.below();
		if (!level().getBlockState(floor).getCollisionShape(level(), floor).isEmpty()) {
			return true;
		}
		// water is footing too
		return !level().getFluidState(floor).isEmpty() || !level().getFluidState(pos).isEmpty();
	}

	/**
	 * Shortens a route by dropping nodes that a straight walk already covers.
	 *
	 * <p>Ported from {@code postProcessPath}: A* produces a node per cell, which makes the
	 * player jitter as it aims at each one in turn. Collapsing collinear runs into single
	 * waypoints is what makes movement look like a player walking rather than a bot
	 * micro-adjusting.
	 */
	public List<Move> shortcut(LocalPlayer p, List<Move> path) {
		// Every candidate for the FIRST waypoint is measured from the player's real position,
		// not from the anchor cell's centre.
		//
		// <p>"First" means "while the anchor is still the route's first node" — not "the first
		// call". A one-shot flag was wrong: the first candidate usually passes, the flag then
		// latched off, and the next candidate was measured centre-to-centre, letting a
		// nine-block first hop through that the walker could not actually take. The route then
		// began far from the body, every movement mode failed, and it re-planned into the same
		// route — "walks half the maze, then stops".
		return collapseFrom(path,
				(a, b) -> canWalkFromPlayer(p, b),
				(a, b) -> canStraightLineBetween(p, a, b));
	}

	/**
	 * Collapsing with a different test for the opening hop than for the rest of the route.
	 *
	 * @param fromOrigin whether the player can walk from where they stand to {@code b}
	 * @param between    whether a straight walk between two cell centres is possible
	 */
	static List<Move> collapseFrom(List<Move> path,
			java.util.function.BiPredicate<Move, Move> fromOrigin,
			java.util.function.BiPredicate<Move, Move> between) {
		if (path.isEmpty()) {
			return path;
		}
		Move origin = path.get(0);
		return collapse(path, (a, b) -> a == origin ? fromOrigin.test(a, b) : between.test(a, b));
	}

	/**
	 * The collapsing algorithm itself, with the world query injected.
	 *
	 * <p>Split out from {@link #shortcut} so the rule that matters most — the route's first
	 * node is never dropped — can be tested without a live client.
	 *
	 * @param lineClear whether a straight walk from {@code a} to {@code b} is possible
	 */
	static List<Move> collapse(List<Move> path, java.util.function.BiPredicate<Move, Move> lineClear) {
		if (path.size() < 3) {
			return path;
		}
		java.util.ArrayList<Move> out = new java.util.ArrayList<>();
		// The first node is always kept. Collapsing it away was the bug behind the
		// "plans a route, then never moves" loop: the route began several cells ahead, the
		// straight line to that node was clear *from the node that was skipped*, and the
		// walker — standing somewhere else entirely — found every movement mode blocked and
		// sat there until the stuck timer fired and re-planned into the same trap.
		out.add(path.get(0));
		Move anchor = path.get(0);
		for (int i = 1; i < path.size(); i++) {
			Move node = path.get(i);
			Move previous = path.get(i - 1);
			if (mustKeep(anchor, previous, node) || !lineClear.test(anchor, node)) {
				if (out.get(out.size() - 1) != previous) {
					out.add(previous);
				}
				anchor = previous;
			}
		}
		Move last = path.get(path.size() - 1);
		if (out.get(out.size() - 1) != last) {
			out.add(last);
		}
		return out;
	}

	/**
	 * True when a node carries meaning that flattening would destroy.
	 *
	 * <p>An edit, a gap jump or a height change is exactly where the walker has to do
	 * something other than hold forward, so those waypoints survive shortening.
	 */
	static boolean mustKeep(Move anchor, Move previous, Move node) {
		return !node.toBreak.isEmpty() || !node.toPlace.isEmpty() || node.parkour
				|| !previous.toBreak.isEmpty() || !previous.toPlace.isEmpty() || previous.parkour
				|| node.y != anchor.y;
	}
}
