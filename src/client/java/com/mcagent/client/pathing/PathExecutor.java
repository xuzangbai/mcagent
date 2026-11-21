package com.mcagent.client.pathing;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks a route, re-deciding continuously rather than once at the start.
 *
 * <p>Ported from mineflayer-pathfinder's {@code monitorMovement}. The important property is
 * that it is a <em>monitor</em>: it runs every tick, re-anchors the route to wherever the
 * player actually is, and re-plans the moment the route stops fitting the world. Computing a
 * path once and then blindly following it is what makes a bot walk into a wall that appeared
 * after planning, or grind against a node it drifted past.
 *
 * <p>Three pieces of state carry the original's behaviour, and all three matter:
 * <ul>
 *   <li>{@link #digging} / {@link #placing} — edits in progress. Without them the executor
 *       re-triggers the same dig or placement every tick and the bot jitters in place
 *       instead of working through the list.</li>
 *   <li>{@link #lastNodeTime} — when a node was last reached. A node that takes more than
 *       {@link #STUCK_MS} means the route is wrong, so it re-plans.</li>
 *   <li>{@link #path} re-anchoring — nodes already passed are dropped, so the route always
 *       starts from the player.</li>
 * </ul>
 */
public final class PathExecutor {

	public enum Status { RUNNING, ARRIVED, FAILED }

	/** A node that takes longer than this to reach means the route no longer fits reality. */
	private static final long STUCK_MS = 3500;
	/** Arrival radius around the cell centre. A wider threshold prevents orbiting corners. */
	private static final double NODE_EPS = 0.60;
	/** A node counts as reached only within this distance, so the route cannot skip ahead. */
	private static final double REACHED_EPS = 0.75;
	/** Give up re-planning after this many attempts so a hopeless goal cannot loop forever. */
	private static final int MAX_REPLANS = 40;
	/** Each client tick searches only this many nodes, keeping rendering responsive. */
	private static final int SEARCH_NODES_PER_TICK = 160;

	private final Minecraft mc;
	private final Physics physics;

	private List<Move> path = new ArrayList<>();
	private AStar.Search search;
	private Goal goal;
	private Vec3 target;

	private boolean digging;
	private boolean placing;
	private BlockPos digPos;
	private boolean digStarted;
	private long lastNodeTime = System.currentTimeMillis();
	/** Body position when progress was last observed; the real stuck signal. */
	private net.minecraft.world.phys.Vec3 lastProgressPos;
	private int replans;
	private String failure;
	private boolean pathComplete;
	/** Body position at the last re-plan, to tell "correcting" apart from "trapped". */
	private net.minecraft.world.phys.Vec3 replanPos;
	/** Consecutive ticks where the next step had no legal movement mode. */
	private int noStepTicks;
	/**
	 * Transitions this walker has proved it cannot perform, shared with the search.
	 *
	 * <p>This is the feedback loop that stops the planner and the walker deadlocking: without
	 * it, a move the planner likes but the walker cannot execute (a jump whose arc clips a
	 * wall) is re-planned identically forever and the bot stands still indefinitely.
	 */
	private final java.util.Set<String> forbidden =
			java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
	/** The movement committed to for {@link #committedNode}, and how long it has run. */
	private Mode committed;
	private Move committedNode;
	/** The cell the body occupied when that movement was chosen: the decision's other input. */
	private BlockPos committedCell;
	/** The cell the committed movement is actually for; see {@link #chooseMovement}. */
	private Move aim;
	/** Whether the last decision was "walk to the step's start cell" rather than the step. */
	private boolean aimedAtLaunchCell;
	/**
	 * How many times the movement mode changed while the route node stayed the same.
	 *
	 * <p>Each one is the body oscillating: a step is taken, the step stops being legal from
	 * the new cell, the body steps back, the step becomes legal again. Surfaced in the debug
	 * line because the symptom — a bot that shivers at a step for a second before going up —
	 * is easy to see on screen and hard to attribute without a counter.
	 */
	private int modeChanges;
	private int commitTicks;
	/** Why the last step could not be taken; surfaced in the periodic path line. */
	private String lastDiag = "";
	/**
	 * The most recently consumed node: where the route expected the body to be standing.
	 *
	 * <p>This is the launch cell for the move that follows. It matters because a jump has to
	 * start from the right place, and the anchor consumes nodes from a 0.75 radius — which is
	 * inside the PREVIOUS cell. A parkour node could therefore become current while the body
	 * was still half a cell short of the ledge it had to jump from, and from there the jump is
	 * genuinely impossible (the ceiling of the cell behind caps it). Baritone has the same
	 * concept: a movement knows its {@code src}, and when the player is neither in {@code src}
	 * nor has begun the jump it steers back toward it ({@code MovementParkour.updateState}).
	 */
	private Move launchCell;
	/** Whether the route may break blocks. Chosen per call; see Movements#canDig. */
	private boolean canDig = true;

	public PathExecutor(Minecraft mc) {
		this.mc = mc;
		this.physics = new Physics(mc);
	}

	public void start(Goal goal, double tx, double ty, double tz) {
		start(goal, tx, ty, tz, true);
	}

	public void start(Goal goal, double tx, double ty, double tz, boolean allowDig) {
		this.canDig = allowDig;
		this.goal = goal;
		this.target = new Vec3(tx, ty, tz);
		this.path = new ArrayList<>();
		this.search = null;
		this.digging = false;
		this.placing = false;
		this.digPos = null;
		this.digStarted = false;
		this.replans = 0;
		this.failure = null;
		this.pathComplete = false;
		this.noStepTicks = 0;
		this.lastNodeTime = System.currentTimeMillis();
		this.lastProgressPos = null;
		this.replanPos = null;
		// A new walk starts with a clean slate: forbidden transitions describe one attempt,
		// and carrying them into an unrelated route would block moves that are fine now.
		forbidden.clear();
		committed = null;
		committedNode = null;
		committedCell = null;
		modeChanges = 0;
	}

	public void stop() {
		releaseKeys();
		goal = null;
		search = null;
		path = new ArrayList<>();
		digging = false;
		placing = false;
	}

	public String failure() {
		return failure;
	}

	public int pathLength() {
		return path.size();
	}

	public String progress() {
		return search != null ? "searching" : path.isEmpty() ? "idle" : "walking";
	}

	public String nextWaypoint() {
		if (path.isEmpty()) {
			return "";
		}
		Move next = path.get(0);
		return next.x + "," + next.y + "," + next.z;
	}

	/** Read-only copy of the remaining route, for the world renderer. */
	public List<Move> routeSnapshot() {
		return new ArrayList<>(path);
	}

	/** One-line search/walk snapshot, for diagnosing a stalled route. */
	public String describe() {
		if (search != null) {
			return "search nodes=" + search.visitedCount();
		}
		if (path.isEmpty()) {
			return "idle";
		}
		Move next = path.get(0);
		StringBuilder route = new StringBuilder();
		for (int i = 0; i < Math.min(4, path.size()); i++) {
			Move m = path.get(i);
			route.append(' ').append(m.x).append(',').append(m.y).append(',').append(m.z);
		}
		return "walk next=" + next.x + "," + next.y + "," + next.z
				+ " left=" + path.size()
				+ " via" + route
				+ (next.parkour ? " parkour" : "")
				+ (next.toBreak.isEmpty() ? "" : " break=" + next.toBreak.size())
				+ (next.toPlace.isEmpty() ? "" : " place=" + next.toPlace.size())
				+ (modeChanges == 0 ? "" : " modeFlips=" + modeChanges)
				+ (lastDiag.isEmpty() ? "" : " | " + lastDiag);
	}

	/** One tick of supervision. Call every client tick while the walk is active. */
	public Status tick() {
		LocalPlayer p = mc.player;
		if (p == null || goal == null) {
			return Status.FAILED;
		}

		anchorPathToPlayer(p);

		// Progress is measured by the BODY MOVING, not by node bookkeeping.
		//
		// The stuck timer used to be refreshed only when the movement block below consumed a
		// node, while node consumption normally happens in anchorPathToPlayer — which uses a
		// wider radius. The result was that the walker could stride down a corridor for
		// seconds with the timer never refreshed, declare itself stuck, and throw the route
		// away and re-search. On screen that is a bot that walks, pauses, and carries on, over
		// and over. Comparing the real position against the last one is what "stuck" means.
		net.minecraft.world.phys.Vec3 now = p.position();
		if (lastProgressPos == null || now.distanceToSqr(lastProgressPos) > 0.02) {
			lastProgressPos = now;
			lastNodeTime = System.currentTimeMillis();
		}

		// Cancel creative flight.
		//
		// In creative a double-tap of jump starts flying, and the walker holds jump on and off
		// as terrain demands — so the bot would take off, drift over the maze instead of
		// through it, and land somewhere unrelated. Flying is never what "walk there" means.
		if (p.getAbilities().flying) {
			p.getAbilities().flying = false;
			p.onUpdateAbilities();
			net.minecraft.world.phys.Vec3 v = p.getDeltaMovement();
			p.setDeltaMovement(v.x, 0, v.z);
		}

		if (path.isEmpty()) {
			if (atGoal(p)) {
				releaseKeys();
				return Status.ARRIVED;
			}
			if (!replan(p)) {
				releaseKeys();
				return Status.FAILED;
			}
			if (path.isEmpty()) {
				releaseKeys();
				return Status.RUNNING; // incremental search continues next client tick
			}
		}

		Move next = path.get(0);

		// ------------------------------------------------------------------
		// edits first: the route is only walkable once these are done
		// ------------------------------------------------------------------
		if (digging || !next.toBreak.isEmpty()) {
			if (!digging) {
				if (!p.onGround()) {
					return Status.RUNNING; // must be standing to start a dig
				}
				digging = true;
				digPos = next.toBreak.remove(0);
				digStarted = false;
				releaseKeys();
			}
			if (digTick()) {
				digging = false;
				digPos = null;
				lastNodeTime = System.currentTimeMillis();
			}
			return Status.RUNNING;
		}

		if (placing || !next.toPlace.isEmpty()) {
			if (!placing) {
				placing = true;
				placingBlock = next.toPlace.remove(0);
				releaseKeys();
			}
			if (placeTick()) {
				placing = false;
				placingBlock = null;
				lastNodeTime = System.currentTimeMillis();
			}
			return Status.RUNNING;
		}

		// ------------------------------------------------------------------
		// movement
		// ------------------------------------------------------------------
		double dx = next.x + 0.5 - p.getX();
		double dy = next.y - p.getY();
		double dz = next.z + 0.5 - p.getZ();

		// Advance to the next node when the body is AT or PAST it, not only when it is
		// centred on it.
		//
		// The test used to demand being within NODE_EPS (0.6) of the node's centre. Walking
		// bodies overshoot, get nudged by collisions, and drift along walls, so a node could be
		// passed without ever satisfying that radius — and the bot would stop dead, refusing to
		// go on until it happened to be standing on the marker again. On screen that reads as
		// "it will not move unless it is standing exactly on the yellow square".
		//
		// The honest test is whether the node is BEHIND us along the route: closer than the
		// next one, or simply within a generous radius. Overshooting is progress, not failure.
		double here2 = Math.hypot(p.getX() - (next.x + 0.5), p.getZ() - (next.z + 0.5));
		boolean atNode = here2 <= NODE_EPS && Math.abs(dy) < 0.9
				&& (!next.parkour || p.onGround());
		if (!atNode && path.size() > 1) {
			// Passed it: the following node is nearer than this one, and we are close to it.
			Move after = path.get(1);
			double toAfter = Math.hypot(p.getX() - (after.x + 0.5), p.getZ() - (after.z + 0.5));
			boolean edited = !next.toBreak.isEmpty() || !next.toPlace.isEmpty()
					|| !after.toBreak.isEmpty() || !after.toPlace.isEmpty();
			if (!edited && toAfter < here2 && toAfter <= 1.6
					&& Math.abs(p.getY() - after.y) < 1.2) {
				atNode = true;
			}
		}

		if (atNode) {
			lastNodeTime = System.currentTimeMillis();
			launchCell = path.get(0); // where the route expected the body to stand
			path.remove(0);
			if (path.isEmpty()) {
				releaseKeys();
				if (atGoal(p)) {
					return Status.ARRIVED;
				}
				if (!pathComplete) {
					failure = "仅走到可达边界 " + p.blockPosition() + "，距离目标仍有 "
							+ String.format("%.1f", goal.distanceTo(here(p))) + " 格";
					return Status.FAILED;
				}
				// An altered world or a shortcut may have left us short: search again.
				return Status.RUNNING;
			}
			next = path.get(0);
			if (!next.toBreak.isEmpty() || !next.toPlace.isEmpty()) {
				releaseKeys();
				return Status.RUNNING;
			}
			dx = next.x + 0.5 - p.getX();
			dz = next.z + 0.5 - p.getZ();
		}

		// stuck: the route no longer matches the world, so rebuild it
		if (System.currentTimeMillis() - lastNodeTime > STUCK_MS) {
			lastNodeTime = System.currentTimeMillis();
			// Record WHY we are stuck before throwing the route away.
			//
			// Re-planning alone is not enough, and this is the defect the user kept hitting:
			// the walker knew perfectly well it could not take the step, threw the route away,
			// and the search — given no information about the failure — returned the identical
			// route, leap and all. The bot then walked the same broken path again, and again,
			// until the re-plan budget ran out. On screen: "it knows it is stuck and still
			// walks the broken route".
			//
			// Forbidding the transition is the feedback that makes the search choose the side
			// path instead. Without it the planner is not re-planning at all; it is replaying.
			if (!path.isEmpty()) {
				forbidCurrentStep(p, path.get(0));
			}
			path = new ArrayList<>();
			search = null;
			// Re-planning is a normal, cheap correction — not a failure.
			//
			// The counter used to accumulate over the whole walk and abandon the trip at 40.
			// On a long route that is nothing: a few corner re-plans, a door, a drifting body,
			// and the bot gives up on a path that was perfectly walkable, mid-maze, for no
			// visible reason. What matters is whether the trip is still PROGRESSING, so the
			// budget resets whenever the body actually moves. Baritone likewise re-plans freely
			// and only stops when the goal is unreachable, not when it has retried enough.
			if (progressSinceReplan()) {
				replans = 0;
			}
			replans++;
			if (replans > MAX_REPLANS) {
				failure = "连续 " + replans + " 次重规划都没有前进，卡在 " + p.blockPosition();
				releaseKeys();
				return Status.FAILED;
			}
			return Status.RUNNING;
		}

		// Turn through the player's own rotation API, not the raw field.
		//
		// setYRot writes a field the client recomputes from input every tick, so the turn is
		// discarded before it can affect movement: the bot faces one way, walks another, and
		// never reaches the node — which showed up as circling on the spot and then stopping.
		// absSnapRotationTo is the call the client itself uses, and it sticks.
		// ------------------------------------------------------------------
		// movement: pick a mode for this node and COMMIT to it
		// ------------------------------------------------------------------
		//
		// Re-deciding the movement mode every tick is what made the walk stutter, stop at
		// corners, and fail to climb: a predicate that is momentarily false — the body a few
		// centimetres off centre, mid-jump, holding a rung between cells — released the keys,
		// and the next tick re-evaluated from a worse position. Baritone likewise chooses a
		// movement and drives it to completion.
		//
		// The decision is therefore re-made only when one of its INPUTS changes, and there are
		// two of those — not one:
		//
		//   * the node, obviously; and
		//   * the cell the body is standing in.
		//
		// The second was missing, and it is the same defect in a new costume. Several
		// predicates ask about the body's own cell: whether it is on a ladder, whether the
		// ground under it is solid. A node becomes current while the body is still in the
		// PREVIOUS cell — the anchor consumes nodes from a radius that reaches into it — so the
		// verdict is reached from the wrong cell and then frozen. Concretely: the route's next
		// node was the first rung of a ladder shaft, the body was still one cell short on plain
		// floor, so "is the body on a ladder" was false, every mode failed, and NONE latched.
		// The body then walked the last few centimetres into the shaft, where the climb was
		// available all along, and the stored NONE kept it standing there.
		//
		// Re-deciding on a cell change costs one decision per cell walked — not one per tick —
		// so the stutter the latch prevents cannot come back.
		BlockPos bodyCell = p.blockPosition();
		if (committed == null || committedNode != next || !bodyCell.equals(committedCell)) {
			Mode chosen = chooseMovement(p, next);
			if (committed != null && chosen != committed) {
				// A mode that changes without the node changing is the body oscillating: it
				// takes a step, the step stops being legal from the new cell, it steps back.
				// Counted so the periodic debug line can show it happening.
				modeChanges++;
			}
			committed = chosen;
			committedNode = next;
			committedCell = bodyCell;
			commitTicks = 0;
			// What the chosen movement is FOR. Usually the route's next node; when the body has
			// not yet reached the cell the step starts from, it is that cell instead, and the
			// driver must steer there rather than at a node it cannot legally approach yet.
			aim = aimedAtLaunchCell ? launchCell : next;
			if (committed != Mode.NONE) {
				// The previous failure no longer applies — this step is being taken.
				//
				// The diagnostic used to be set when a step failed and never cleared, so the
				// one-second debug line kept printing an old failure while the bot was walking
				// along perfectly well. That is worse than no diagnostic: it sends whoever is
				// reading the log to the wrong cell. (It did.)
				lastDiag = "";
			}
		}
		commitTicks++;
		Move goalNode = aim != null ? aim : next;

		// While climbing, keep the keys exactly as the climb needs them and do not let a
		// transient predicate failure interrupt the ascent.
		if (committed == Mode.CLIMB) {
			driveClimb(p, goalNode);
			return Status.RUNNING;
		}

		dx = goalNode.x + 0.5 - p.getX();
		dz = goalNode.z + 0.5 - p.getZ();
		float idealYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		// Rub past a corner rather than jamming against it.
		//
		// The ideal heading points at the destination's centre, which at a corner runs the
		// body's own 0.6-wide box into the block diagonally beside it. Held straight, that is a
		// walker pressed against a wall face making no progress — the "碰到墙壁就动不了了" the
		// user sees. Baritone aims slightly OFF the ideal (moveTowardsWithSlightRotation) so
		// the face redirects the motion and the body slides around the corner; the same nudge
		// here, applied only while actually colliding so ordinary walking stays straight.
		float yaw = idealYaw;
		if (committed == Mode.WALK && physics.isRubbing(p)) {
			yaw = Physics.pickRubYaw(idealYaw,
					physics.rubSideOpen(p, idealYaw, true),
					physics.rubSideOpen(p, idealYaw, false),
					p.getYRot());
		}
		p.absSnapRotationTo(yaw, 0.0f);
		mc.options.keyDown.setDown(false);
		mc.options.keyLeft.setDown(false);
		mc.options.keyRight.setDown(false);
		mc.options.keyShift.setDown(false);

		switch (committed) {
			case WALK -> {
				mc.options.keyUp.setDown(true);
				mc.options.keyJump.setDown(false);
				mc.options.keySprint.setDown(Math.hypot(dx, dz) > 2.0);
			}
			case JUMP -> {
				// An ascend, not a standing jump.
				//
				// Holding jump and forward unconditionally is wrong under a ceiling: the body
				// hops in place against the block above and never travels. Baritone instead
				// walks toward the step and presses jump only once it is close and its head is
				// clear (MovementAscend.updateState), which is what lets it start the jump from
				// the open cell in front of the step rather than from wherever the node
				// happened to come into range. Forward is held the whole time, so the body is
				// already moving when the jump goes in and carries across onto the ledge.
				mc.options.keyUp.setDown(true);
				double toNode = Math.hypot(goalNode.x + 0.5 - p.getX(), goalNode.z + 0.5 - p.getZ());
				mc.options.keyJump.setDown(Physics.jumpAllowedNow(
						p.onGround(), physics.canJumpNow(p), toNode, p.horizontalCollision));
				mc.options.keySprint.setDown(false);
			}
			case SPRINT_JUMP -> {
				// A gap leap: sprint from the launch cell and leave the ground AT the lip.
				//
				// Two things must be true before jump goes in, and both are Baritone's rule
				// (MovementParkour.updateState):
				//
				// 1. The body must be standing in the cell the leap starts from. The anchor
				//    consumes nodes from a 0.75 radius, so this node became current while the
				//    body was still inside the PREVIOUS cell — and from there the leap is not
				//    merely mistimed, it is impossible: that cell's ceiling caps the jump. So
				//    when the body is short of the launch cell, steer BACK to it first.
				// 2. The jump fires at the lip, not from a standstill and not after walking off
				//    the edge. While the cell ahead is walkable, keep sprinting to build speed;
				//    the moment the floor ahead at the body's own level runs out, jump.
				BlockPos cell = p.blockPosition();
				if (launchCell != null
						&& (cell.getX() != launchCell.x || cell.getZ() != launchCell.z)) {
					double bx = launchCell.x + 0.5;
					double bz = launchCell.z + 0.5;
					float backYaw = (float) (Math.toDegrees(Math.atan2(bz - p.getZ(), bx - p.getX())) - 90.0);
					p.absSnapRotationTo(backYaw, 0.0f);
					mc.options.keyUp.setDown(true);
					mc.options.keySprint.setDown(false);
					mc.options.keyJump.setDown(false);
					return Status.RUNNING;
				}
				mc.options.keyUp.setDown(true);
				mc.options.keySprint.setDown(true);
				int ddx = Integer.signum(next.x - cell.getX());
				int ddz = Integer.signum(next.z - cell.getZ());
				boolean atLip = (ddx != 0 || ddz != 0)
						&& !physics.standable(cell.getX() + ddx, cell.getY(), cell.getZ() + ddz);
				mc.options.keyJump.setDown(p.onGround() && physics.canJumpNow(p) && atLip);
			}
			case DROP -> {
				mc.options.keyUp.setDown(true);
				mc.options.keyJump.setDown(false);
				mc.options.keySprint.setDown(false);
			}
			case SWIM -> {
				// In water, hold jump CONTINUOUSLY.
				//
				// Jump is the only thing keeping the body at the surface: release it and the
				// player sinks. Making it conditional ("rise only if the next node is higher")
				// looked reasonable and was wrong — the route's next node is often *inside*
				// the water column, so the condition was false, the bot sank to the pool floor,
				// and the route ended at "reachable boundary" while it sat on the bottom.
				// A player crossing water holds jump the whole way; so does this.
				double aimX = goalNode.x + 0.5;
				double aimZ = goalNode.z + 0.5;
				float swimYaw = (float) (Math.toDegrees(Math.atan2(aimZ - p.getZ(), aimX - p.getX())) - 90.0);
				p.absSnapRotationTo(swimYaw, 0.0f);
				mc.options.keyUp.setDown(true);
				mc.options.keyJump.setDown(true);
				mc.options.keySprint.setDown(false);
			}
			case PUSH -> {
				// Steer at the node and let the game resolve the contact — what a mob's
				// navigator does, and the reason a zombie walks terrain this walker's catalogue
				// of named moves has no entry for.
				//
				// The jump is the important half. A mob jumps whenever it is on the ground and
				// the node it is steering at is above it; that single rule carries it up
				// staircases, corner steps, slabs and stair blocks without knowing what any of
				// them are. The same rule here, gated only by headroom (a jump under a ceiling
				// is a wasted hop) — and forward stays held throughout, so the body is moving
				// when the jump goes in and lands ON the step instead of against its face.
				mc.options.keyUp.setDown(true);
				boolean wantsUp = goalNode.y > p.getY() + 0.4;
				double toNode = Math.hypot(goalNode.x + 0.5 - p.getX(), goalNode.z + 0.5 - p.getZ());
				mc.options.keyJump.setDown(Physics.jumpAllowedNow(
						p.onGround(), physics.canJumpNow(p), toNode, wantsUp || p.horizontalCollision));
				mc.options.keySprint.setDown(false);
			}
			case NONE -> {
				// Genuinely nothing to do. Record the transition as impossible before
				// re-planning, so the next search cannot return the same dead move: that
				// feedback is the difference between "re-plans into the identical route
				// forever" and "finds another way".
				mc.options.keyUp.setDown(false);
				mc.options.keyJump.setDown(false);
				mc.options.keySprint.setDown(false);
				lastDiag = "noStep from " + String.format("%.2f,%.2f,%.2f", p.getX(), p.getY(), p.getZ())
						+ " to " + next.x + "," + next.y + "," + next.z
						+ " launch=" + (launchCell == null ? "-"
								: launchCell.x + "," + launchCell.y + "," + launchCell.z)
						+ " onGround=" + p.onGround()
						+ " climbable=" + physics.isOnClimbable(p);
				forbidCurrentStep(p, next);
				noStepTicks++;
				if (noStepTicks >= 3) {
					noStepTicks = 0;
					committed = null;
					path = new ArrayList<>();
					search = null;
					// A blocked step is worth re-planning, but only counts against the budget
					// while the trip is going nowhere. See the stuck branch above.
					if (progressSinceReplan()) {
						replans = 0;
					}
					replans++;
					if (replans > MAX_REPLANS) {
						failure = "连续 " + replans + " 次重规划都无法迈出下一步，卡在 " + p.blockPosition();
						return Status.FAILED;
					}
				}
			}
		}
		noStepTicks = 0;
		return Status.RUNNING;
	}

	/**
	 * Records that the route's current step cannot be walked, so the search routes around it.
	 *
	 * <p>Two keys are recorded, and the distinction is the whole point. The route's own step —
	 * from the cell the route expected the body to stand in ({@link #launchCell}) to the node —
	 * is the key the search actually tests when it expands that cell. The body's current cell
	 * is recorded too, because that is where the next search will start from.
	 *
	 * <p>Recording only the body's cell was a real bug: a body standing at the edge of cell A
	 * while the route's step is B→C forbids A→C, which the search never asks about, so it
	 * returned the identical route and the bot stood still until the re-plan budget ran out.
	 * That is the "it knows it is stuck and still walks the broken path" behaviour.
	 */
	private void forbidCurrentStep(LocalPlayer p, Move next) {
		Move from = here(p);
		forbidden.add(Movements.moveKey(from, next));
		forbidden.add(Movements.moveKey(next, from));
		if (launchCell != null && (launchCell.x != from.x || launchCell.y != from.y || launchCell.z != from.z)) {
			// The step as the ROUTE defines it: this is the one the search will re-offer.
			forbidden.add(Movements.moveKey(launchCell, next));
			forbidden.add(Movements.moveKey(next, launchCell));
		}
	}

	/** How the walker will travel to the current node. */
	private enum Mode { WALK, JUMP, SPRINT_JUMP, DROP, SWIM, CLIMB, PUSH, NONE }

	/**
	 * Chooses the movement for one node.
	 *
	 * <p>Ordered from the most ordinary to the most special, so a plain walk is never upgraded
	 * to a jump it does not need. Ladder handling comes first because a climb is the one case
	 * where the ordinary predicates are all false yet the move is obviously correct.
	 */
	private Mode chooseMovement(LocalPlayer p, Move next) {
		aimedAtLaunchCell = false;
		Mode direct = movementFor(p, next);
		if (direct != Mode.NONE) {
			return direct;
		}
		// The route's step is (launchCell -> next), and this walker judges steps from where the
		// body actually stands. Those differ whenever the body has not yet reached launchCell —
		// which is routine, because the anchor's 0.75 radius is wider than half a cell, so it
		// consumes two nodes at once near a boundary and leaves launchCell one cell AHEAD.
		//
		// Judged from the wrong cell, a perfectly ordinary step reads as something else
		// entirely. The concrete case: the route stepped down from (225,106,450) to
		// (226,105,450) — a cardinal step down. The body was still in (225,106,451), from where
		// the same node is a DIAGONAL down, and that diagonal is blocked by the solid corner at
		// (226,106,451). Every mode failed, the walker reported noStep, and the bot stopped one
		// cell short of a step it could take.
		//
		// Baritone has this built in: a Movement carries its {@code src}, and the player is
		// steered to {@code src} when not already there. Same rule here — walk to the cell the
		// step starts from, then take it.
		//
		// Only when that is FORWARD progress. The fallback reverses the body whenever the step
		// stops being legal from the new cell, and re-entering the old cell makes it legal
		// again — a body shivering back and forth at the foot of a step. So the launch cell is
		// used only while it lies between the body and the node; if it is behind the body, it
		// is not the step's start any more, it is where the body came from.
		if (launchCell != null && !sameCell(launchCell, p.blockPosition())
				&& cellDistanceSq(launchCell, next) <= cellDistanceSq(p.blockPosition(), next)) {
			Mode toLaunch = movementFor(p, launchCell);
			if (toLaunch != Mode.NONE) {
				aimedAtLaunchCell = true;
				return toLaunch;
			}
		}
		return Mode.NONE;
	}

	/** Squared cell-to-cell distance, for comparing which of two cells is nearer a node. */
	private static double cellDistanceSq(Move a, Move b) {
		double dx = a.x - b.x;
		double dy = a.y - b.y;
		double dz = a.z - b.z;
		return dx * dx + dy * dy + dz * dz;
	}

	private static double cellDistanceSq(BlockPos a, Move b) {
		double dx = a.getX() - b.x;
		double dy = a.getY() - b.y;
		double dz = a.getZ() - b.z;
		return dx * dx + dy * dy + dz * dz;
	}

	/** The movement that would take the body to this node from where it stands now. */
	private Mode movementFor(LocalPlayer p, Move node) {
		if (physics.isOnClimbable(p) || physics.isClimb(node)) {
			return Mode.CLIMB;
		}
		if (p.isInWater()) {
			return Mode.SWIM;
		}
		if (physics.canFallIntoWater(p, node)) {
			return Mode.DROP;
		}
		if (physics.canStraightLine(p, node)) {
			return Mode.WALK;
		}
		if (physics.canWalkDown(p, node)) {
			return Mode.DROP;
		}
		if (physics.canAscend(p, node)) {
			// Up and across at once: jump, and hold forward so the body travels sideways too.
			// Driven as an ascend, not a plain jump — see the JUMP case below.
			return Mode.JUMP;
		}
		if (physics.canSprintJump(p, node)) {
			return Mode.SPRINT_JUMP;
		}
		if (physics.canRideOut(p, node)) {
			// Airborne mid-step: hold the course. Releasing here is what makes the bot stutter
			// at a one-block step instead of finishing it — see Physics#canRideOut.
			return Mode.WALK;
		}
		if (physics.canSlideTo(p, node)) {
			return Mode.PUSH;
		}
		if (physics.canSteerToward(p, node)) {
			// Nothing precise fits, but the node is an ordinary place to stand one step away.
			// Steer at it and let the game resolve the contact, the way a mob's navigator does;
			// the stall detector is what notices if that genuinely goes nowhere.
			return Mode.PUSH;
		}
		return Mode.NONE;
	}

	/** True when a route node and a body cell are the same cell. */
	private static boolean sameCell(Move node, BlockPos cell) {
		return node.x == cell.getX() && node.y == cell.getY() && node.z == cell.getZ();
	}

	/**
	 * Drives a ladder climb for as long as the node is a ladder node.
	 *
	 * <p>Held deliberately: the rungs are climbed by holding jump (up) or sneak (down) with
	 * the body against the ladder, and releasing between ticks drops the player back down. The
	 * face is turned into the ladder so "forward" would not walk off it.
	 */
	private void driveClimb(LocalPlayer p, Move next) {
		BlockPos here = p.blockPosition();
		boolean sameColumn = next.x == here.getX() && next.z == here.getZ();
		double dx = next.x + 0.5 - p.getX();
		double dz = next.z + 0.5 - p.getZ();
		// Face along the shaft so the body stays against the rungs.
		p.absSnapRotationTo((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0), 0.0f);
		mc.options.keySprint.setDown(false);
		mc.options.keyShift.setDown(false);
		if (sameColumn) {
			boolean up = next.y > p.getY() + 0.05;
			// Hold jump to rise; hold sneak to descend. Forward stays released: walking
			// forward on a ladder steps off it.
			mc.options.keyUp.setDown(false);
			mc.options.keyJump.setDown(up);
			mc.options.keyShift.setDown(!up);
			return;
		}
		// Boarding the ladder from the floor, or stepping off at the top: move horizontally.
		mc.options.keyUp.setDown(true);
		mc.options.keyJump.setDown(next.y > p.getY() + 0.05);
	}

	/**
	 * Steers the body toward the centre of the cell it occupies.
	 *
	 * <p>Kept for callers that need a deliberate recentring step. Note this is NOT used as a
	 * stall recovery any more: walking the body backwards to a cell centre was papering over
	 * a routing defect, and looked like the bot changing its mind mid-route.
	 */
	@SuppressWarnings("unused")
	private void nudgeToCellCentre(LocalPlayer p) {
		BlockPos cell = p.blockPosition();
		double cx = cell.getX() + 0.5;
		double cz = cell.getZ() + 0.5;
		double dx = cx - p.getX();
		double dz = cz - p.getZ();
		if (Math.hypot(dx, dz) < 0.08) {
			return; // already centred; the obstacle is real, not an overlap
		}
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		p.absSnapRotationTo(yaw, 0.0f);
		mc.options.keyUp.setDown(true);
		mc.options.keyJump.setDown(false);
		mc.options.keySprint.setDown(false);
		// Creep rather than stride: this is a correction of a few centimetres.
		mc.options.keyDown.setDown(false);
	}

	// ------------------------------------------------------------------
	// route maintenance
	// ------------------------------------------------------------------

	/**
	 * Drops nodes the player has genuinely reached, from the front of the route.
	 *
	 * <p>Deliberately conservative. The ported version re-anchored to the <em>closest</em>
	 * node anywhere in the route, which can skip the player forward past a corner they have
	 * not actually rounded: standing at (46,211) with the route running (47,211) then
	 * (47,212), "closest" can select (47,212) and drop the intermediate node — leaving a
	 * first step that is a diagonal through the solid block at (46,212). Every movement check
	 * then fails, the bot jitters, and it re-plans into the same route.
	 *
	 * <p>Only nodes within {@link #REACHED_EPS} of the body are consumed, and only from the
	 * front, so the route can never begin somewhere the player cannot legally step to.
	 */
	private void anchorPathToPlayer(LocalPlayer p) {
		if (path.size() < 2) {
			return;
		}
		int reached = 0;
		for (int i = 0; i < path.size(); i++) {
			Move n = path.get(i);
			if (!n.toBreak.isEmpty() || !n.toPlace.isEmpty()) {
				break; // never skip past an edit
			}
			if (p.position().distanceToSqr(n.x + 0.5, n.y, n.z + 0.5) > REACHED_EPS * REACHED_EPS) {
				break; // stop at the first node not yet reached: do not skip ahead
			}
			reached = i + 1;
		}
		// Always leave at least one node, so the route never becomes empty here.
		int drop = Math.min(reached, path.size() - 1);
		if (drop > 0) {
			// Remember the last node consumed: it is the cell the NEXT move starts from. For a
			// parkour leap that is the ledge the body must jump off, and it is not necessarily
			// the cell the body is standing in — the anchor's radius reaches into the previous
			// cell. See the SPRINT_JUMP driver.
			launchCell = path.get(drop - 1);
			path.subList(0, drop).clear();
		}
		// NOTE: do not "validate" the new first node here and clear the route when it looks
		// unreachable. That was tried and it is destructive: a route's next waypoint is often
		// several cells away (shortening collapses runs into single waypoints), so a check
		// meant for one-step reachability fails constantly, wipes the route every tick, and
		// restarts the search each time — the replan budget is gone in about two seconds and
		// the bot gives up mid-maze with the drawn path vanishing. Because only genuinely
		// reached nodes are dropped above, the node left at the front is already the next one
		// the walker was heading for; the stuck timer and the no-step counter are the right
		// places to notice when it cannot get there.
	}

	/**
	 * How many blocks the player can actually place, which caps bridging and towering.
	 *
	 * <p>Hard-coding this is not a harmless simplification. With a phantom stock of 64, the
	 * search happily plans a tower up a four-block wall and a walk along its top — a shorter
	 * route than the corridors. The bot then walks to the wall, finds nothing to place, and
	 * jams in the corner. The count has to be the real one, so the search only plans moves the
	 * player can carry out.
	 */
	private int scaffoldCount() {
		LocalPlayer p = mc.player;
		if (p == null) {
			return 0;
		}
		int n = 0;
		for (int i = 0; i < 36; i++) {
			var stack = p.getInventory().getItem(i);
			if (!stack.isEmpty() && stack.getItem() instanceof net.minecraft.world.item.BlockItem) {
				n += stack.getCount();
			}
		}
		return n;
	}

	private Move here(LocalPlayer p) {
		BlockPos pos = p.blockPosition();
		return new Move(pos.getX(), pos.getY(), pos.getZ(), 0, 0);
	}

	/**
	 * True when the body has moved meaningfully since the last re-plan.
	 *
	 * <p>This is what separates "re-planning because the route needed correcting" from
	 * "re-planning because we are genuinely trapped". The first is normal and must not exhaust
	 * a budget; the second must eventually give up rather than loop forever.
	 */
	private boolean progressSinceReplan() {
		LocalPlayer p = mc.player;
		if (p == null) {
			return false;
		}
		if (replanPos == null) {
			replanPos = p.position();
			return false;
		}
		boolean moved = p.position().distanceToSqr(replanPos) > 1.0;
		replanPos = p.position();
		return moved;
	}

	private boolean atGoal(LocalPlayer p) {
		return goal.isEnd(here(p));
	}

	/** Re-runs the search from where the player is now. */
	private boolean replan(LocalPlayer p) {
		if (target == null) {
			return false;
		}
		if (search == null) {
			// Same rule as the stuck branches: a re-plan only counts against the budget while
			// the trip is making no headway. Otherwise a long walk exhausts the counter on
			// harmless corrections and abandons a route it could have finished.
			if (progressSinceReplan()) {
				replans = 0;
			}
			replans++;
			if (replans > MAX_REPLANS) {
				failure = "连续 " + replans + " 次重规划都无路可走，卡在 " + p.blockPosition();
				return false;
			}
			search = new AStar.Search(mc.level, p.blockPosition(), goal, 120000,
					scaffoldCount(), canDig, forbidden);
		}
		AStar.Result r = search.advance(SEARCH_NODES_PER_TICK);
		if (r == null) {
			return true;
		}
		search = null;
		if (r.path.isEmpty()) {
			failure = "找不到通往目标的路径（" + p.blockPosition() + " → " + target + "）";
			return false;
		}
		pathComplete = r.complete;
		// Follow the search's own cell-by-cell route; do NOT shorten it first.
		//
		// <p>Shortening merges consecutive cells into distant waypoints and validates each
		// merge with a straight-line test. That test answers "is the line between these two
		// cell centres clear", which is a different question from "can the walker take this
		// step" — and every movement check below is a ONE-CELL check. So a merged waypoint
		// around a bend is unreachable by all four checks at once: the bot stops at the
		// corner, the route is dropped, it re-plans, and the search returns the identical
		// route. That is the pause at every corner, and the path visibly disappearing.
		//
		// <p>Cell-by-cell also matches what the walker is: it steers toward one node, and
		// re-anchors every tick, so a node per cell is smooth rather than jittery — the
		// jitter that shortening was meant to prevent came from a stale anchor, not from
		// having intermediate nodes.
		path = new ArrayList<>(r.path);
		lastNodeTime = System.currentTimeMillis();
		return true;
	}

	// ------------------------------------------------------------------
	// digging and placing
	// ------------------------------------------------------------------

	private BlockPos placingBlock;

	/** Mines the current target block; true once it is gone. */
	private boolean digTick() {
		LocalPlayer p = mc.player;
		if (digPos == null) {
			return true;
		}
		BlockState st = mc.level.getBlockState(digPos);
		if (st.isAir() || st.getCollisionShape(mc.level, digPos).isEmpty()) {
			return true;
		}
		Vec3 center = Vec3.atCenterOf(digPos);
		if (p.getEyePosition().distanceTo(center) > 5.0) {
			return true; // out of reach; the route will be re-planned
		}
		releaseKeys();
		face(center);
		if (!digStarted) {
			autoTool(st);
			digStarted = true;
		}
		Direction side = Direction.getNearest(
				(int) Math.round((p.getEyePosition().x - center.x) * 2),
				(int) Math.round((p.getEyePosition().y - center.y) * 2),
				(int) Math.round((p.getEyePosition().z - center.z) * 2), Direction.UP);
		if (!mc.gameMode.continueDestroyBlock(digPos, side) && !mc.gameMode.isDestroying()) {
			mc.gameMode.startDestroyBlock(digPos, side);
		}
		p.swing(InteractionHand.MAIN_HAND);
		return mc.level.getBlockState(digPos).isAir();
	}

	/** Places the current target block; true once a placement was carried out. */
	private boolean placeTick() {
		LocalPlayer p = mc.player;
		if (placingBlock == null) {
			return true;
		}
		BlockState cur = mc.level.getBlockState(placingBlock);
		if (!cur.canBeReplaced()) {
			return true; // already occupied
		}
		Vec3 eye = p.getEyePosition();
		double best = Double.MAX_VALUE;
		Direction bestFace = null;
		BlockPos bestRef = null;
		Vec3 bestHit = null;
		for (Direction d : Direction.values()) {
			BlockPos ref = placingBlock.relative(d);
			if (mc.level.getBlockState(ref).getCollisionShape(mc.level, ref).isEmpty()) {
				continue;
			}
			Vec3 hit = Vec3.atCenterOf(ref).add(d.getStepX() * 0.5, d.getStepY() * 0.5, d.getStepZ() * 0.5);
			double dist = eye.distanceTo(hit);
			if (dist > 4.5 || dist >= best) {
				continue;
			}
			best = dist;
			bestFace = d;
			bestRef = ref;
			bestHit = hit;
		}
		if (bestFace == null) {
			return true; // nothing to attach to; re-plan instead of flailing
		}
		releaseKeys();
		face(bestHit);
		p.setShiftKeyDown(true);
		mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
				new BlockHitResult(bestHit, bestFace.getOpposite(), bestRef, false));
		p.swing(InteractionHand.MAIN_HAND);
		p.setShiftKeyDown(false);
		return true;
	}

	/** Picks the fastest tool in the hotbar for this block, like the original's bestHarvestTool. */
	private void autoTool(BlockState st) {
		LocalPlayer p = mc.player;
		if (p == null) {
			return;
		}
		float bestSpeed = 1.0f;
		int bestSlot = -1;
		for (int i = 0; i < 9; i++) {
			var stack = p.getInventory().getItem(i);
			if (stack.isEmpty()) {
				continue;
			}
			float speed = stack.getDestroySpeed(st);
			if (speed > bestSpeed) {
				bestSpeed = speed;
				bestSlot = i;
			}
		}
		if (bestSlot >= 0) {
			p.getInventory().setSelectedSlot(bestSlot);
		}
	}

	private void face(Vec3 point) {
		LocalPlayer p = mc.player;
		Vec3 eye = p.getEyePosition();
		double dx = point.x - eye.x;
		double dy = point.y - eye.y;
		double dz = point.z - eye.z;
		double horiz = Math.hypot(dx, dz);
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horiz)));
		// same reason as the walk steering: the raw setters get overwritten by client input
		p.absSnapRotationTo(yaw, pitch);
	}

	private void releaseKeys() {
		mc.options.keyUp.setDown(false);
		mc.options.keyDown.setDown(false);
		mc.options.keyLeft.setDown(false);
		mc.options.keyRight.setDown(false);
		mc.options.keyJump.setDown(false);
		mc.options.keySprint.setDown(false);
	}
}
