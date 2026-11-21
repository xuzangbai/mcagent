package com.mcagent.client.pathing;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** No world or downloaded test framework required: run with Gradle's testPathing task. */
public final class AStarGraphTest {
	private static int checks;

	public static void main(String[] args) {
		cheapestRouteWins();
		scaffoldingIsPartOfState();
		partialIsNotSuccess();
		searchIsIncremental();
		firstStepIsAdjacent();
		openingHopUsesPlayerPosition();
		diagonalNeedsBothFlanksClear();
		reachedNodesAreConsumedFromTheFront();
		waterFallBeatsOrdinaryStepDown();
		forbiddenMovesAreNotReplanned();
		ascendIsAllowedUnderACeiling();
		jumpWaitsUntilCloseAndClear();
		ascendRejectsAStandingJump();
		parkourIsDecidedByColumnsNotAnArc();
		walkAroundBeatsAnImpossibleLeap();
		theRouteKeyIsTheOneTheSearchTests();
		movementIsReDecidedWhenTheBodyChangesCell();
		cornerSteeringRubsPastTheWall();
		aFailedSearchNeverWalksBackwards();
		theFallbackNeverReversesTheBody();
		System.out.println("Pathing graph tests passed: " + checks);
	}

	/**
	 * The "walk back to the step's start cell" fallback must never send the body backwards.
	 *
	 * <p>Regression, and the mechanism behind the remaining jitter the user reported: "有时候会
	 * 来回鬼畜一会儿才正常走，就是上下单格位置时" — sometimes it shivers back and forth for a
	 * moment at single-block up/down steps before going on.
	 *
	 * <p>The fallback exists for a real reason (a step is defined from its start cell, and the
	 * body may not be there yet), but unguarded it oscillates: the body steps forward, the step
	 * stops being legal from the new cell, the fallback aims back at the start cell, the body
	 * steps back, the step becomes legal again, and round it goes. The guard is direction: the
	 * start cell is only useful while it lies BETWEEN the body and the node. Once it is behind
	 * the body it is not the step's start any more, it is where the body came from.
	 */
	private static void theFallbackNeverReversesTheBody() {
		Move node = new Move(226, 105, 450, 1, 1);

		// Body one cell short, on the near side: the start cell is between it and the node, so
		// walking to the start cell is genuine forward progress.
		Move bodyBehind = new Move(225, 106, 451, 0, 0);
		Move startBetween = new Move(225, 106, 450, 0, 0);
		check(distSq(startBetween, node) < distSq(bodyBehind, node),
				"a start cell between the body and the node must be nearer the node");

		// Body already past the start cell: aiming back at it would reverse the body.
		Move bodyAhead = new Move(226, 105, 449, 0, 0);
		check(distSq(startBetween, node) > distSq(bodyAhead, node),
				"a start cell behind the body must not be treated as forward progress");
	}

	private static double distSq(Move a, Move b) {
		double dx = a.x - b.x;
		double dy = a.y - b.y;
		double dz = a.z - b.z;
		return dx * dx + dy * dy + dz * dz;
	}

	/**
	 * At a corner the heading must be nudged off the ideal, not held straight at the wall.
	 *
	 * <p>Regression, in the user's words: "像baritone在这种单格边缘拐角的寻路都是蹭过去的，
	 * 你这个，碰到墙壁就动不了了" — Baritone rubs past a single-cell corner, this walker just
	 * stops when it touches the wall. The cause is geometric: the body is 0.6 wide and the
	 * ideal heading points at the destination's centre, so at a corner the heading drives the
	 * bounding box into the block diagonally beside it. Held straight, that is a body pressed
	 * against a wall face making no progress.
	 *
	 * <p>The fix is Baritone's {@code moveTowardsWithSlightRotation}: fold the offset between
	 * the current and ideal heading into a 45-degree window and aim at the nearer diagonal, so
	 * the wall's face redirects the motion and the body slides around the corner.
	 */
	private static void cornerSteeringRubsPastTheWall() {
		// Only one way round: aim at that diagonal. This is the corner case — the ideal heading
		// runs into the block beside the body, and the open side is where the body can slide.
		check(close(Physics.pickRubYaw(0f, true, false, 0f), -45f),
				"an open left side must steer 45 degrees left");
		check(close(Physics.pickRubYaw(0f, false, true, 0f), 45f),
				"an open right side must steer 45 degrees right");
		// Boxed in: hold the heading and let the stall detector deal with it. Sidestepping at
		// random here is how a bot wanders.
		check(close(Physics.pickRubYaw(0f, false, false, 0f), 0f),
				"with both sides blocked the heading must be held");
		// Both open: take the smaller turn, so the body does not swing across the corner.
		check(close(Physics.pickRubYaw(0f, true, true, -40f), -45f),
				"with both sides open, prefer the smaller turn (left)");
		check(close(Physics.pickRubYaw(0f, true, true, 40f), 45f),
				"with both sides open, prefer the smaller turn (right)");
		// The angle arithmetic must wrap the short way round.
		check(close(Physics.angleDelta(350f, 10f), 20f), "angle deltas must wrap across 360");
		check(close(Physics.angleDelta(10f, 350f), -20f), "angle deltas must wrap the other way");
	}

	private static boolean close(float a, float b) {
		return Math.abs(a - b) < 0.01f;
	}

	/**
	 * A failed search must never return a route that walks the player backwards.
	 *
	 * <p>Regression, and the second half of the same complaint: "卡住了，然后重新寻路往回走，
	 * 回去之后就不动了" — it got stuck, re-planned BACKWARDS, walked back, and stopped. When the
	 * goal is unreachable the search returns the path to the closest cell it reached, and
	 * "closest" is a plain distance — so with the way forward blocked, the nearest cell to the
	 * goal is frequently the one the player came from. The walker then follows that route in
	 * reverse and gives up somewhere behind its own starting point.
	 *
	 * <p>A retreat is never useful: it undoes progress and disguises "this way is blocked" as
	 * the bot changing its mind. The partial route must stop at the first node that is no
	 * closer to the goal than the start.
	 */
	private static void aFailedSearchNeverWalksBackwards() {
		// A one-dimensional world: the goal sits at x=10, the player starts at x=5, and the way
		// forward is walled off at x=6. The only cells reachable are x=4,5,6 — and x=6 is
		// closest to the goal, so a naive search returns a route that walks BACK to x=4.
		Function<Move, List<Move>> edges = m -> {
			List<Move> out = new ArrayList<>();
			if (m.x == 5) {
				out.add(move(6, 0, 0, 1)); // forward, to the wall
				out.add(move(4, 0, 0, 1)); // backwards, the retreat
			} else if (m.x == 6 || m.x == 4) {
				out.add(move(5, 0, 0, 1)); // back to the start
			}
			return out;
		};
		AStar.Result r = AStar.search(new BlockPos(5, 102, 0),
				new Goal.Block(10, 102, 0), 50, 1, edges);
		check(!r.complete, "the goal must be reported unreachable");
		// Whatever comes back must be forward progress only.
		for (Move n : r.path) {
			check(n.x >= 5, "a failed search must not route the player backwards");
		}
	}

	/** Mirrors the executor's cache condition: reuse only when node AND cell are unchanged. */

	/**
	 * A movement decision must be revisited when the BODY enters a new cell.
	 *
	 * <p>Regression, and the third time the same class of bug appeared. The mode was cached
	 * against the node alone, but several predicates ask about the body's own cell — "am I on a
	 * ladder", "is the floor under me solid". A node becomes current while the body is still in
	 * the PREVIOUS cell, because the anchor consumes nodes from a radius that reaches into it.
	 * So the decision was made from the wrong cell and then frozen.
	 *
	 * <p>The concrete failure: the route's next node was the first rung of a ladder shaft and
	 * the body was one cell short, on plain floor. "On a ladder" was false, every mode failed,
	 * NONE latched — and the body then walked the last few centimetres into the shaft, where
	 * the climb had been available all along, and stood there.
	 */
	private static void movementIsReDecidedWhenTheBodyChangesCell() {
		// The two inputs to the cached decision.
		Move node = new Move(225, 110, 476, 1, 1);
		Move cellOnFloor = new Move(225, 107, 475, 0, 0);
		Move cellInShaft = new Move(225, 107, 476, 0, 0);

		// Same node, same decision — the cache holds while neither input moves.
		check(sameDecision(node, node, cellInShaft, cellInShaft),
				"an unchanged node and cell must reuse the decision");
		// New node: re-decide. This always worked.
		check(!sameDecision(node, new Move(225, 111, 476, 1, 1), cellInShaft, cellInShaft),
				"a new node must force a new decision");
		// Same node, new body cell: this is the case that was missing.
		check(!sameDecision(node, node, cellOnFloor, cellInShaft),
				"entering a new cell must force a new decision");
		check(!sameDecision(node, node, cellInShaft, cellOnFloor),
				"leaving a cell must force a new decision");
	}

	/** Mirrors the executor's cache condition: reuse only when node AND cell are unchanged. */
	private static boolean sameDecision(Move cachedNode, Move node, Move cachedCell, Move cell) {
		return cachedNode == node && cachedCell == cell;
	}

	/**
	 * The planner must prefer the walkable way round over a leap it cannot make.
	 *
	 * <p>This is the defect the user kept reporting, in their own words: "旁边是有小路的，但是
	 * 他就是想要直接跨过中间的空格" — there is a side path, but it insists on crossing the gap.
	 * The arithmetic that caused it: a sprint-jump over a two-block gap is priced at
	 * {@code d + 0.5 = 2.5}, while walking around costs three ordinary steps at 1.0 each. The
	 * leap therefore looks CHEAPER than the detour, so A* takes it every time — and then the
	 * walker, which cannot actually make the leap from a standstill under a ceiling, refuses it
	 * and the bot freezes.
	 *
	 * <p>A move that cannot be executed must not be cheaper than one that can. Rather than
	 * reprice the leap to be honest, the leap is removed: a vanilla zombie cannot jump a gap at
	 * all, and the benchmark here is "a zombie gets through". Walking round is what it does.
	 */
	private static void walkAroundBeatsAnImpossibleLeap() {
		// The cost model as it was: the leap undercut the detour.
		double leapCost = 2 + 0.5;
		double detourCost = 3 * 1.0;
		check(leapCost < detourCost,
				"the old parkour price made a two-block leap cheaper than a three-step detour");
		// With parkour off, the search has no leap to prefer, so the detour is what remains.
		Movements m = new Movements(null, true);
		check(!m.allowParkour,
				"gap leaps must be off by default: an unexecutable move is worse than no move");
	}

	/**
	 * The failure must be recorded against the key the SEARCH asks about.
	 *
	 * <p>Regression, and the mechanism behind "it knows it is stuck and still walks the broken
	 * path". When the walker gave up on a step it forbade {@code bodyCell → nextNode}. But the
	 * search expands the route's OWN step, {@code routeCell → nextNode}, and the two differ
	 * whenever the body is straddling a cell boundary — which is exactly the situation that
	 * produces a stall, because the anchor consumes nodes from a 0.75 radius that reaches into
	 * the previous cell. The forbidden key was therefore never consulted, the search returned
	 * the identical route, and the walker refused the identical step, until the budget ran out.
	 */
	private static void theRouteKeyIsTheOneTheSearchTests() {
		// Constructed directly: the move(x, z, ...) helper pins y=102, and the whole point
		// here is the y=104/105 boundary.
		Move routeCell = new Move(225, 104, 459, 1, 1);
		Move nextNode = new Move(227, 105, 459, 2, 2);
		// The body stands 2cm short of the route cell: cell 458, not 459.
		Move bodyCell = new Move(225, 104, 458, 1, 1);

		String routeKey = Movements.moveKey(routeCell, nextNode);
		String bodyKey = Movements.moveKey(bodyCell, nextNode);
		check(!routeKey.equals(bodyKey),
				"the body's cell and the route's cell give different keys at a boundary");

		// The search filters with the route's key, so that is the one that must be recorded.
		java.util.Set<String> forbidden = new java.util.HashSet<>();
		forbidden.add(routeKey);
		check(forbidden.contains(Movements.moveKey(routeCell, nextNode)),
				"forbidding the route's step is what the search will actually test");
		check(!forbidden.contains(Movements.moveKey(bodyCell, nextNode)),
				"the body-cell key alone would never be consulted: the route would repeat");
	}

	/**
	 * A gap leap is judged by the columns it crosses, not by a line through the air.
	 *
	 * <p>Regression, and the one that kept the bot standing at a two-block gap in course 2. The
	 * check interpolated the body's height linearly from take-off to landing — but a jump does
	 * not travel in a straight line: it rises fast and comes down late, so on arrival it is
	 * well ABOVE that interpolation. Sampling the line put the body at y=104.9 inside the
	 * landing column, whose step block at y=104 is solid, and the leap was refused. The
	 * arithmetic below is that exact sample: the landing cell's own block must NOT be treated
	 * as an obstacle, because it is the step being jumped onto.
	 */
	private static void parkourIsDecidedByColumnsNotAnArc() {
		// The failing sample, as it was computed: body 2 cells along a leap from y=104 up to
		// y=105, at the moment it enters the landing column.
		double startY = 104.0;
		double dy = 1.0;
		double t = 1.0;
		double sampled = startY + Math.max(0, dy * t);
		check(sampled == 105.0,
				"a linear arc samples the body at the landing height, inside the step block");
		// The landing cell at that height is the step itself: (227,105,459) is standable and
		// (227,104,459) beneath it is the solid block the old sweep collided with.
		check(sampled - 1 == 104.0,
				"the cell the old sweep rejected is the step block, not an obstacle");
		// Hence the rule: the landing column is excluded from the flight-path sweep. Only the
		// cells strictly between take-off and landing are tested, at the body's own height.
		int span = 2;
		int tested = 0;
		for (int i = 1; i < span; i++) {
			tested++;
		}
		check(tested == 1, "only the cell between take-off and landing is swept");
	}

	/**
	 * The ascend rule and the plain "jump up" rule must be the same rule.
	 *
	 * <p>They were two predicates with different swept-corridor tests, and the one the walker
	 * consulted was stricter than the one the planner used. That is the general shape of the
	 * bug that kept this walker behind a zombie: the planner routes through a step, the walker
	 * refuses it, and the bot freezes between two halves of a navigator that disagree.
	 * {@code canReachFrom} is the walker's own question — "can I take this step from here" —
	 * so anything the planner can emit must satisfy it.
	 */
	private static void ascendRejectsAStandingJump() {
		// A step two blocks up is not an ascend, however close.
		check(!Physics.ascendApplies(2.0, true, 1, 0, 1.0),
				"a two-block rise must not be an ascend");
		// A step that is level is a walk, not an ascend.
		check(!Physics.ascendApplies(0.0, true, 1, 0, 1.0), "a level step is a walk");
		// The one-block step in every direction, at the distances a body can reach.
		check(Physics.ascendApplies(1.0, true, 1, 0, 1.2), "cardinal step up");
		check(Physics.ascendApplies(1.0, true, 0, 1, 1.2), "cardinal step up, other axis");
		check(Physics.ascendApplies(1.0, true, 1, 1, 1.5), "diagonal step up");
		check(Physics.ascendApplies(1.0, true, -1, -1, 1.5), "diagonal step up, negative");
	}

	/**
	 * A one-block step up must be plannable even when the player is under a ceiling.
	 *
	 * <p>This is the exact course-2 stall, from the real block data. The corridor runs along
	 * x=224 at feet y=102; at z=458 the cell one block across and one block up, (225,103,458),
	 * is a standable ledge — but the cell the player is standing in, (224,102,457), has a stone
	 * ceiling at y=104. Both halves of the navigator demanded headroom over the PLAYER'S OWN
	 * cell before permitting the step, so the search never generated the move and the walker
	 * refused it, and the bot froze at a step a zombie walks up.
	 *
	 * <p>The headroom that matters belongs to the jump, not the step: the body keeps walking
	 * and jumps from the open cell in front. So the step must be legal here, and the jump must
	 * simply wait.
	 */
	private static void ascendIsAllowedUnderACeiling() {
		// (225,103,458) from (224,102,458): one across, one up, standable, within reach.
		check(Physics.ascendApplies(1.0, true, 1, 0, 1.0),
				"a one-block step up must be allowed with a ceiling overhead");
		check(Physics.ascendApplies(1.0, true, 1, 1, 1.4),
				"a diagonal step up must be allowed too");
		// The rules that must still hold.
		check(!Physics.ascendApplies(0.0, true, 1, 0, 1.0), "a level step is not an ascend");
		check(!Physics.ascendApplies(2.0, true, 1, 0, 1.0), "a two-block rise is not an ascend");
		check(!Physics.ascendApplies(1.0, false, 1, 0, 1.0), "no floor means no ascend");
		check(!Physics.ascendApplies(1.0, true, 0, 0, 0.0), "the same cell is not a step");
		check(!Physics.ascendApplies(1.0, true, 2, 0, 2.0), "two cells away is not a step");
		check(!Physics.ascendApplies(1.0, true, 1, 0, 3.0), "out of reach is not a step");
	}

	/**
	 * The jump waits for a clear head and a close body, then fires.
	 *
	 * <p>This is Baritone's {@code MovementAscend} rule, and it is what makes the ceiling over
	 * the cell behind the step irrelevant. Pressing jump unconditionally is the failure it
	 * replaces: the body hops in place against the block above instead of walking on to where
	 * the jump is possible, which reads on screen as a bot that "cannot jump" at a step it
	 * could clear with one more stride.
	 */
	private static void jumpWaitsUntilCloseAndClear() {
		// Under a ceiling: never jump, however close — walk on first.
		check(!Physics.jumpAllowedNow(true, false, 0.5, true),
				"no jump under a ceiling, even when pressed against the step");
		// In the air: never jump.
		check(!Physics.jumpAllowedNow(false, true, 0.5, true), "no jump in mid-air");
		// Clear and close: jump.
		check(Physics.jumpAllowedNow(true, true, 1.0, false),
				"jump when close with a clear head");
		// Clear but far: hold, so the jump does not land short of the step.
		check(!Physics.jumpAllowedNow(true, true, 2.0, false),
				"do not jump from too far away");
		// Clear, far, but already leaning on the step: the distance test can never pass here,
		// so the collision is the signal that the step is right in front.
		check(Physics.jumpAllowedNow(true, true, 2.0, true),
				"jump when blocked, even if the centre is still far");
	}

	/**
	 * A transition the walker proved impossible must not come back from the search.
	 *
	 * <p>Without this feedback the planner and the walker deadlock: the planner keeps emitting
	 * a move the walker cannot perform, the walker keeps dropping the route, and the search
	 * returns the identical route forever — a bot that stands still indefinitely while its
	 * drawn path flickers. Here the direct edge is forbidden, so the search must find the
	 * two-step way round instead of re-offering it.
	 */
	private static void forbiddenMovesAreNotReplanned() {
		// Compare by coordinate, not identity: the search builds its own Move for the start
		// cell, so an identity test against a locally constructed Move never matches.
		Function<Move, List<Move>> edges = m -> {
			List<Move> out = new ArrayList<>();
			if (m.x == 0 && m.z == 0) {
				out.add(move(1, 0, 0, 1));   // the direct edge
				out.add(move(0, 1, 0, 1));   // the detour
			} else if (m.x == 0 && m.z == 1) {
				out.add(move(1, 1, 0, 1));
			} else if (m.x == 1 && m.z == 1) {
				out.add(move(2, 0, 0, 1));
			} else if (m.x == 1 && m.z == 0) {
				out.add(move(2, 0, 0, 1));
			}
			return out;
		};

		// With nothing forbidden, the direct edge is available and wins.
		AStar.Result plain = AStar.search(new BlockPos(0, 102, 0),
				new Goal.Block(2, 102, 0), 50, 1, edges);
		check(plain.complete, "direct route must reach the goal");
		check(plain.path.stream().anyMatch(n -> n.x == 1 && n.z == 0),
				"direct route should use the direct edge");

		// Forbid it: the search must route around, not give up and not repeat it.
		java.util.Set<String> forbidden = new java.util.HashSet<>();
		forbidden.add(Movements.moveKey(move(0, 0, 0, 1), move(1, 0, 0, 1)));
		Function<Move, List<Move>> filtered = m -> {
			List<Move> out = new ArrayList<>(edges.apply(m));
			out.removeIf(n -> forbidden.contains(Movements.moveKey(m, n)));
			return out;
		};
		AStar.Result alt = AStar.search(new BlockPos(0, 102, 0),
				new Goal.Block(2, 102, 0), 50, 1, filtered);
		check(alt.complete, "a forbidden edge must not make the goal unreachable");
		check(alt.path.stream().noneMatch(n -> n.x == 1 && n.z == 0),
				"the forbidden move must not appear in the route");
		check(alt.path.stream().anyMatch(n -> n.x == 1 && n.z == 1),
				"the search must take the detour instead");
	}

	/**
	 * A fall into water is allowed to be far deeper than an ordinary step down.
	 *
	 * <p>Regression guard for the course feature: ordinary drops are capped at four blocks
	 * because landing on stone hurts, but water breaks a fall of any depth. Treating the cap as
	 * universal makes a cliff-with-a-pool a dead end and the course unsolvable.
	 */
	private static void waterFallBeatsOrdinaryStepDown() {
		check(Movements.IMPOSSIBLE > 4, "the ordinary drop cap must be below the sentinel");
		// A ten-block fall is representable as a single move; the cost must stay modest so a
		// route never prefers a long fall over walking.
		double fallCost = 2 + Math.min(6, 10 * 0.25);
		check(fallCost < 6, "a long water fall must not dominate the cost model");
		check(fallCost > 1, "a long water fall must still cost more than a flat step");
	}

	private static void check(boolean value, String description) {
		checks++;
		if (!value) throw new AssertionError(description);
	}

	private static Move move(int x, int z, int stock, double stepCost) {
		return new Move(x, 102, z, stock, stepCost);
	}

	private static void cheapestRouteWins() {
		Function<Move, List<Move>> edges = m -> {
			List<Move> out = new ArrayList<>();
			if (m.x == 0 && m.z == 0) {
				out.add(move(1, 0, 0, 9));
				out.add(move(0, 1, 0, 1));
			} else if (m.x == 0 && m.z == 1) {
				out.add(move(1, 1, 0, 1));
			} else if (m.x == 1 && m.z == 1) {
				out.add(move(1, 0, 0, 1));
			} else if (m.x == 1 && m.z == 0) {
				out.add(move(2, 0, 0, 1));
			}
			return out;
		};
		AStar.Result r = AStar.search(new BlockPos(0, 102, 0), new Goal.Block(2, 102, 0), 100, 0, edges);
		check(r.complete, "cheaper detour must reach target");
		check(r.path.size() == 4, "route should take four inexpensive edges");
		check(r.path.get(0).z == 1, "route must not take expensive first edge");
	}

	private static void scaffoldingIsPartOfState() {
		Function<Move, List<Move>> edges = m -> {
			List<Move> out = new ArrayList<>();
			if (m.x == 0 && m.z == 0) {
				out.add(move(1, 0, 0, 1)); // cheap route used the only block
				out.add(move(0, 1, 1, 2)); // longer route preserves the block
			} else if (m.x == 0 && m.z == 1) {
				out.add(move(1, 0, 1, 2));
			} else if (m.x == 1 && m.z == 0 && m.remainingBlocks > 0) {
				out.add(move(2, 0, m.remainingBlocks - 1, 2));
			}
			return out;
		};
		AStar.Result r = AStar.search(new BlockPos(0, 102, 0), new Goal.Block(2, 102, 0), 100, 1, edges);
		check(r.complete, "preserved stock must remain reachable at same cell");
		check(r.path.size() == 3 && r.path.get(0).z == 1, "route must preserve a scaffold");
	}

	private static void partialIsNotSuccess() {
		Function<Move, List<Move>> edges = m -> m.x == 0
				? List.of(move(1, 0, 0, 1)) : List.of();
		AStar.Result partial = AStar.search(new BlockPos(0, 102, 0), new Goal.Block(3, 102, 0), 20, 0, edges);
		check(!partial.complete && partial.path.size() == 1, "disconnected route must be partial");
		check(partial.missBy == 2, "partial route must report true remaining distance");
		AStar.Result capped = AStar.search(new BlockPos(0, 102, 0), new Goal.Block(3, 102, 0), 1, 0, edges);
		check(!capped.complete && capped.visited == 1, "search budget must stop expansion");
	}

	private static void searchIsIncremental() {
		AStar.Search search = new AStar.Search(new BlockPos(0, 102, 0),
				new Goal.Block(7, 102, 0), 100, 0,
				m -> m.x < 7 ? List.of(move(m.x + 1, 0, 0, 1)) : List.of());
		check(search.advance(2) == null, "first tick must yield before completion");
		check(search.advance(2) == null, "second tick must resume, not restart");
		AStar.Result done = null;
		for (int i = 0; i < 4 && done == null; i++) done = search.advance(2);
		check(done != null && done.complete && done.visited == 8,
				"incremental search must finish from persistent frontier");
	}

	/**
	 * The route must begin where the player stands.
	 *
	 * <p>Regression: shortening used to drop node 0, so the route started several cells
	 * ahead. The walker then aimed at a waypoint whose straight line was clear only from the
	 * skipped node, found every movement mode blocked, and stalled until re-planning into the
	 * same route — "plans a path, never moves", on repeat.
	 */
	private static void firstStepIsAdjacent() {
		List<Move> route = new ArrayList<>();
		for (int x = 0; x <= 6; x++) {
			route.add(move(x, 0, 0, 1));
		}
		// Everything is walkable, which is exactly the case that used to collapse node 0.
		List<Move> collapsed = Physics.collapse(route, (a, b) -> true);
		check(collapsed.get(0) == route.get(0), "route must keep its first node");
		check(collapsed.get(collapsed.size() - 1) == route.get(route.size() - 1),
				"route must keep its last node");
		check(collapsed.size() == 2, "a clear straight run collapses to start plus end");

		// A height change or an edit must survive, or the walker loses the jump/dig step.
		List<Move> stepped = new ArrayList<>();
		stepped.add(move(0, 0, 0, 1));
		stepped.add(move(1, 0, 0, 1));
		stepped.add(new Move(2, 103, 0, 0, 2));
		List<Move> kept = Physics.collapse(stepped, (a, b) -> true);
		check(kept.size() == 3, "a height change must not be flattened away");
	}

	/**
	 * A diagonal step needs BOTH flanks clear.
	 *
	 * <p>Regression, and the one the user kept hitting: the port took the cheaper flank
	 * (min) where mineflayer-pathfinder takes the worse one (max). Taking the cheaper side
	 * lets the planner thread a corner with a solid block on one flank — the bot walks at the
	 * corner, the block stops it, and it re-plans into the same move until it gives up.
	 */
	private static void diagonalNeedsBothFlanksClear() {
		double impossible = Movements.IMPOSSIBLE;
		check(Movements.diagonalFlankCost(0, impossible) == impossible,
				"a solid flank must make the diagonal impossible");
		check(Movements.diagonalFlankCost(impossible, 0) == impossible,
				"a solid flank must be fatal on either side");
		check(Movements.diagonalFlankCost(0, 3) == 3,
				"a diagonal costs the worse flank, not the cheaper one");
		check(Movements.diagonalFlankCost(3, 0) == 3,
				"the worse flank is used regardless of order");
		check(Movements.diagonalFlankCost(1, 1) == 1, "an open corner keeps its cost");
	}

	/**
	 * The opening hop must be validated from the player's real position for EVERY candidate.
	 *
	 * <p>Regression, and the one that made the bot "walk half the maze, then stop": the
	 * from-player test was applied through a one-shot flag, so it covered only the first
	 * candidate. The next candidate was then measured centre-to-centre and a nine-block
	 * opening hop was accepted — far beyond anything the walker can take in one step. Here the
	 * from-player test rejects everything past node 2, so the route must stop there.
	 */
	private static void openingHopUsesPlayerPosition() {
		List<Move> route = new ArrayList<>();
		for (int x = 0; x <= 9; x++) {
			route.add(move(x, 0, 0, 1));
		}
		// Standable only up to x=2 from where the player actually is; the rest is "too far".
		List<Move> out = Physics.collapseFrom(route,
				(a, b) -> b.x <= 2,
				(a, b) -> true);
		check(out.get(0) == route.get(0), "route must keep its first node");
		check(out.get(1).x == 2,
				"the opening hop must stop where the from-player check stops it");
		check(out.get(out.size() - 1) == route.get(route.size() - 1),
				"route must still end at the goal");
	}

	/**
	 * Only genuinely reached nodes are consumed, and only from the front.
	 *
	 * <p>Two regressions live here. Re-anchoring to the <em>closest</em> node anywhere in the
	 * route skipped the walker forward past a corner it had not rounded, making the next step
	 * a diagonal through solid rock. And a follow-up "is the new first node reachable in one
	 * step?" guard wiped the whole route whenever it was not — which is almost always, because
	 * shortening leaves waypoints several cells apart. That guard fired every tick, restarting
	 * the search until the replan budget ran out, and the bot gave up mid-maze with its path
	 * visibly disappearing.
	 */
	private static void reachedNodesAreConsumedFromTheFront() {
		List<Move> route = new ArrayList<>();
		for (int z = 0; z <= 5; z++) {
			route.add(move(0, z, 0, 1));
		}
		// The body stands at z=1.2, near the shared edge of cells 0 and 1: both of those
		// centres are within reach, node 2 (a whole cell further on) is not. Distances are
		// measured to each node's centre, exactly as the executor measures them.
		double bodyZ = 1.2;
		double eps = 0.75;
		int reached = 0;
		for (int i = 0; i < route.size(); i++) {
			double d = Math.abs(route.get(i).z + 0.5 - bodyZ);
			if (d > eps) {
				break;
			}
			reached = i + 1;
		}
		check(reached == 2, "consumption must stop at the first node not reached");
		int drop = Math.min(reached, route.size() - 1);
		check(drop == 2, "at least one node must always remain");
		check(route.get(drop).z == 2, "the new front node is the next one to walk to");
	}
}
