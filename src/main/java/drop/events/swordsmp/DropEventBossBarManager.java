package drop.events.swordsmp;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.monster.Shulker;

/**
 * Manages a simple mapping from a linked shulker UUID to a server-side BossBar
 * that is shown to all players while the shulker exists.
 */
import java.util.WeakHashMap;
import net.minecraft.world.entity.LivingEntity;

public final class DropEventBossBarManager {
	// Map shulker UUID to BossBar
	private static final Map<UUID, ServerBossEvent> BARS = new ConcurrentHashMap<>();
	// Map shulker UUID to shulker entity (weak ref, not for lifecycle, just for health tracking)
	private static final Map<UUID, Shulker> SHULKERS = new WeakHashMap<>();

	private DropEventBossBarManager() {}

	public static void createBossBarForDisplay(ServerLevel world, Display.ItemDisplay display, Shulker shulker) {
		if (world == null || display == null || shulker == null) return;
		UUID shulkerId = shulker.getUUID();
		// if already created, refresh title instead
		ServerBossEvent existing = BARS.get(shulkerId);
		if (existing != null) {
			updateTitle(existing, display);
			SHULKERS.put(shulkerId, shulker);
			return;
		}
		Component title = Component.literal(
			String.format("%s at %d, %d, %d",
				display.getItemStack().getHoverName().getString(),
				Math.round(display.getX()),
				Math.round(display.getY()),
				Math.round(display.getZ())));
		ServerBossEvent bar = new ServerBossEvent(title, BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.PROGRESS);
		BARS.put(shulkerId, bar);
		SHULKERS.put(shulkerId, shulker);
		MinecraftServer server = world.getServer();
		if (server == null) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			bar.addPlayer(player);
		}
		// Set initial progress
		updateProgress(bar, shulker);
	}

	public static void removeForShulker(Shulker shulker) {
		if (shulker == null) return;
		UUID id = shulker.getUUID();
		ServerBossEvent bar = BARS.remove(id);
		SHULKERS.remove(id);
		if (bar == null) return;
		if (shulker.level() instanceof ServerLevel world) {
			MinecraftServer server = world.getServer();
			if (server != null) {
				for (ServerPlayer player : server.getPlayerList().getPlayers()) {
					bar.removePlayer(player);
				}
			}
		}
	}

	public static void removeForShulker(UUID id, ServerLevel world) {
		ServerBossEvent bar = BARS.remove(id);
		SHULKERS.remove(id);
		if (bar == null) return;
		if (world == null) return;
		MinecraftServer server = world.getServer();
		if (server == null) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			bar.removePlayer(player);
		}
	}
	// Called every server tick to update all bossbars' progress
	public static void updateAllBossBarProgress() {
		for (Map.Entry<UUID, ServerBossEvent> entry : BARS.entrySet()) {
			UUID shulkerId = entry.getKey();
			ServerBossEvent bar = entry.getValue();
			Shulker shulker = SHULKERS.get(shulkerId);
			if (shulker != null && shulker.isAlive()) {
				updateProgress(bar, shulker);
			} else {
				// Remove bar if shulker is dead
				BARS.remove(shulkerId);
				SHULKERS.remove(shulkerId);
			}
		}
	}

	private static void updateProgress(ServerBossEvent bar, Shulker shulker) {
		if (bar == null || shulker == null) return;
		float max = shulker.getMaxHealth();
		float hp = shulker.getHealth();
		float percent = (max > 0) ? (hp / max) : 0f;
		bar.setProgress(percent);
	}

	private static void updateTitle(ServerBossEvent bar, Display.ItemDisplay display) {
		if (bar == null || display == null) return;
		Component title = Component.literal(
			String.format("%s at %d, %d, %d",
				display.getItemStack().getHoverName().getString(),
				Math.round(display.getX()),
				Math.round(display.getY()),
				Math.round(display.getZ())));
		bar.setName(title);
	}

	private static UUID linkedShulkerUuidFromDisplay(Display.ItemDisplay display) {
		for (String tag : display.getTags()) {
			if (tag.startsWith("linked_shulker:")) {
				try {
					return UUID.fromString(tag.substring("linked_shulker:".length()));
				} catch (IllegalArgumentException ignored) {
					return null;
				}
			}
		}
		return null;
	}
}
