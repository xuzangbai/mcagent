package com.mcagent.client.pathing;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mcagent.MCAgentMod;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Draws the navigator's route in the world, the way Baritone does.
 *
 * <p>Without this the navigator is unfalsifiable from the outside: a bot standing still looks
 * identical whether it is searching, following a bad route, or blocked by terrain no planner
 * could cross. Drawing the route turns that into something you can simply look at — the line
 * shows where the path goes, the yellow cube is the node being walked to right now, red cubes
 * are blocks the plan intends to break, blue cubes are blocks it intends to place.
 *
 * <p>Rendering only reads {@link PathExecutor}'s current route; it never affects navigation.
 */
public final class PathRenderer {

	/** Route line: green, so it reads as "planned path" at a glance. */
	private static final float[] LINE = {0.25f, 0.95f, 0.35f, 0.85f};
	/** The node the executor is currently walking toward. */
	private static final float[] NEXT = {1.0f, 0.85f, 0.2f, 0.95f};
	/** Blocks the route intends to break. */
	private static final float[] BREAK = {1.0f, 0.30f, 0.25f, 0.95f};
	/** Blocks the route intends to place. */
	private static final float[] PLACE = {0.30f, 0.60f, 1.0f, 0.95f};

	/** Never draw more than this many nodes: a long route becomes an unreadable smear. */
	private static final int MAX_NODES = 300;

	/** Line thickness in pixels; the line vertex format requires it. */
	private static final float LINE_WIDTH = 2.5f;

	private static long lastDiagAt;
	private static String lastError;

	private PathRenderer() {
	}

	public static void register(PathExecutor executor) {
		WorldRenderEvents.AFTER_ENTITIES.register(context -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.level == null || mc.player == null) {
				return;
			}
			List<Move> route = executor.routeSnapshot();
			// A failure here must be visible, not swallowed: silently catching it is
			// indistinguishable from "there is no route to draw", which is exactly the
			// ambiguity this renderer exists to remove.
			try {
				diagnose(context, route);
				if (route.isEmpty()) {
					return;
				}
				draw(context, route);
			} catch (Throwable t) {
				report("draw failed: " + t);
			}
		});
	}

	/**
	 * Logs the facts needed to place the line correctly: how big the route is, where the
	 * camera is, and what the matrix stack already encodes.
	 *
	 * <p>The matrix translation is the part that matters. If the stack handed to world render
	 * events is already camera-relative, its translation is about {@code -cameraPos}; if it is
	 * identity, it is zero — and block coordinates then have to be offset by hand. Guessing
	 * this wrong draws the route tens of blocks away from the blocks it describes.
	 */
	private static void diagnose(WorldRenderContext context, List<Move> route) {
		if (!com.mcagent.config.ConfigManager.get().pathDebug) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastDiagAt < 1000) {
			return;
		}
		lastDiagAt = now;
		PoseStack pose = context.matrices();
		Vec3 cam = context.gameRenderer().getMainCamera().position();
		String t = "n/a";
		if (pose != null) {
			Matrix4f m = pose.last().pose();
			t = String.format("%.2f,%.2f,%.2f", m.m30(), m.m31(), m.m32());
		}
		report("route=" + route.size() + " cam=" + String.format("%.2f,%.2f,%.2f", cam.x, cam.y, cam.z)
				+ " poseTranslation=" + t);
	}

	private static void report(String message) {
		if (message.equals(lastError)) {
			return;
		}
		lastError = message;
		MCAgentMod.LOGGER.info("[path-render] {}", message);
	}

	private static void draw(WorldRenderContext context, List<Move> route) {
		PoseStack pose = context.matrices();
		MultiBufferSource buffers = context.consumers();
		if (pose == null || buffers == null) {
			return;
		}
		VertexConsumer out = buffers.getBuffer(RenderTypes.lines());
		if (out == null) {
			return;
		}

		// Vanilla world rendering works in camera-relative space: entities are drawn at
		// (entityPos - cameraPos). So a vertex meant to sit at world coordinate B must be
		// emitted at (B - camera), minus whatever translation the stack already carries.
		//
		// Deriving the offset from the matrix instead of assuming a convention is what makes
		// this correct either way: if the stack is identity the offset is -camera, and if it
		// is already camera-relative the offset comes out zero. Assuming wrong puts the whole
		// route tens of blocks away from the blocks it describes.
		Vec3 cam = context.gameRenderer().getMainCamera().position();
		Matrix4f m = pose.last().pose();
		float ox = (float) -cam.x - m.m30();
		float oy = (float) -cam.y - m.m31();
		float oz = (float) -cam.z - m.m32();

		pose.pushPose();
		pose.translate(ox, oy, oz);
		PoseStack.Pose entry = pose.last();
		try {
			int limit = Math.min(route.size(), MAX_NODES);
			for (int i = 0; i + 1 < limit; i++) {
				Move a = route.get(i);
				Move b = route.get(i + 1);
				// Lifted off the floor so the line is not swallowed by the block surface.
				line(out, entry, a.x + 0.5f, a.y + 0.20f, a.z + 0.5f,
						b.x + 0.5f, b.y + 0.20f, b.z + 0.5f, LINE);
			}
			for (int i = 0; i < limit; i++) {
				Move node = route.get(i);
				box(out, entry, node.x, node.y, node.z, i == 0 ? NEXT : LINE);
				for (BlockPos pos : node.toBreak) {
					box(out, entry, pos.getX(), pos.getY(), pos.getZ(), BREAK);
				}
				for (BlockPos pos : node.toPlace) {
					box(out, entry, pos.getX(), pos.getY(), pos.getZ(), PLACE);
				}
			}
		} finally {
			pose.popPose();
		}
	}

	/** A flat wireframe box sitting on the floor of a cell. */
	private static void box(VertexConsumer out, PoseStack.Pose pose, int x, int y, int z, float[] c) {
		float x0 = x + 0.12f;
		float x1 = x + 0.88f;
		float z0 = z + 0.12f;
		float z1 = z + 0.88f;
		float y0 = y + 0.03f;
		float y1 = y + 0.30f;
		line(out, pose, x0, y0, z0, x1, y0, z0, c);
		line(out, pose, x1, y0, z0, x1, y0, z1, c);
		line(out, pose, x1, y0, z1, x0, y0, z1, c);
		line(out, pose, x0, y0, z1, x0, y0, z0, c);
		line(out, pose, x0, y1, z0, x1, y1, z0, c);
		line(out, pose, x1, y1, z0, x1, y1, z1, c);
		line(out, pose, x1, y1, z1, x0, y1, z1, c);
		line(out, pose, x0, y1, z1, x0, y1, z0, c);
		line(out, pose, x0, y0, z0, x0, y1, z0, c);
		line(out, pose, x1, y0, z0, x1, y1, z0, c);
		line(out, pose, x1, y0, z1, x1, y1, z1, c);
		line(out, pose, x0, y0, z1, x0, y1, z1, c);
	}

	private static void line(VertexConsumer out, PoseStack.Pose pose,
			float x1, float y1, float z1, float x2, float y2, float z2, float[] c) {
		float nx = x2 - x1;
		float ny = y2 - y1;
		float nz = z2 - z1;
		// setLineWidth is mandatory for RenderTypes.lines(): the vertex format carries a
		// LineWidth element, and omitting it corrupts the shared line buffer so the game
		// throws "Missing elements in vertex: LineWidth" when it later flushes it.
		out.addVertex(pose, x1, y1, z1).setColor(c[0], c[1], c[2], c[3])
				.setNormal(pose, nx, ny, nz).setLineWidth(LINE_WIDTH);
		out.addVertex(pose, x2, y2, z2).setColor(c[0], c[1], c[2], c[3])
				.setNormal(pose, nx, ny, nz).setLineWidth(LINE_WIDTH);
	}
}
