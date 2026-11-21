package com.mcagent.client.pathing;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The cost model and move generators, ported from mineflayer-pathfinder's {@code Movements}.
 *
 * <p>This is the part that makes pathfinding good, and it is deliberately much larger than
 * the search itself: A* is a hundred lines, while knowing <em>what a legal move is</em> and
 * what it costs is the whole problem. Each generator below answers one question — can I walk
 * there, jump up there, drop down there, parkour across, tower up — and returns the blocks
 * that must be broken or placed along the way.
 *
 * <p>Faithful to the original except for entity arithmetic, which is simplified: this drives
 * one player in a normal world rather than a fleet of bots on a server.
 *
 * <p>Block vocabulary used throughout, taken from the original's comments:
 * <ul>
 *   <li><b>physical</b> — has a collision box; you can stand on it.</li>
 *   <li><b>safe</b> — can be occupied without harm (no fire, lava, cactus, magma).</li>
 *   <li><b>liquid</b> — water or lava.</li>
 *   <li><b>replaceable</b> — a block can be placed into this cell (air, grass, water).</li>
 *   <li><b>height</b> — top of the collision box, relative to the block's own Y.</li>
 * </ul>
 */
public final class Movements {

	/** Cost above which a route counts as impossible. */
	public static final double IMPOSSIBLE = 100;

	/**
	 * Whether the route may break blocks.
	 *
	 * <p>Exposed as a choice rather than a constant because both answers are useful: with it
	 * on, the navigator tunnels through obstacles; with it off, it must find a way around, and
	 * a failure means "genuinely walled in" rather than "would have to dig". Letting the
	 * caller decide is also what stops a walk across open ground from turning into a trench,
	 * which is what happens when mining is always permitted.
	 */
	public final boolean canDig;

	public final double digCost = 1;
	public final double placeCost = 2;
	public final double liquidCost = 1;
	public final double entityCost = 1;

	public final boolean dontCreateFlow = true;
	public final boolean dontMineUnderFallingBlock = true;
	public final boolean allow1by1towers = true;
	/**
	 * Whether the planner may route through a sprint-jump over a gap.
	 *
	 * <p>Off, and that is a deliberate retreat to what the benchmark actually is. A vanilla
	 * zombie's navigator cannot leap a gap at all: it walks, steps up one block, and jumps
	 * when the node ahead is higher. Every time this walker was measured against "a zombie
	 * gets through here", the failure was a leap — the planner preferring a two-block jump
	 * across a hole over the walkable way around it, because a jump costs 2.5 and the detour
	 * costs 3. The leap then failed at execution time (no run-up room under the ceiling, the
	 * lip reached at the wrong speed) and the bot stood there.
	 *
	 * <p>A move the walker cannot reliably perform is worse than a move it does not have:
	 * the planner treats it as available, so it never considers the side path. Walking
	 * around is what a mob does and what the user expects. Turn this back on only with a
	 * leap that is verified as thoroughly as Baritone's {@code MovementParkour} — landing
	 * cell, overshoot safety, and take-off room all checked at plan time.
	 */
	public final boolean allowParkour = false;
	public final boolean allowSprinting = true;
	public final int maxDropDown = 4;

	private final ClientLevel level;
	/** One search's immutable terrain snapshot: avoids repeating collision/fluid lookups. */
	private final Map<Long, BlockInfo> terrain = new HashMap<>();
	/**
	 * Moves the walker has already proved impossible, as {@code from>to} cell keys.
	 *
	 * <p>Shared with the executor so a step it cannot physically perform is not planned again.
	 * Without this the planner and the walker deadlock: the planner keeps emitting a move the
	 * walker refuses (a jump whose flight path clips a wall, say), the walker keeps dropping
	 * the route and re-planning, and the search returns the identical route every time. The
	 * bot stands still for as long as you care to watch.
	 */
	private final Set<String> forbidden;

	public Movements(ClientLevel level) {
		this(level, true, Set.of());
	}

	public Movements(ClientLevel level, boolean canDig) {
		this(level, canDig, Set.of());
	}

	public Movements(ClientLevel level, boolean canDig, Set<String> forbidden) {
		this.level = level;
		this.canDig = canDig;
		this.forbidden = forbidden;
	}

	/** Key identifying one transition, so the executor and the planner agree on identity. */
	public static String moveKey(Move from, Move to) {
		return from.x + "," + from.y + "," + from.z + ">" + to.x + "," + to.y + "," + to.z;
	}

	// ------------------------------------------------------------------
	// block classification
	// ------------------------------------------------------------------

	/** Everything the generators need to know about one cell. */
	public static final class BlockInfo {
		public final BlockPos pos;
		public final boolean physical;
		public final boolean safe;
		public final boolean liquid;
		public final boolean replaceable;
		public final boolean climbable;
		/** Top of the collision box relative to the block's y; 0 when empty. */
		public double height;

		BlockInfo(BlockPos pos, boolean physical, boolean safe, boolean liquid,
				boolean replaceable, boolean climbable, double height) {
			this.pos = pos;
			this.physical = physical;
			this.safe = safe;
			this.liquid = liquid;
			this.replaceable = replaceable;
			this.climbable = climbable;
			this.height = height;
		}

		/** Absolute Y of this cell's top surface. */
		public double top() {
			return pos.getY() + height;
		}
	}

	public BlockInfo getBlock(int x, int y, int z) {
		long key = BlockPos.asLong(x, y, z);
		BlockInfo cached = terrain.get(key);
		if (cached != null) {
			return cached;
		}
		BlockPos pos = new BlockPos(x, y, z);
		BlockState st = level.getBlockState(pos);
		VoxelShape shape = st.getCollisionShape(level, pos);

		boolean water = st.getFluidState().is(FluidTags.WATER);
		boolean lava = st.getFluidState().is(FluidTags.LAVA);
		boolean liquid = water || lava;

		boolean hazard = st.is(Blocks.FIRE) || st.is(Blocks.SOUL_FIRE) || st.is(Blocks.LAVA)
				|| st.is(Blocks.CACTUS) || st.is(Blocks.MAGMA_BLOCK) || st.is(Blocks.POWDER_SNOW)
				|| st.is(Blocks.SWEET_BERRY_BUSH) || st.is(Blocks.CAMPFIRE) || st.is(Blocks.SOUL_CAMPFIRE);

		boolean climbable = st.is(Blocks.LADDER) || st.is(Blocks.VINE) || st.is(Blocks.SCAFFOLDING)
				|| st.is(Blocks.TWISTING_VINES) || st.is(Blocks.TWISTING_VINES_PLANT)
				|| st.is(Blocks.WEEPING_VINES) || st.is(Blocks.WEEPING_VINES_PLANT)
				|| st.is(Blocks.CAVE_VINES) || st.is(Blocks.CAVE_VINES_PLANT);

		// A ladder is a thin box bolted to a wall, so a plain "has a collision shape" test
		// calls the cell solid and the planner routes around a shaft it could simply climb.
		// For occupancy purposes a climbable cell is open space you can hang in.
		boolean physical = !shape.isEmpty() && !climbable;
		double height = physical ? shape.max(Direction.Axis.Y) : 0;

		// safe = you may occupy it: nothing solid in the way and nothing that hurts
		boolean safe = !physical && !hazard;

		BlockInfo info = new BlockInfo(pos, physical, safe, liquid, st.canBeReplaced(), climbable, height);
		terrain.put(key, info);
		return info;
	}

	private BlockInfo getBlock(int x, int y, int z, int dx, int dy, int dz) {
		return getBlock(x + dx, y + dy, z + dz);
	}

	/** Living entities whose hitbox overlaps this cell, excluding the player. */
	private int entitiesAt(BlockPos pos) {
		try {
			AABB box = new AABB(pos);
			int n = 0;
			for (Entity e : level.getEntities(null, box)) {
				if (e.isAlive() && !(e instanceof net.minecraft.client.player.LocalPlayer)) {
					n++;
				}
			}
			return n;
		} catch (Throwable t) {
			return 0;
		}
	}

	// ------------------------------------------------------------------
	// dig / place feasibility
	// ------------------------------------------------------------------

	/** Ported {@code safeToBreak}: refuses blocks that would flood or drop gravel on us. */
	public boolean safeToBreak(BlockInfo b) {
		if (!canDig) {
			return false;
		}
		if (b.pos.getY() <= level.getMinY()) {
			return false;
		}
		if (dontCreateFlow) {
			// breaking next to liquid lets it pour into the gap
			if (getBlock(b.pos.getX(), b.pos.getY() + 1, b.pos.getZ()).liquid) return false;
			if (getBlock(b.pos.getX() - 1, b.pos.getY(), b.pos.getZ()).liquid) return false;
			if (getBlock(b.pos.getX() + 1, b.pos.getY(), b.pos.getZ()).liquid) return false;
			if (getBlock(b.pos.getX(), b.pos.getY(), b.pos.getZ() - 1).liquid) return false;
			if (getBlock(b.pos.getX(), b.pos.getY(), b.pos.getZ() + 1).liquid) return false;
		}
		if (dontMineUnderFallingBlock) {
			BlockState above = level.getBlockState(b.pos.above());
			boolean canFall = above.is(Blocks.SAND) || above.is(Blocks.RED_SAND)
					|| above.is(Blocks.GRAVEL) || above.is(Blocks.ANVIL) || above.is(Blocks.DRAGON_EGG);
			if (canFall || entitiesAt(b.pos.above()) > 0) {
				return false;
			}
		}
		if (b.physical && hardness(b.pos) < 0) {
			return false; // bedrock and friends
		}
		return !b.replaceable || hardness(b.pos) >= 0;
	}

	/** Hardness of the block at this position, or -1 when unbreakable. */
	private double hardness(BlockPos pos) {
		BlockState st = level.getBlockState(pos);
		float speed = st.getDestroySpeed(level, pos);
		if (speed < 0) {
			return -1;
		}
		if (st.getBlock() instanceof net.minecraft.world.level.block.BaseEntityBlock) {
			return -1; // chests and other containers: not ours to smash
		}
		return speed;
	}

	/**
	 * Cost of needing this cell clear, or {@link #IMPOSSIBLE} when it cannot be cleared.
	 * Adds the position to {@code toBreak} when something must be mined.
	 */
	public double safeOrBreak(BlockInfo b, List<BlockPos> toBreak) {
		double cost = entitiesAt(b.pos) * entityCost;
		if (b.safe) {
			return cost;
		}
		if (!safeToBreak(b)) {
			return IMPOSSIBLE;
		}
		toBreak.add(b.pos);
		double h = hardness(b.pos);
		if (h < 0) {
			return IMPOSSIBLE;
		}
		// digging cost grows with hardness, which is what keeps A* from tunnelling through
		// obsidian when walking around is cheaper
		cost += (1 + 3 * h) * digCost;
		return cost;
	}

	// ------------------------------------------------------------------
	// move generators — one per way of leaving a cell
	// ------------------------------------------------------------------

	/** Walk straight ahead, optionally bridging over a gap. */
	public void getMoveForward(Move node, int dx, int dz, List<Move> out) {
		BlockInfo b = getBlock(node.x, node.y, node.z, dx, 1, dz);
		BlockInfo c = getBlock(node.x, node.y, node.z, dx, 0, dz);
		BlockInfo d = getBlock(node.x, node.y, node.z, dx, -1, dz);

		double cost = 1;
		List<BlockPos> toBreak = new ArrayList<>();
		List<BlockPos> toPlace = new ArrayList<>();

		if (!d.physical && !c.liquid) {
			// A gap needs support under the destination (not under the player).
			if (node.remainingBlocks == 0 || entitiesAt(d.pos) > 0) {
				return;
			}
			if (!d.replaceable) {
				if (!safeToBreak(d)) {
					return;
				}
				cost += hardBreakCost(d, toBreak);
			}
			toPlace.add(d.pos);
			cost += placeCost;
		}

		cost += safeOrBreak(b, toBreak);
		if (cost > IMPOSSIBLE) {
			return;
		}
		cost += safeOrBreak(c, toBreak);
		if (cost > IMPOSSIBLE) {
			return;
		}
		if (getBlock(node.x, node.y, node.z).liquid) {
			cost += liquidCost;
		}
		out.add(new Move(c.pos.getX(), c.pos.getY(), c.pos.getZ(),
				node.remainingBlocks - toPlace.size(), cost, toBreak, toPlace, false));
	}

	/**
	 * Swim out of water onto the bank beside it.
	 *
	 * <p>A separate move because the ordinary step generators judge height from the block grid,
	 * and water has no collision box: the planner treats the player as standing at the water
	 * block's own Y rather than floating at its surface. A bank one block above the surface
	 * then looks like a two-block wall and the route stops in the pool — which is exactly how
	 * a "drop into water" course ends with the bot treading water at the bottom.
	 *
	 * <p>A player swims to the edge and jumps out, so that is what this models: from any water
	 * cell, step onto an adjacent standable cell whose floor is at most one above the surface.
	 */
	public void getMoveSwimOut(Move node, int dx, int dz, List<Move> out) {
		BlockInfo here = getBlock(node.x, node.y, node.z);
		if (!here.liquid || !here.safe) {
			return; // only from water
		}
		// The surface: the highest water cell in this column that is still water.
		int surface = node.y;
		while (getBlock(node.x, surface + 1, node.z).liquid) {
			surface++;
		}
		BlockInfo target = getBlock(node.x, node.y, node.z, dx, 1, dz);
		BlockInfo targetFloor = getBlock(node.x, node.y, node.z, dx, 0, dz);
		BlockInfo targetFoot = getBlock(node.x, node.y, node.z, dx, -1, dz);
		if (!target.safe || !targetFloor.safe) {
			return;
		}
		// The bank must be at or just above the water surface, and have real footing.
		if (!targetFoot.physical && !targetFoot.liquid) {
			return;
		}
		if (targetFoot.top() - surface > 1.2) {
			return; // too high to haul out of the water
		}
		List<BlockPos> toBreak = new ArrayList<>();
		double cost = 2 + safeOrBreak(target, toBreak);
		if (cost > IMPOSSIBLE) {
			return;
		}
		out.add(new Move(target.pos.getX(), target.pos.getY(), target.pos.getZ(),
				node.remainingBlocks, cost, toBreak, new ArrayList<>(), false));
	}

	/** Step up one block, placing scaffolding when the step is a wall. */
	public void getMoveJumpUp(Move node, int dx, int dz, List<Move> out) {
		BlockInfo a = getBlock(node.x, node.y, node.z, 0, 2, 0);
		BlockInfo h = getBlock(node.x, node.y, node.z, dx, 2, dz);
		BlockInfo b = getBlock(node.x, node.y, node.z, dx, 1, dz);
		BlockInfo c = getBlock(node.x, node.y, node.z, dx, 0, dz);

		double cost = 2;
		List<BlockPos> toBreak = new ArrayList<>();
		List<BlockPos> toPlace = new ArrayList<>();

		if (!c.physical) {
			// Never bridge into liquid. Water is not a "gap" that needs a block: it is a
			// surface the player swims on, and treating it as a gap made the planner generate
			// place-a-block moves inside a pool. The bot then paved the water, stood on its own
			// block (so it was no longer in water and swim mode never engaged), and trapped
			// itself in a pocket of its own making.
			if (c.liquid) {
				return;
			}
			if (node.remainingBlocks == 0 || entitiesAt(c.pos) > 0) {
				return;
			}
			BlockInfo d = getBlock(node.x, node.y, node.z, dx, -1, dz);
			if (!d.physical) {
				if (node.remainingBlocks == 1 || entitiesAt(d.pos) > 0) {
					return;
				}
				if (!d.replaceable) {
					if (!safeToBreak(d)) {
						return;
					}
					cost += hardBreakCost(d, toBreak);
				}
				toPlace.add(d.pos);
				cost += placeCost;
			}
			if (!c.replaceable) {
				if (!safeToBreak(c)) {
					return;
				}
				cost += hardBreakCost(c, toBreak);
			}
			toPlace.add(new BlockPos(c.pos.getX(), c.pos.getY(), c.pos.getZ()));
			cost += placeCost;
			// The scaffold exists only in this candidate move, not in the terrain cache.
			c = new BlockInfo(c.pos, true, false, false, false, false, 1);
		}

		BlockInfo below = getBlock(node.x, node.y, node.z, 0, -1, 0);
		if (c.top() - below.top() > 1.2) {
			return; // too high to jump
		}

		cost += safeOrBreak(a, toBreak);
		if (cost > IMPOSSIBLE) return;
		cost += safeOrBreak(h, toBreak);
		if (cost > IMPOSSIBLE) return;
		cost += safeOrBreak(b, toBreak);
		if (cost > IMPOSSIBLE) return;

		out.add(new Move(b.pos.getX(), b.pos.getY(), b.pos.getZ(),
				node.remainingBlocks - toPlace.size(), cost, toBreak, toPlace, false));
	}

	/** Step down into a lower cell, including a fall of up to {@link #maxDropDown}. */
	public void getMoveDropDown(Move node, int dx, int dz, List<Move> out) {
		BlockInfo b = getBlock(node.x, node.y, node.z, dx, 1, dz);
		BlockInfo c = getBlock(node.x, node.y, node.z, dx, 0, dz);
		BlockInfo d = getBlock(node.x, node.y, node.z, dx, -1, dz);

		double cost = 1;
		List<BlockPos> toBreak = new ArrayList<>();

		if (d.physical || !b.safe || !c.safe || !d.safe) {
			return; // only drop into an open shaft, not through the floor
		}
		BlockInfo land = getLandingBlock(node, dx, dz);
		if (land == null) {
			return;
		}
		if (node.y - land.pos.getY() > maxDropDown) {
			return;
		}

		cost += safeOrBreak(b, toBreak);
		if (cost > IMPOSSIBLE) return;
		cost += safeOrBreak(c, toBreak);
		if (cost > IMPOSSIBLE) return;
		cost += safeOrBreak(d, toBreak);
		if (cost > IMPOSSIBLE) return;

		if (c.liquid) {
			return; // do not dive
		}
		cost += entitiesAt(land.pos) * entityCost;
		out.add(new Move(land.pos.getX(), land.pos.getY(), land.pos.getZ(),
				node.remainingBlocks, cost, toBreak, new ArrayList<>(), false));
	}

	/** Straight down, for climbing back out of a shaft. */
	public void getMoveDown(Move node, List<Move> out) {
		BlockInfo below = getBlock(node.x, node.y, node.z, 0, -1, 0);
		double cost = 1;
		List<BlockPos> toBreak = new ArrayList<>();
		if (below.physical || !below.safe) {
			return;
		}

		BlockInfo land = getLandingBlock(node, 0, 0);
		if (land == null) {
			return;
		}
		cost += safeOrBreak(below, toBreak);
		if (cost > IMPOSSIBLE) {
			return;
		}
		if (getBlock(node.x, node.y, node.z).liquid) {
			return;
		}
		cost += entitiesAt(land.pos) * entityCost;
		out.add(new Move(land.pos.getX(), land.pos.getY(), land.pos.getZ(),
				node.remainingBlocks, cost, toBreak, new ArrayList<>(), false));
	}

	/** Tower straight up by jumping and placing the block underneath. */
	public void getMoveUp(Move node, List<Move> out) {
		BlockInfo here = getBlock(node.x, node.y, node.z);
		if (here.liquid) {
			return;
		}
		if (entitiesAt(here.pos) > 0) {
			return;
		}
		BlockInfo ceiling = getBlock(node.x, node.y, node.z, 0, 2, 0);

		double cost = 1;
		List<BlockPos> toBreak = new ArrayList<>();
		List<BlockPos> toPlace = new ArrayList<>();
		cost += safeOrBreak(ceiling, toBreak);
		if (cost > IMPOSSIBLE) {
			return;
		}

		if (!here.climbable) {
			if (!allow1by1towers || node.remainingBlocks == 0) {
				return;
			}
			if (!here.replaceable) {
				if (!safeToBreak(here)) {
					return;
				}
				toBreak.add(here.pos);
			}
			BlockInfo below = getBlock(node.x, node.y, node.z, 0, -1, 0);
			if (below.physical && below.top() - node.y < -0.2) {
				return; // cannot jump-place off a slab
			}
			toPlace.add(here.pos);
			cost += placeCost;
		}

		if (cost > IMPOSSIBLE) {
			return;
		}
		out.add(new Move(node.x, node.y + 1, node.z,
				node.remainingBlocks - toPlace.size(), cost, toBreak, toPlace, false));
	}

	/** Jump a flat or descending gap of up to four blocks, sprinting. */
	public void getMoveParkourForward(Move node, int dx, int dz, List<Move> out) {
		BlockInfo floor0 = getBlock(node.x, node.y, node.z, 0, -1, 0);
		BlockInfo floor1 = getBlock(node.x, node.y, node.z, dx, -1, dz);
		if ((floor1.physical && floor1.top() >= floor0.top())
				|| !getBlock(node.x, node.y, node.z, dx, 0, dz).safe
				|| !getBlock(node.x, node.y, node.z, dx, 1, dz).safe) {
			return;
		}
		if (getBlock(node.x, node.y, node.z).liquid) {
			return; // cannot jump out of water
		}
		// The cell immediately ahead must be a GAP, not a wall and not walkable floor.
		//
		// A parkour move means "leap over a hole". Without this guard the generator also
		// fired where the floor simply continued — and worse, where the cell directly ahead
		// was solid, producing a route that asked the walker to hop sideways over a wall.
		// On screen that is a bot stuck in a corridor, repeatedly refusing a jump the planner
		// had drawn a line through.
		BlockInfo immediate = getBlock(node.x, node.y, node.z, dx, 0, dz);
		if (immediate.physical) {
			return;
		}
		// There must be a gap: the cell below the landing must not be solid, or this is an
		// ordinary step and the plain walk/jump generators already cover it.
		BlockInfo landingFloor = getBlock(node.x, node.y, node.z, dx, -1, dz);
		if (landingFloor.physical) {
			return;
		}

		boolean ceilingClear = getBlock(node.x, node.y, node.z, 0, 2, 0).safe
				&& getBlock(node.x, node.y, node.z, dx, 2, dz).safe;
		boolean floorCleared = !getBlock(node.x, node.y, node.z, dx, -2, dz).physical;

		int maxD = allowSprinting ? 4 : 2;
		for (int d = 2; d <= maxD; d++) {
			int ox = dx * d;
			int oz = dz * d;
			BlockInfo a = getBlock(node.x, node.y, node.z, ox, 2, oz);
			BlockInfo b = getBlock(node.x, node.y, node.z, ox, 1, oz);
			BlockInfo c = getBlock(node.x, node.y, node.z, ox, 0, oz);
			BlockInfo dd = getBlock(node.x, node.y, node.z, ox, -1, oz);

			// Every intervening cell must be clear of solid blocks: a sprint jump travels
			// through the cells between take-off and landing, so one wall in the middle makes
			// the whole move impossible however open the landing looks.
			boolean pathClear = true;
			for (int k = 1; k < d; k++) {
				if (getBlock(node.x, node.y, node.z, dx * k, 0, dz * k).physical
						|| getBlock(node.x, node.y, node.z, dx * k, 1, dz * k).physical) {
					pathClear = false;
					break;
				}
			}
			if (!pathClear) {
				break;
			}
			if (ceilingClear && b.safe && c.safe && dd.physical) {
				out.add(new Move(c.pos.getX(), c.pos.getY(), c.pos.getZ(),
						node.remainingBlocks, d, new ArrayList<>(), new ArrayList<>(), true));
				break;
			} else if (ceilingClear && b.safe && c.physical) {
				if (a.safe && d != 4) {
					if (c.top() - floor0.top() > 1.2) {
						break;
					}
					out.add(new Move(b.pos.getX(), b.pos.getY(), b.pos.getZ(),
							node.remainingBlocks, d + 0.5, new ArrayList<>(), new ArrayList<>(), true));
					break;
				}
			} else if ((ceilingClear || d == 2) && b.safe && c.safe && dd.safe && floorCleared) {
				BlockInfo e = getBlock(node.x, node.y, node.z, ox, -2, oz);
				if (e.physical) {
					out.add(new Move(dd.pos.getX(), dd.pos.getY(), dd.pos.getZ(),
							node.remainingBlocks, d + 1, new ArrayList<>(), new ArrayList<>(), true));
				}
				floorCleared = floorCleared && !e.physical;
			} else if (!b.safe || !c.safe) {
				break;
			}
			ceilingClear = ceilingClear && a.safe;
		}
	}

	/**
	 * Diagonal step. Diagonals matter: without them the bot only moves in four directions and
	 * looks robotic, and its routes are needlessly long.
	 */
	public void getMoveDiagonal(Move node, int dx, int dz, List<Move> out) {
		double cost = Math.sqrt(2);
		List<BlockPos> toBreak = new ArrayList<>();

		BlockInfo c = getBlock(node.x, node.y, node.z, dx, 0, dz);
		int y = c.physical ? 1 : 0;
		BlockInfo floor0 = getBlock(node.x, node.y, node.z, 0, -1, 0);

		// Both flanks of the corner must be clear, and the step costs the WORSE of the two.
		//
		// <p>The original mineflayer-pathfinder takes {@code Math.max(cost1, cost2)} here;
		// this port took the cheaper side, and that single word is the "stuck at corners" bug.
		// A player is 0.6 blocks wide, so a diagonal step sweeps through BOTH flanking cells.
		// Picking the cheap flank let A* accept a corner with a solid block on one side — a
		// move the executor then refused every tick, re-planning into the identical route
		// until it gave up. Cost is max, never min, and an unbreakable flank is fatal.
		double cost1 = safeOrBreak(getBlock(node.x, node.y, node.z, 0, y + 1, dz), toBreak)
				+ safeOrBreak(getBlock(node.x, node.y, node.z, 0, y, dz), toBreak);
		double cost2 = safeOrBreak(getBlock(node.x, node.y, node.z, dx, y + 1, 0), toBreak)
				+ safeOrBreak(getBlock(node.x, node.y, node.z, dx, y, 0), toBreak);

		double flank = diagonalFlankCost(cost1, cost2);
		if (flank >= IMPOSSIBLE) {
			return; // a solid flank: the body cannot fit through this corner
		}
		cost += flank;
		if (cost > IMPOSSIBLE) {
			return;
		}

		cost += safeOrBreak(getBlock(node.x, node.y, node.z, dx, y, dz), toBreak);
		if (cost > IMPOSSIBLE) return;
		cost += safeOrBreak(getBlock(node.x, node.y, node.z, dx, y + 1, dz), toBreak);
		if (cost > IMPOSSIBLE) return;

		if (getBlock(node.x, node.y, node.z).liquid) {
			cost += liquidCost;
		}

		BlockInfo d = getBlock(node.x, node.y, node.z, dx, -1, dz);
		if (y == 1) {
			if (c.top() - floor0.top() > 1.2) {
				return;
			}
			cost += safeOrBreak(getBlock(node.x, node.y, node.z, 0, 2, 0), toBreak);
			if (cost > IMPOSSIBLE) return;
			out.add(new Move(c.pos.getX(), c.pos.getY() + 1, c.pos.getZ(),
					node.remainingBlocks, cost + 1, toBreak, new ArrayList<>(), false));
		} else if (d.physical || c.liquid) {
			out.add(new Move(c.pos.getX(), c.pos.getY(), c.pos.getZ(),
					node.remainingBlocks, cost, toBreak, new ArrayList<>(), false));
		} else if (getBlock(node.x, node.y, node.z, dx, -2, dz).physical || d.liquid) {
			cost += entitiesAt(c.pos) * entityCost;
			out.add(new Move(c.pos.getX(), c.pos.getY() - 1, c.pos.getZ(),
					node.remainingBlocks, cost, toBreak, new ArrayList<>(), false));
		}
	}

	/**
	 * Jump diagonally up onto a ledge one block higher.
	 *
	 * <p>A player does this constantly — standing at the inside of a corner where the only way
	 * on is up and sideways — but the cardinal jump-up generator cannot express it, and the
	 * plain diagonal generator refuses it because the flanking cell at the player's own height
	 * is solid. With neither move available the search declares the route unreachable and the
	 * bot stops dead at the corner, which is the "stops at corners and gives up" symptom.
	 *
	 * <p>Both flanking cells must be clear at the DESTINATION height (the body swings through
	 * them as it rises), and the destination must be a solid, standable ledge.
	 */
	public void getMoveDiagonalJumpUp(Move node, int dx, int dz, List<Move> out) {
		BlockInfo dest = getBlock(node.x, node.y, node.z, dx, 1, dz);
		if (dest.physical || !dest.safe) {
			return; // the cell one up and across must be open for the body
		}
		BlockInfo destFloor = getBlock(node.x, node.y, node.z, dx, 0, dz);
		if (!destFloor.physical) {
			return; // nothing to land on: that is a plain diagonal, not a step up
		}
		// The landing surface must be a step up, not a cliff.
		BlockInfo hereFloor = getBlock(node.x, node.y, node.z, 0, -1, 0);
		double rise = destFloor.top() - hereFloor.top();
		if (rise <= 0.1 || rise > 1.2) {
			return;
		}
		// Headroom above the destination, and at the player's own head.
		BlockInfo destHead = getBlock(node.x, node.y, node.z, dx, 2, dz);
		BlockInfo hereHead = getBlock(node.x, node.y, node.z, 0, 2, 0);
		if (!destHead.safe || !hereHead.safe) {
			return;
		}
		// The two cells the body sweeps through must be clear AT THE NEW HEIGHT.
		BlockInfo sideA = getBlock(node.x, node.y, node.z, 0, 1, dz);
		BlockInfo sideB = getBlock(node.x, node.y, node.z, dx, 1, 0);
		if (sideA.physical || sideB.physical) {
			return;
		}
		List<BlockPos> toBreak = new ArrayList<>();
		double cost = 2 + Math.sqrt(2) + safeOrBreak(destHead, toBreak);
		if (cost > IMPOSSIBLE) {
			return;
		}
		out.add(new Move(destFloor.pos.getX(), destFloor.pos.getY() + 1, destFloor.pos.getZ(),
				node.remainingBlocks, cost, toBreak, new ArrayList<>(), false));
	}

	/**
	 * Ascend: jump straight up onto a ledge one block higher, cardinal or diagonal.
	 *
	 * <p>The ordinary {@link #getMoveJumpUp} sweeps through the ledge's own face and refuses
	 * this, so a staircase built against a wall — or any step whose riser is beside the player
	 * rather than under the destination — had no move at all. The search then reported the
	 * route unreachable and the bot stopped dead at the bottom of a one-block step.
	 *
	 * <p>Mirrors Baritone's {@code MovementAscend}: the move is legal when the destination is
	 * standable and the rise is one block. No lateral sweep and no headroom requirement — the
	 * walker keeps moving toward the step and presses jump once it is close and its head is
	 * clear ({@code Physics#jumpAllowedNow}), which is how the ceiling over the cell BEHIND
	 * the step stops mattering. Requiring headroom here rejected the move outright and left
	 * the bot standing at a step it could reach with one more stride.
	 */
	public void getMoveHopUpOntoLedge(Move node, int dx, int dz, List<Move> out) {
		BlockInfo dest = getBlock(node.x, node.y, node.z, dx, 1, dz);
		if (dest.physical || !dest.safe) {
			return; // the cell above the ledge must be open for the body
		}
		BlockInfo destFloor = getBlock(node.x, node.y, node.z, dx, 0, dz);
		if (!destFloor.physical) {
			return; // no ledge here: that is a plain walk, handled elsewhere
		}
		// The rise must be a step, not a cliff, measured from the surface the player is on.
		BlockInfo hereFloor = getBlock(node.x, node.y, node.z, 0, -1, 0);
		double rise = destFloor.top() - hereFloor.top();
		if (rise <= 0.1 || rise > 1.2) {
			return;
		}
		List<BlockPos> toBreak = new ArrayList<>();
		double cost = 2 + safeOrBreak(getBlock(node.x, node.y, node.z, dx, 2, dz), toBreak);
		if (cost > IMPOSSIBLE) {
			return;
		}
		out.add(new Move(dest.pos.getX(), dest.pos.getY(), dest.pos.getZ(),
				node.remainingBlocks, cost, toBreak, new ArrayList<>(), false));
	}

	/** The cell you end up in after falling in direction {@code (dx,dz)}. */
	private BlockInfo getLandingBlock(Move node, int dx, int dz) {
		BlockInfo land = getBlock(node.x, node.y, node.z, dx, -2, dz);
		while (land.pos.getY() > level.getMinY()) {
			if (land.liquid && land.safe) {
				return land;
			}
			if (land.physical) {
				if (node.y - land.pos.getY() <= maxDropDown) {
					return getBlock(land.pos.getX(), land.pos.getY() + 1, land.pos.getZ());
				}
				return null;
			}
			if (!land.safe) {
				return null;
			}
			land = getBlock(land.pos.getX(), land.pos.getY() - 1, land.pos.getZ());
		}
		return null;
	}

	/**
	 * Climb a ladder: up or down one cell while inside a climbable column.
	 *
	 * <p>A ladder is the only way up a shaft, and without this generator the planner has no
	 * move that gains height inside one — it would tower with scaffolding instead, or declare
	 * the shaft impassable. The whole cell must be climbable, not merely non-solid, so this
	 * never becomes a general "fly up" move.
	 */
	public void getMoveClimb(Move node, List<Move> out) {
		BlockInfo here = getBlock(node.x, node.y, node.z);

		// Entering the ladder from the side or from the floor below it.
		//
		// A climb generator that only works when the CURRENT cell is already a rung can never
		// be boarded: the player stands on the corridor floor next to the shaft, and the first
		// step onto the ladder has no generator at all. That is what left the bot standing at
		// the foot of a working ladder reporting "reached boundary".
		if (!here.climbable) {
			for (int[] dir : CARDINAL) {
				BlockInfo up = getBlock(node.x, node.y + 1, node.z, dir[0], 0, dir[1]);
				BlockInfo at = getBlock(node.x, node.y, node.z, dir[0], 0, dir[1]);
				// Step sideways onto a rung at this height, or step up onto one above.
				if (at.climbable && up.safe) {
					out.add(new Move(at.pos.getX(), at.pos.getY(), at.pos.getZ(),
							node.remainingBlocks, 1.5, new ArrayList<>(), new ArrayList<>(), false));
				}
				if (up.climbable) {
					// The rung one block up: jumping onto the ladder from the floor.
					out.add(new Move(up.pos.getX(), up.pos.getY(), up.pos.getZ(),
							node.remainingBlocks, 2, new ArrayList<>(), new ArrayList<>(), false));
				}
			}
			return;
		}

		for (int dy : new int[]{1, -1}) {
			BlockInfo next = getBlock(node.x, node.y + dy, node.z);
			if (!next.climbable) {
				// Leaving the ladder at the top: step off onto solid ground.
				if (dy > 0) {
					for (int[] dir : CARDINAL) {
						BlockInfo out2 = getBlock(node.x, node.y + 1, node.z, dir[0], 0, dir[1]);
						BlockInfo floor = getBlock(out2.pos.getX(), out2.pos.getY() - 1, out2.pos.getZ());
						if (out2.safe && floor.physical) {
							out.add(new Move(out2.pos.getX(), out2.pos.getY(), out2.pos.getZ(),
									node.remainingBlocks, 1.5, new ArrayList<>(), new ArrayList<>(), false));
						}
					}
				}
				continue;
			}
			List<BlockPos> toBreak = new ArrayList<>();
			// Headroom is only needed above the destination, and only when going up.
			if (dy > 0) {
				double cost = 1.5 + safeOrBreak(getBlock(node.x, node.y + 2, node.z), toBreak);
				if (cost > IMPOSSIBLE) {
					continue;
				}
				out.add(new Move(node.x, node.y + 1, node.z,
						node.remainingBlocks, cost, toBreak, new ArrayList<>(), false));
			} else {
				out.add(new Move(node.x, node.y - 1, node.z,
						node.remainingBlocks, 1, toBreak, new ArrayList<>(), false));
			}
		}
	}

	/**
	 * A long fall that ends in water, which is survivable and is the only way down a big drop.
	 *
	 * <p>{@link #maxDropDown} deliberately caps ordinary falls at four blocks, because landing
	 * on stone from higher hurts or kills. Water changes the physics: a player who lands in
	 * water takes no fall damage at all, so a ten-block drop into a pool is a normal route.
	 * Without this the planner treats such a drop as a dead end and the course becomes
	 * unsolvable — the exact case a "cliff with a pool at the bottom" tests.
	 */
	public void getMoveFallIntoWater(Move node, int dx, int dz, List<Move> out) {
		// The cell in front must be clear to step off into, at both body heights.
		BlockInfo b = getBlock(node.x, node.y, node.z, dx, 1, dz);
		BlockInfo c = getBlock(node.x, node.y, node.z, dx, 0, dz);
		if (!b.safe || !c.safe) {
			return;
		}
		// Walk straight down the column until something is hit.
		BlockInfo land = getBlock(node.x, node.y, node.z, dx, -1, dz);
		while (land.pos.getY() > level.getMinY()) {
			if (land.liquid && land.safe) {
				// Water deep enough to break the fall, and the cell above it is free.
				BlockInfo above = getBlock(land.pos.getX(), land.pos.getY() + 1, land.pos.getZ());
				if (!above.safe) {
					return;
				}
				int drop = node.y - land.pos.getY();
				if (drop <= maxDropDown) {
					return; // a short drop is already covered by the ordinary generator
				}
				// Deeper falls are worth more, but never so much that a route prefers them.
				double cost = 2 + Math.min(6, drop * 0.25);
				out.add(new Move(land.pos.getX(), land.pos.getY(), land.pos.getZ(),
						node.remainingBlocks, cost, new ArrayList<>(), new ArrayList<>(), false));
				return;
			}
			if (land.physical) {
				return; // solid ground: an ordinary (short) fall, not a water landing
			}
			if (!land.safe) {
				return; // lava or a hazard on the way down
			}
			land = getBlock(land.pos.getX(), land.pos.getY() - 1, land.pos.getZ());
		}
	}

	/** Cost of breaking a block we already decided to break. */
	private double hardBreakCost(BlockInfo b, List<BlockPos> toBreak) {
		toBreak.add(b.pos);
		double h = hardness(b.pos);
		return h < 0 ? IMPOSSIBLE : (1 + 3 * h) * digCost;
	}

	/**
	 * Cost of squeezing diagonally past a corner, given what each flank costs to clear.
	 *
	 * <p>Split out as a pure function because the rule is easy to get subtly wrong and
	 * expensive to get wrong: taking the cheaper flank (the natural-looking choice) makes the
	 * planner accept corners a 0.6-block-wide body cannot physically pass, which shows up as a
	 * bot that repeatedly walks into the same wall corner and re-plans forever.
	 *
	 * @return the step's flank cost, or {@link #IMPOSSIBLE} when the corner is impassable
	 */
	static double diagonalFlankCost(double cost1, double cost2) {
		if (cost1 >= IMPOSSIBLE || cost2 >= IMPOSSIBLE) {
			return IMPOSSIBLE;
		}
		return Math.max(cost1, cost2);
	}

	// ------------------------------------------------------------------
	// neighbour dispatch
	// ------------------------------------------------------------------

	private static final int[][] CARDINAL = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
	private static final int[][] DIAGONAL = {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}};

	/**
	 * Everywhere reachable from this node in one move, with the edits each move needs.
	 *
	 * <p>The order and coverage mirror the original: four cardinals with forward / jump-up /
	 * drop-down / parkour, then four diagonals, then straight down and straight up. Anything
	 * missing here is a route A* can never find — the reason the hand-written version kept
	 * running out of options was that it only had a handful of these.
	 */
	public List<Move> getNeighbors(Move node) {
		List<Move> neighbors = new ArrayList<>();
		for (int[] dir : CARDINAL) {
			getMoveForward(node, dir[0], dir[1], neighbors);
			getMoveJumpUp(node, dir[0], dir[1], neighbors);
			getMoveHopUpOntoLedge(node, dir[0], dir[1], neighbors);
			getMoveDropDown(node, dir[0], dir[1], neighbors);
			getMoveFallIntoWater(node, dir[0], dir[1], neighbors);
			getMoveSwimOut(node, dir[0], dir[1], neighbors);
			if (allowParkour) {
				getMoveParkourForward(node, dir[0], dir[1], neighbors);
			}
		}
		for (int[] dir : DIAGONAL) {
			getMoveDiagonal(node, dir[0], dir[1], neighbors);
			getMoveDiagonalJumpUp(node, dir[0], dir[1], neighbors);
		}
		getMoveDown(node, neighbors);
		getMoveUp(node, neighbors);
		getMoveClimb(node, neighbors);
		// One choke point enforcing the invariant that matters most: the planner may only
		// propose cells the player can actually end up standing in.
		//
		// <p>Without this the generators can emit a node that no movement mode can reach — a
		// cell with no floor under it, or with the body's space still solid — and the two
		// halves of the navigator disagree. A* routes through it, the executor refuses the
		// step every tick, and the bot re-plans into the identical route until it gives up.
		// That disagreement is the whole "walks part of the maze, then freezes" family of bugs,
		// so it is checked here rather than trusted from each generator.
		neighbors.removeIf(m -> !occupiableAfter(m));
		// Drop transitions the walker has already proved it cannot perform from this cell.
		if (!forbidden.isEmpty()) {
			neighbors.removeIf(m -> forbidden.contains(moveKey(node, m)));
		}
		return neighbors;
	}

	/**
	 * True when the player can occupy this node's cell once the move's own edits are applied.
	 *
	 * <p>The edits are what make the difference: bridging and towering deliberately target
	 * cells that are not yet standable, because the same move places the block that becomes
	 * the floor. So a block scheduled in {@code toPlace} counts as support, and one scheduled
	 * in {@code toBreak} no longer blocks the body.
	 *
	 * <p>A climbable cell is its own kind of footing. On a ladder the player does not stand on
	 * anything — they hold the rung — so requiring a floor beneath would reject every rung and
	 * make a ladder shaft impassable. That is exactly what happened when this check was added:
	 * the planner reported "reached boundary" at the foot of a perfectly good ladder.
	 */
	private boolean occupiableAfter(Move m) {
		BlockPos feet = new BlockPos(m.x, m.y, m.z);
		if (solidAfter(feet, m) || solidAfter(feet.above(), m)) {
			return false; // the body would be inside something
		}
		// Hanging on a ladder or vine counts as being able to occupy the cell.
		if (getBlock(feet.getX(), feet.getY(), feet.getZ()).climbable) {
			return true;
		}
		BlockPos floor = feet.below();
		BlockInfo f = getBlock(floor.getX(), floor.getY(), floor.getZ());
		if (f.physical || m.toPlace.contains(floor)) {
			return true; // solid ground, or one the move is about to place
		}
		// Water is footing too, matching how the executor judges a landing.
		return f.liquid && f.safe;
	}

	/** True when this cell still blocks the body after the move's edits. */
	private boolean solidAfter(BlockPos pos, Move m) {
		if (m.toBreak.contains(pos)) {
			return false; // scheduled to be mined out
		}
		BlockInfo b = getBlock(pos.getX(), pos.getY(), pos.getZ());
		return b.physical;
	}
}
