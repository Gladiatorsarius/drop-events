package drop.events.swordsmp;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.world.entity.monster.Shulker;

final class DropEventShulkerDropListener {
	private DropEventShulkerDropListener() {}

	static void register() {
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof Shulker shulker && shulker.getTags().contains(DropEventLinker.TAG_SHULKER)) {
				DropEventBossBarManager.removeForShulker(shulker);
				DropEventLinker.dropLinkedDisplayItem(shulker);
			}
		});
	}
}
