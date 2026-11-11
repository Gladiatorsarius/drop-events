package drop.events.swordsmp;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Shulker;

/**
 * Lifecycle of one drop event: no tag = untouched, running, paused.
 * The tag lives on the guard shulker and is mirrored on the display.
 * Server-thread only; the session set is cleared when the server stops.
 */
final class DropEventState {
	enum State { UNTOUCHED, RUNNING, PAUSED }

	static final String TAG_RUNNING = "drop_event_running";
	static final String TAG_PAUSED = "drop_event_paused";
	private static final int RESISTANCE_AMPLIFIER = 4; // Resistance V: immune to most damage
	/** Guards started or resumed during this server session. A running tag outside it is stale. */
	private static final Set<UUID> ACTIVE_THIS_SESSION = new HashSet<>();

	private DropEventState() {}

	static State of(Entity entity) {
		Set<String> tags = entity.getTags();
		if (tags.contains(TAG_RUNNING)) return State.RUNNING;
		if (tags.contains(TAG_PAUSED)) return State.PAUSED;
		return State.UNTOUCHED;
	}

	static void set(Display.ItemDisplay display, Shulker guard, State state) {
		mirror(display, state);
		mirror(guard, state);
		if (state == State.RUNNING) ACTIVE_THIS_SESSION.add(guard.getUUID());
		else ACTIVE_THIS_SESSION.remove(guard.getUUID());
		applyProtection(guard);
	}

	private static void mirror(Entity entity, State state) {
		if (state == State.RUNNING) {
			entity.removeTag(TAG_PAUSED);
			entity.addTag(TAG_RUNNING);
		} else if (state == State.PAUSED) {
			entity.removeTag(TAG_RUNNING);
			entity.addTag(TAG_PAUSED);
		} else {
			entity.removeTag(TAG_RUNNING);
			entity.removeTag(TAG_PAUSED);
		}
	}

	static boolean startedThisSession(UUID guardId) {
		return ACTIVE_THIS_SESSION.contains(guardId);
	}

	static void forget(UUID guardId) {
		ACTIVE_THIS_SESSION.remove(guardId);
	}

	static void clear() {
		ACTIVE_THIS_SESSION.clear();
	}

	/** Untouched and paused guards carry infinite Resistance V; a running guard does not. */
	static void applyProtection(Shulker guard) {
		MobEffectInstance current = guard.getEffect(MobEffects.RESISTANCE);
		boolean ours = current != null && current.getAmplifier() == RESISTANCE_AMPLIFIER && current.isInfiniteDuration();
		boolean protectedState = of(guard) != State.RUNNING;
		if (protectedState && !ours) {
			guard.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, MobEffectInstance.INFINITE_DURATION, RESISTANCE_AMPLIFIER, false, false));
		} else if (!protectedState && ours) {
			guard.removeEffect(MobEffects.RESISTANCE);
		}
	}
}
