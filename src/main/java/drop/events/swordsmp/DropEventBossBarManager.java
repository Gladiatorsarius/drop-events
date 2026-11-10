package drop.events.swordsmp;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.monster.Shulker;

/** All state is accessed on the server thread and cleared when that server stops. */
public final class DropEventBossBarManager {
	private record TrackedBar(Shulker shulker, ServerBossEvent bar) {}
	private static final Map<UUID, TrackedBar> BARS = new HashMap<>();

	private DropEventBossBarManager() {}

	public static void register() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			for (TrackedBar tracked : BARS.values()) tracked.bar().addPlayer(handler.getPlayer());
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			for (TrackedBar tracked : BARS.values()) tracked.bar().removePlayer(handler.getPlayer());
		});
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
			if (entity instanceof Shulker shulker) removeForShulker(shulker);
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			for (TrackedBar tracked : BARS.values()) tracked.bar().removeAllPlayers();
			BARS.clear();
		});
	}

	/** Returns true only when a new bar is created, not on a repeated start. */
	public static boolean createBossBarForDisplay(ServerLevel world, Display.ItemDisplay display, Shulker shulker) {
		UUID id = shulker.getUUID();
		TrackedBar tracked = BARS.get(id);
		boolean created = tracked == null;
		if (tracked == null) {
			ServerBossEvent bar = new ServerBossEvent(title(display), BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.PROGRESS);
			tracked = new TrackedBar(shulker, bar);
			BARS.put(id, tracked);
		} else {
			tracked.bar().setName(title(display));
		}
		updateProgress(tracked);
		for (var player : world.getServer().getPlayerList().getPlayers()) tracked.bar().addPlayer(player);
		return created;
	}

	public static void removeForShulker(Shulker shulker) {
		removeForShulker(shulker.getUUID());
	}

	public static void removeForShulker(UUID id) {
		TrackedBar tracked = BARS.remove(id);
		if (tracked != null) tracked.bar().removeAllPlayers();
	}

	public static void updateAllBossBarProgress() {
		Iterator<TrackedBar> iterator = BARS.values().iterator();
		while (iterator.hasNext()) {
			TrackedBar tracked = iterator.next();
			if (tracked.shulker().isAlive()) {
				updateProgress(tracked);
			} else {
				tracked.bar().removeAllPlayers();
				iterator.remove();
			}
		}
	}

	private static void updateProgress(TrackedBar tracked) {
		float max = tracked.shulker().getMaxHealth();
		tracked.bar().setProgress(max > 0 ? Math.clamp(tracked.shulker().getHealth() / max, 0f, 1f) : 0f);
	}

	private static Component title(Display.ItemDisplay display) {
		return Component.literal(String.format("%s at %d, %d, %d",
			display.getItemStack().getHoverName().getString(),
			Math.round(display.getX()), Math.round(display.getY()), Math.round(display.getZ())));
	}
}
