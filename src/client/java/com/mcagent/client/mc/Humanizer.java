package com.mcagent.client.mc;

import com.mcagent.config.BotConfig;
import com.mcagent.config.ConfigManager;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Makes the bot's movement look like a real player's.
 *
 * <p>The stock executor calls {@code absSnapRotationTo}, which rotates the head the full
 * way in a single tick. A server that measures per-tick rotation delta sees an instant
 * 180-degree flip — a textbook automation signature. Real players move the mouse, so the
 * head sweeps toward the target over several ticks and never lands exactly on it.
 *
 * <p>This class rate-limits the turn and adds a small persistent aiming error. The error is
 * regenerated only when the aim target changes meaningfully, so the head does not jitter
 * back and forth every tick.
 */
public final class Humanizer {

	private Humanizer() {
	}

	/** Current aim target (the point we are trying to look at), or null when idle. */
	private static Vec3 aimTarget;
	/** Persistent per-target aiming error, so the crosshair does not oscillate. */
	private static double errX, errY, errZ;
	/** Ticks to wait before the next action may start (simulated reaction time). */
	private static int actionDelayTicks;

	private static BotConfig cfg() {
		return ConfigManager.get();
	}

	/**
	 * Turns the head toward {@code target}, at most a few degrees per tick.
	 *
	 * <p>Call this every tick while aiming at the same point; the head converges over
	 * several ticks. Falls back to an instant snap when humanizing is switched off.
	 */
	public static void lookAt(LocalPlayer p, Vec3 target) {
		BotConfig c = cfg();
		if (!c.humanizeLook) {
			snap(p, target);
			return;
		}
		// regenerate the aiming error when we start aiming somewhere new
		if (aimTarget == null || aimTarget.distanceToSqr(target) > 0.25) {
			aimTarget = target;
			double j = Math.max(0, c.humanizeAimJitter);
			errX = rnd(-j, j);
			errY = rnd(-j, j);
			errZ = rnd(-j, j);
		}
		Vec3 aim = target.add(errX, errY, errZ);
		Vec3 eye = p.getEyePosition();

		double dx = aim.x - eye.x;
		double dy = aim.y - eye.y;
		double dz = aim.z - eye.z;
		double horiz = Math.sqrt(dx * dx + dz * dz);

		float wantYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
		float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
		wantPitch = Mth.clamp(wantPitch, -89.9f, 89.9f);

		float curYaw = p.getYRot();
		float curPitch = p.getXRot();

		// shortest angular distance, so we never spin the long way round
		float dYaw = Mth.wrapDegrees(wantYaw - curYaw);
		float dPitch = wantPitch - curPitch;

		float maxYaw = (float) Math.max(0.5, c.humanizeMaxYawPerTick);
		float maxPitch = (float) Math.max(0.5, c.humanizeMaxPitchPerTick);

		// ease out as we approach, the way a hand slows down onto a target
		float stepYaw = clampStep(dYaw, maxYaw);
		float stepPitch = clampStep(dPitch, maxPitch);

		// a touch of noise so identical turns never repeat exactly
		stepYaw += (float) rnd(-0.35, 0.35);
		stepPitch += (float) rnd(-0.25, 0.25);

		p.absSnapRotationTo(Mth.wrapDegrees(curYaw + stepYaw), Mth.clamp(curPitch + stepPitch, -89.9f, 89.9f));
	}

	/** True when the head is close enough to the aim target to interact accurately. */
	public static boolean isAimedAt(LocalPlayer p, Vec3 target, double toleranceDeg) {
		Vec3 aim = aimTarget == null ? target : aimTarget.add(errX, errY, errZ);
		Vec3 eye = p.getEyePosition();
		double dx = aim.x - eye.x;
		double dy = aim.y - eye.y;
		double dz = aim.z - eye.z;
		double horiz = Math.sqrt(dx * dx + dz * dz);
		float wantYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
		float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
		float dYaw = Math.abs(Mth.wrapDegrees(wantYaw - p.getYRot()));
		float dPitch = Math.abs(wantPitch - p.getXRot());
		return dYaw <= toleranceDeg && dPitch <= toleranceDeg;
	}

	/** Forgets the current aim target; call when an action ends. */
	public static void clearAim() {
		aimTarget = null;
	}

	/**
	 * Blocks until this action's simulated reaction time has elapsed.
	 *
	 * <p>Call on the game thread at the start of an action. Returns immediately once the
	 * delay has been consumed, so a long-running action pays the cost only once.
	 */
	public static boolean tickActionDelay() {
		BotConfig c = cfg();
		if (actionDelayTicks <= 0) {
			int lo = Math.max(0, Math.min(c.humanizeActionDelayMin, c.humanizeActionDelayMax));
			int hi = Math.max(lo, Math.max(c.humanizeActionDelayMin, c.humanizeActionDelayMax));
			// 50ms per tick; the delay is expressed in milliseconds in the config
			actionDelayTicks = Math.max(1, (lo == hi ? lo : ThreadLocalRandom.current().nextInt(lo, hi + 1)) / 50);
		}
		actionDelayTicks--;
		return actionDelayTicks <= 0;
	}

	/** Random pause in ticks, added between repeated block interactions. */
	public static int interactJitterTicks() {
		int j = Math.max(0, cfg().humanizeInteractJitterTicks);
		return j == 0 ? 0 : ThreadLocalRandom.current().nextInt(0, j + 1);
	}

	/** Resets per-action state. Call when an action completes or is cancelled. */
	public static void reset() {
		aimTarget = null;
		actionDelayTicks = 0;
	}

	private static void snap(LocalPlayer p, Vec3 target) {
		Vec3 eye = p.getEyePosition();
		double dx = target.x - eye.x;
		double dy = target.y - eye.y;
		double dz = target.z - eye.z;
		double horiz = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
		p.absSnapRotationTo(Mth.wrapDegrees(yaw), Mth.clamp(pitch, -89.9f, 89.9f));
	}

	/** Moves {@code delta} toward zero by at most {@code max}, easing out near the target. */
	private static float clampStep(float delta, float max) {
		float abs = Math.abs(delta);
		if (abs <= max) {
			return delta;
		}
		// slow down over the last stretch instead of stopping dead
		float factor = abs > max * 4 ? 1.0f : 0.65f;
		return Math.signum(delta) * max * factor;
	}

	private static double rnd(double lo, double hi) {
		return lo >= hi ? lo : ThreadLocalRandom.current().nextDouble(lo, hi);
	}
}
