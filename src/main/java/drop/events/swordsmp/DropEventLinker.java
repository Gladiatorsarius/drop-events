package drop.events.swordsmp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

public final class DropEventLinker {
	public static final String TAG_TRIGGER = "drop_event_item";
	public static final String TAG_SHULKER = "drop_event_shulker";
	private static final String TAG_LINKED_DISPLAY_PREFIX = "linked_display:";
	private static final String TAG_LINKED_SHULKER_PREFIX = "linked_shulker:";
	private static final double GUARD_VERTICAL_OFFSET = -1.0;
	private static final double DISPLAY_LINK_OFFSET = -0.5;
	/** Consecutive ticks the guard was missing while its chunk was loaded, per display. */
	private static final Map<UUID, Integer> MISSING_TICKS = new HashMap<>();
	static final int MISSING_GUARD_TICKS = 40;
	private static final Map<ServerLevel, Set<Display.ItemDisplay>> DISPLAYS = new HashMap<>();

	private DropEventLinker() {
	}

	public static void register() {
		// Track every loaded display, not just tagged ones: /tag changes must work immediately.
		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			if (entity instanceof Display.ItemDisplay display) {
				DISPLAYS.computeIfAbsent(world, ignored -> new HashSet<>()).add(display);
			}
		});
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
			if (entity instanceof Display.ItemDisplay display) {
				Set<Display.ItemDisplay> displays = DISPLAYS.get(world);
				if (displays != null) {
					displays.remove(display);
					if (displays.isEmpty()) DISPLAYS.remove(world);
				}
				var reason = display.getRemovalReason();
				if (reason != null && reason.shouldDestroy()) removeGuardForDisplay(world, display);
			}
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(DropEventLinker::pauseRunningEvents);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			DISPLAYS.clear();
			MISSING_TICKS.clear();
			DropEventState.clear();
		});
		ServerTickEvents.END_SERVER_TICK.register(DropEventLinker::tickServer);
	}

	/** Shutdown: running events become paused; untouched and paused ones are left alone. */
	private static void pauseRunningEvents(MinecraftServer server) {
		for (ServerLevel world : server.getAllLevels()) {
			Set<Display.ItemDisplay> displays = DISPLAYS.get(world);
			if (displays == null) continue;
			for (Display.ItemDisplay display : new ArrayList<>(displays)) {
				if (!display.getTags().contains(TAG_TRIGGER)) continue;
				UUID id = getLinkedUuid(display, TAG_LINKED_SHULKER_PREFIX);
				if (id != null && world.getEntity(id) instanceof Shulker shulker
						&& DropEventState.of(shulker) == DropEventState.State.RUNNING) {
					DropEventState.set(display, shulker, DropEventState.State.PAUSED);
				}
			}
		}
	}

	private static void tickServer(MinecraftServer server) {
		for (ServerLevel world : server.getAllLevels()) {
			tickWorld(world);
		}
	}

	private static void tickWorld(ServerLevel world) {
		Set<Display.ItemDisplay> displays = DISPLAYS.get(world);
		if (displays == null) return;
		// Handling can discard a display and synchronously fire ENTITY_UNLOAD.
		for (Display.ItemDisplay display : new ArrayList<>(displays)) {
			if (!display.isRemoved() && display.getTags().contains(TAG_TRIGGER)) handleDisplay(world, display);
		}
	}

	private static void handleDisplay(ServerLevel world, Display.ItemDisplay display) {
		UUID shulkerId = getLinkedUuid(display, TAG_LINKED_SHULKER_PREFIX);
		if (shulkerId == null) {
			spawnShulker(world, display);
			return;
		}

		Entity linked = world.getEntity(shulkerId);
		if (!(linked instanceof Shulker shulker)) {
			// world.getEntity is also null for a guard in a chunk that is not loaded yet (the guard
			// sits right below the display and can be across a chunk border). Only a guard that stays
			// missing while its own chunk is ticking counts as lost.
			if (!guardChunkLoaded(world, display)) {
				MISSING_TICKS.remove(display.getUUID());
				return;
			}
			if (MISSING_TICKS.merge(display.getUUID(), 1, Integer::sum) < MISSING_GUARD_TICKS) return;
			MISSING_TICKS.remove(display.getUUID());
			// Lost for real: only a running event pays out; otherwise the display just goes away.
			if (DropEventState.of(display) == DropEventState.State.RUNNING) dropDisplayItemAndRemove(world, display);
			else display.discard();
			return;
		}
		MISSING_TICKS.remove(display.getUUID());

		configureShulker(world, shulker);
		reconcileState(world, display, shulker);
		ensureLinkTag(shulker, TAG_LINKED_DISPLAY_PREFIX, display.getUUID());
		teleportToDisplay(shulker, guardAnchor(display));
	}

	/**
	 * A running tag that this session never started (crash, or unloaded during shutdown) is stale:
	 * pause it. Running and paused events get their bar back after a restart or chunk reload.
	 */
	private static void reconcileState(ServerLevel world, Display.ItemDisplay display, Shulker shulker) {
		DropEventState.State state = DropEventState.of(shulker);
		if (state == DropEventState.State.UNTOUCHED) {
			if (DropEventState.of(display) != state) DropEventState.set(display, shulker, state);
			DropEventState.applyProtection(shulker);
			return;
		}
		if (state == DropEventState.State.RUNNING && !DropEventState.startedThisSession(shulker.getUUID())) {
			DropEventState.set(display, shulker, DropEventState.State.PAUSED);
			state = DropEventState.State.PAUSED;
		}
		DropEventState.applyProtection(shulker);
		DropEventBossBarManager.ensureBar(world, display, shulker, state == DropEventState.State.PAUSED);
	}

	/** True when the chunk that holds the guard (just below the display) is loaded and ticking entities. */
	static boolean guardChunkLoaded(ServerLevel world, Display.ItemDisplay display) {
		return world.isPositionEntityTicking(BlockPos.containing(guardAnchor(display)));
	}

	private static void spawnShulker(ServerLevel world, Display.ItemDisplay display) {
		Shulker shulker = (Shulker) EntityType.SHULKER.create(world, EntitySpawnReason.TRIGGERED);
		if (shulker == null) {
			return;
		}

		teleportToDisplay(shulker, display.position());
		configureShulker(world, shulker);

		// ✅ Apply gamerule-based health ONLY here
		applyHealth(world, shulker);

		ensureLinkTag(shulker, TAG_LINKED_DISPLAY_PREFIX, display.getUUID());
		ensureLinkTag(display, TAG_LINKED_SHULKER_PREFIX, shulker.getUUID());
		// A replacement guard (the old one is gone) continues the display's lifecycle state. A
		// running tag with no session start is then paused by the next reconcile.
		DropEventState.State inherited = DropEventState.of(display);
		if (inherited != DropEventState.State.UNTOUCHED) {
			shulker.addTag(inherited == DropEventState.State.RUNNING ? DropEventState.TAG_RUNNING : DropEventState.TAG_PAUSED);
		}

		world.addFreshEntity(shulker);
		teleportDisplayToShulker(display, shulker);
	}

	private static void teleportDisplayToShulker(Display.ItemDisplay display, LivingEntity shulker) {
		Vec3 pos = shulker.position();
		display.teleportTo(pos.x, pos.y + DISPLAY_LINK_OFFSET, pos.z);
		display.setDeltaMovement(0.0, 0.0, 0.0);
	}

	private static void teleportToDisplay(Shulker shulker, Vec3 pos) {
		// Shulker.setPos snaps to block centers unless it is riding another entity.
		double x = shulker.isPassenger() ? pos.x : Math.floor(pos.x) + 0.5;
		double y = shulker.isPassenger() ? pos.y : Math.floor(pos.y + 0.5);
		double z = shulker.isPassenger() ? pos.z : Math.floor(pos.z) + 0.5;
		if (shulker.getX() != x || shulker.getY() != y || shulker.getZ() != z) {
			shulker.teleportTo(pos.x, pos.y, pos.z);
		}
		if (shulker.getDeltaMovement().lengthSqr() != 0) shulker.setDeltaMovement(0.0, 0.0, 0.0);
	}

	private static Vec3 guardAnchor(Display.ItemDisplay display) {
		Vec3 pos = display.position();
		return new Vec3(pos.x, pos.y + GUARD_VERTICAL_OFFSET, pos.z);
	}

	// ✅ Uses gamerule instead of constant
	private static void applyHealth(ServerLevel world, LivingEntity entity) {
		int health = world.getGameRules().get(DropEventRules.SHULKER_HEALTH);

		AttributeInstance maxHealth = entity.getAttribute(Attributes.MAX_HEALTH);
		if (maxHealth != null) {
			maxHealth.setBaseValue(health);
		}
		entity.setHealth(health);
	}

	private static void dropDisplayItemAndRemove(ServerLevel world, Display.ItemDisplay display) {
		ItemStack stack = display.getItemStack();
		if (!stack.isEmpty()) {
			ItemEntity drop = new ItemEntity(world, display.getX(), display.getY(), display.getZ(), stack.copy());
			world.addFreshEntity(drop);
		}
		display.discard();
	}

	private static UUID getLinkedUuid(Entity entity, String prefix) {
		for (String tag : entity.getTags()) {
			if (tag.startsWith(prefix)) {
				String id = tag.substring(prefix.length());
				try {
					return UUID.fromString(id);
				} catch (IllegalArgumentException ignored) {
					return null;
				}
			}
		}
		return null;
	}

	private static void ensureLinkTag(Entity entity, String prefix, UUID uuid) {
		Set<String> tags = entity.getTags();
		String expected = prefix + uuid;
		if (tags.contains(expected) && tags.stream().filter(tag -> tag.startsWith(prefix)).count() == 1) return;
		for (String tag : new ArrayList<>(tags)) {
			if (tag.startsWith(prefix) && !tag.equals(expected)) {
				entity.removeTag(tag);
			}
		}
		entity.addTag(expected);
	}

	private static void configureShulker(ServerLevel world, Shulker shulker) {
		if (!shulker.isNoGravity()) shulker.setNoGravity(true);
		if (!shulker.isNoAi()) shulker.setNoAi(true);
		if (!shulker.isSilent()) shulker.setSilent(true);
		boolean shouldRenderModel = Boolean.TRUE.equals(world.getGameRules().get(DropEventRules.GUARD_VISIBLE));
		if (shulker.isInvisible() == shouldRenderModel) shulker.setInvisible(!shouldRenderModel);
		if (!shulker.getTags().contains(TAG_SHULKER)) {
			shulker.addTag(TAG_SHULKER);
		}
	}

	static UUID linkedShulkerUuidFromDisplay(Display.ItemDisplay display) {
		return getLinkedUuid(display, TAG_LINKED_SHULKER_PREFIX);
	}

	private static void removeGuardForDisplay(ServerLevel world, Display.ItemDisplay display) {
		UUID id = linkedShulkerUuidFromDisplay(display);
		Entity entity = id == null ? null : world.getEntity(id);
		if (entity instanceof Shulker shulker && shulker.isAlive()
				&& shulker.getTags().contains(TAG_SHULKER)
				&& display.getUUID().equals(getLinkedUuid(shulker, TAG_LINKED_DISPLAY_PREFIX))) {
			DropEventBossBarManager.removeForShulker(shulker);
			shulker.discard();
		}
	}

	static void discardLinkedDisplay(Shulker shulker) {
		if (!(shulker.level() instanceof ServerLevel world)) return;
		UUID displayId = getLinkedUuid(shulker, TAG_LINKED_DISPLAY_PREFIX);
		if (displayId != null && world.getEntity(displayId) instanceof Display.ItemDisplay display) display.discard();
	}

	static void dropLinkedDisplayItem(Shulker shulker) {
		if (!(shulker.level() instanceof ServerLevel world)) {
			return;
		}

		UUID displayId = getLinkedUuid(shulker, TAG_LINKED_DISPLAY_PREFIX);
		if (displayId == null) {
			return;
		}

		Entity entity = world.getEntity(displayId);
		if (entity instanceof Display.ItemDisplay display) {
			dropDisplayItemAndRemove(world, display);
		}
	}
}
