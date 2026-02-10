package drop.events.swordsmp;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
	private static final float SHULKER_MAX_HEALTH = 1000.0f;
	private static final double GUARD_VERTICAL_OFFSET = -1.0;
	private static final double DISPLAY_LINK_OFFSET = -0.5;

	private DropEventLinker() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(DropEventLinker::tickServer);
	}

	private static void tickServer(MinecraftServer server) {
		for (ServerLevel world : server.getAllLevels()) {
			tickWorld(world);
		}
	}

	private static void tickWorld(ServerLevel world) {
		List<Display.ItemDisplay> displays = new ArrayList<>();
		world.getEntities(EntityType.ITEM_DISPLAY, display -> display.getTags().contains(TAG_TRIGGER), displays);

		for (Display.ItemDisplay display : displays) {
			handleDisplay(world, display);
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
			dropDisplayItemAndRemove(world, display);
			return;
		}

		configureShulker(world, shulker);
		ensureLinkTag(shulker, TAG_LINKED_DISPLAY_PREFIX, display.getUUID());
		teleportToDisplay(shulker, guardAnchor(display));
	}

	private static void spawnShulker(ServerLevel world, Display.ItemDisplay display) {
		Shulker shulker = (Shulker) EntityType.SHULKER.create(world, EntitySpawnReason.TRIGGERED);
		if (shulker == null) {
			return;
		}

		teleportToDisplay(shulker, display.position());
		configureShulker(world, shulker);
		ensureLinkTag(shulker, TAG_LINKED_DISPLAY_PREFIX, display.getUUID());
		ensureLinkTag(display, TAG_LINKED_SHULKER_PREFIX, shulker.getUUID());
		applyHealth(shulker);
		world.addFreshEntity(shulker);
		teleportDisplayToShulker(display, shulker);
	}

	private static void teleportDisplayToShulker(Display.ItemDisplay display, LivingEntity shulker) {
		Vec3 pos = shulker.position();
		display.teleportTo(pos.x, pos.y + DISPLAY_LINK_OFFSET, pos.z);
		display.setDeltaMovement(0.0, 0.0, 0.0);
	}

	private static void teleportToDisplay(LivingEntity shulker, Vec3 pos) {
		shulker.teleportTo(pos.x, pos.y, pos.z);
		shulker.setDeltaMovement(0.0, 0.0, 0.0);
	}

	private static Vec3 guardAnchor(Display.ItemDisplay display) {
		Vec3 pos = display.position();
		return new Vec3(pos.x, pos.y + GUARD_VERTICAL_OFFSET, pos.z);
	}

	private static void applyHealth(LivingEntity entity) {
		AttributeInstance maxHealth = entity.getAttribute(Attributes.MAX_HEALTH);
		if (maxHealth != null) {
			maxHealth.setBaseValue(SHULKER_MAX_HEALTH);
		}
		entity.setHealth(SHULKER_MAX_HEALTH);
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
		for (String tag : new ArrayList<>(tags)) {
			if (tag.startsWith(prefix) && !tag.equals(prefix + uuid)) {
				entity.removeTag(tag);
			}
		}
		entity.addTag(prefix + uuid);
	}

	private static void configureShulker(ServerLevel world, Shulker shulker) {
		shulker.setNoGravity(true);
		shulker.setNoAi(true);
		shulker.setSilent(true);
		boolean shouldRenderModel = Boolean.TRUE.equals(world.getGameRules().get(DropEventRules.GUARD_VISIBLE));
		shulker.setInvisible(!shouldRenderModel);
		if (!shulker.getTags().contains(TAG_SHULKER)) {
			shulker.addTag(TAG_SHULKER);
		}
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
