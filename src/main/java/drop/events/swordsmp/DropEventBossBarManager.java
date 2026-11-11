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
	private record TrackedBar(Shulker shulker, ServerBossEvent bar, boolean paused, long titleRefreshedAt) {}
	/** The title shows the item name and position, which can change; refresh it this often (ticks). */
	static final long TITLE_REFRESH_TICKS = 20;
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

	/** Creates the bar or retitles an existing one. */
	public static void showBar(ServerLevel world, Display.ItemDisplay display, Shulker shulker, boolean paused) {
		UUID id = shulker.getUUID();
		TrackedBar tracked = BARS.get(id);
		if (tracked == null) {
			ServerBossEvent bar = new ServerBossEvent(title(display, paused), BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.PROGRESS);
			tracked = new TrackedBar(shulker, bar, paused, world.getGameTime());
			BARS.put(id, tracked);
		} else {
			tracked.bar().setName(title(display, paused));
			tracked = new TrackedBar(tracked.shulker(), tracked.bar(), paused, world.getGameTime());
			BARS.put(id, tracked);
		}
		updateProgress(tracked);
		for (var player : world.getServer().getPlayerList().getPlayers()) tracked.bar().addPlayer(player);
	}

	/** Cheap per-tick reconcile: recreates a missing bar or fixes the paused suffix. No announcement. */
	static void ensureBar(ServerLevel world, Display.ItemDisplay display, Shulker shulker, boolean paused) {
		TrackedBar tracked = BARS.get(shulker.getUUID());
		// No string building on the hot path: only a missing bar, a changed paused flag or a
		// title that is due for its bounded refresh needs work.
		if (tracked == null || tracked.paused() != paused
				|| world.getGameTime() - tracked.titleRefreshedAt() >= TITLE_REFRESH_TICKS) {
			showBar(world, display, shulker, paused);
		}
	}

	/** Current bar title, or null when no bar exists. */
	static String titleOf(UUID guardId) {
		TrackedBar tracked = BARS.get(guardId);
		return tracked == null ? null : tracked.bar().getName().getString();
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

	private static Component title(Display.ItemDisplay display, boolean paused) {
		return Component.literal(String.format("%s at %d, %d, %d%s",
			display.getItemStack().getHoverName().getString(),
			Math.round(display.getX()), Math.round(display.getY()), Math.round(display.getZ()), paused ? " (Paused)" : ""));
	}
}
