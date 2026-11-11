package drop.events.swordsmp;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.world.entity.monster.Shulker;

final class DropEventShulkerDropListener {
	private DropEventShulkerDropListener() {}

	static void register() {
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof Shulker shulker && shulker.getTags().contains(DropEventLinker.TAG_SHULKER)) {
				boolean running = DropEventState.of(shulker) == DropEventState.State.RUNNING;
				DropEventBossBarManager.removeForShulker(shulker);
				DropEventState.forget(shulker.getUUID());
				// Only a running event pays out. An untouched or paused guard that dies
				// (/kill, void) just removes its display.
				if (running) DropEventLinker.dropLinkedDisplayItem(shulker);
				else DropEventLinker.discardLinkedDisplay(shulker);
			}
		});
	}
}
