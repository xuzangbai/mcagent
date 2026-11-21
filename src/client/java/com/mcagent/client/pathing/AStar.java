package com.mcagent.client.pathing;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Function;

/**
 * A* over {@link Movements}, ported from mineflayer-pathfinder.
 *
 * <p>The search itself is the small half of the job: a priority queue over cells and
 * non-dominated (cost, remaining scaffolding) labels. Quality comes from {@link Movements}, which decides what a neighbour is
 * and what it costs — including the blocks that must be broken or placed to get there.
 *
 * <p>When the goal cannot be reached it returns the closest node found instead of nothing, so
 * the caller can walk part of the way and re-plan from there rather than giving up outright.
 */
public final class AStar {

	/** A node is a move plus the bookkeeping the search needs. */
	public static final class PathNode {
		public final Move move;
		public double g;
		public double h;
		public double f;
		public PathNode parent;

		PathNode(Move move, double g, double h, PathNode parent) {
			this.move = move;
			this.g = g;
			this.h = h;
			this.f = g + h;
			this.parent = parent;
		}
	}

	/** Outcome of a search: the steps, and whether the goal was actually reached. */
	public static final class Result {
		public final List<Move> path;
		public final boolean complete;
		/** Straight-line distance from the final step to the goal; 0 when complete. */
		public final double missBy;
		public final int visited;

		Result(List<Move> path, boolean complete, double missBy, int visited) {
			this.path = path;
			this.complete = complete;
			this.missBy = missBy;
			this.visited = visited;
		}
	}

	private AStar() {
	}

	/**
	 * Searches from {@code start} to {@code goal}.
	 *
	 * @param maxNodes ceiling on expanded nodes, so a hopeless search cannot hang the client
	 * @param remainingBlocks scaffolding blocks assumed available for bridging and towering
	 */
	public static Result search(ClientLevel level, BlockPos start, Goal goal, int maxNodes, int remainingBlocks) {
		return search(level, start, goal, maxNodes, remainingBlocks, true);
	}

	/**
	 * @param canDig whether the route may break blocks; false forces it to go around
	 */
	public static Result search(ClientLevel level, BlockPos start, Goal goal, int maxNodes,
			int remainingBlocks, boolean canDig) {
		return search(start, goal, maxNodes, remainingBlocks,
				new Movements(level, canDig)::getNeighbors);
	}

	/** Search on a movement graph; also allows deterministic tests without a live world. */
	static Result search(BlockPos start, Goal goal, int maxNodes, int remainingBlocks,
			Function<Move, List<Move>> neighbors) {
		Search search = new Search(start, goal, maxNodes, remainingBlocks, neighbors);
		return search.advance(maxNodes);
	}

	/** Incremental search, advanced on the client tick so long routes never freeze the game. */
	public static final class Search {
		private final Goal goal;
		private final int maxNodes;
		private final Function<Move, List<Move>> neighbors;
		private final PriorityQueue<PathNode> open =
				new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
		// A cell reached with fewer scaffolding blocks is a different search state.
		private final Map<Long, List<PathNode>> labels = new HashMap<>();
		private final PathNode start;
		private PathNode closest;
		private int visited;
		private Result result;

		public Search(ClientLevel level, BlockPos start, Goal goal, int maxNodes,
				int remainingBlocks, boolean canDig) {
			this(level, start, goal, maxNodes, remainingBlocks, canDig, java.util.Set.of());
		}

		/**
		 * @param forbidden transitions the walker has already proved impossible, so the search
		 *                  cannot hand back a route containing one of them
		 */
		public Search(ClientLevel level, BlockPos start, Goal goal, int maxNodes,
				int remainingBlocks, boolean canDig, java.util.Set<String> forbidden) {
			this(start, goal, maxNodes, remainingBlocks,
					new Movements(level, canDig, forbidden)::getNeighbors);
		}

		Search(BlockPos start, Goal goal, int maxNodes, int remainingBlocks,
				Function<Move, List<Move>> neighbors) {
			this.goal = goal;
			this.maxNodes = maxNodes;
			this.neighbors = neighbors;
			Move move = new Move(start.getX(), start.getY(), start.getZ(), remainingBlocks, 0);
			this.start = new PathNode(move, 0, goal.heuristic(move), null);
			this.closest = this.start;
			open.add(this.start);
			labels.computeIfAbsent(key(move), k -> new ArrayList<>()).add(this.start);
		}

		/** Returns null until finished; the node budget is per tick, not per search. */
		public Result advance(int nodeBudget) {
			if (result != null) {
				return result;
			}
			int expanded = 0;
			while (!open.isEmpty() && visited < maxNodes && expanded < nodeBudget) {
				PathNode cur = open.poll();
				List<PathNode> cell = labels.get(key(cur.move));
				if (cell == null || !cell.contains(cur)) {
					continue; // a better label replaced this open entry
				}
				visited++;
				expanded++;
				if (goal.isEnd(cur.move)) {
					return result = finish(cur, true);
				}
				if (cur.h < closest.h || (cur.h == closest.h && cur.g < closest.g)) {
					closest = cur;
				}				for (Move next : neighbors.apply(cur.move)) {
					if (next.cost >= Movements.IMPOSSIBLE || next.cost < 0
							|| next.remainingBlocks < 0) {
						continue;
					}
					double g = cur.g + next.cost;
					List<PathNode> existing = labels.computeIfAbsent(key(next), k -> new ArrayList<>());
					boolean dominated = false;
					for (PathNode lab : existing) {
						if (lab.g <= g && lab.move.remainingBlocks >= next.remainingBlocks) {
							dominated = true;
							break;
						}
					}
					if (dominated) {
						continue;
					}
					existing.removeIf(lab -> g <= lab.g && next.remainingBlocks >= lab.move.remainingBlocks);
					PathNode node = new PathNode(next, g, goal.heuristic(next), cur);
					existing.add(node);
					open.add(node);
				}
			}
			if (open.isEmpty() || visited >= maxNodes) {
				return result = finish(closest, false);
			}
			return null;
		}

		private Result finish(PathNode end, boolean complete) {
			List<Move> path = reconstruct(end);
			if (!complete) {
				path = dropRetreat(path);
			}
			return new Result(path, complete, complete ? 0 : goal.distanceTo(end.move), visited);
		}

		/**
		 * Trims a failed search's partial route so it never walks the player BACKWARDS.
		 *
		 * <p>When the goal turns out to be unreachable, the search returns the path to the
		 * closest cell it managed to reach. That cell is chosen by heuristic, and the
		 * heuristic is a plain distance — so with the forward way blocked, the closest cell to
		 * the goal is often the one the player already came from. The walker then faithfully
		 * follows the route in reverse, and stops somewhere behind where it started. On screen
		 * that is "卡住了，然后重新寻路往回走，回去之后就不动了" — it retreats and gives up.
		 *
		 * <p>A retreat is never useful: it does not get closer to the goal, it undoes progress,
		 * and it hides the real situation (this way is blocked) behind motion that looks like
		 * the bot changing its mind. The partial route is cut at the first node that is further
		 * from the goal than the start, so what remains is only genuine forward progress — and
		 * an empty result, which reports the failure honestly, if there was none.
		 */
		private List<Move> dropRetreat(List<Move> path) {
			double startDistance = goal.distanceTo(start.move);
			int keep = 0;
			for (int i = 0; i < path.size(); i++) {
				if (goal.distanceTo(path.get(i)) >= startDistance) {
					break; // this node is no closer than where we began: the route is retreating
				}
				keep = i + 1;
			}
			return new ArrayList<>(path.subList(0, keep));
		}

		/** Nodes expanded so far; for progress reporting while the search runs. */
		public int visitedCount() {
			return visited;
		}
	}

	private static long key(Move m) {
		return BlockPos.asLong(m.x, m.y, m.z);
	}

	private static List<Move> reconstruct(PathNode end) {
		List<Move> out = new ArrayList<>();
		for (PathNode n = end; n != null && n.parent != null; n = n.parent) {
			out.add(n.move);
		}
		java.util.Collections.reverse(out);
		return out;
	}
}
