package drop.events.swordsmp;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.item.Items;

final class DropEventShulkerDropListener {
	private static final double DROP_RADIUS = 2.5;

	private DropEventShulkerDropListener() {
	}

	static void register() {
			ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (!(entity instanceof Shulker shulker)) {
				return;
			}

			if (!shulker.getTags().contains(DropEventLinker.TAG_SHULKER)) {
				return;
			}

				// Remove boss bar (if shown) for this shulker, then perform the normal drop handling
				DropEventBossBarManager.removeForShulker(shulker);
				removeVanillaDrops(shulker);
				DropEventLinker.dropLinkedDisplayItem(shulker);
		});
	}

	private static void removeVanillaDrops(Shulker shulker) {
		if (!(shulker.level() instanceof ServerLevel world)) {
			return;
		}

		List<ItemEntity> itemDrops = new ArrayList<>();
		world.getEntities(EntityType.ITEM, entity -> entity.distanceTo(shulker) <= DROP_RADIUS, itemDrops);
		for (ItemEntity drop : itemDrops) {
			if (drop.getItem().getItem() == Items.SHULKER_SHELL) {
				drop.discard();
			}
		}

		List<ExperienceOrb> xpDrops = new ArrayList<>();
		world.getEntities(EntityType.EXPERIENCE_ORB, orb -> orb.distanceTo(shulker) <= DROP_RADIUS, xpDrops);
		for (ExperienceOrb orb : xpDrops) {
			orb.discard();
		}
	}
}