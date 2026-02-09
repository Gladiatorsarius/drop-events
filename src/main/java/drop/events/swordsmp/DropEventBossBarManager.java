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
public final class DropEventBossBarManager {
	private static final Map<UUID, ServerBossEvent> BARS = new ConcurrentHashMap<>();

	private DropEventBossBarManager() {}

	public static void createBossBarForDisplay(ServerLevel world, Display.ItemDisplay display) {
		if (world == null || display == null) return;

		UUID shulkerId = linkedShulkerUuidFromDisplay(display);
		if (shulkerId == null) return;

		// if already created, refresh title instead
		ServerBossEvent existing = BARS.get(shulkerId);
		if (existing != null) {
			updateTitle(existing, display);
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

		MinecraftServer server = world.getServer();
		if (server == null) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			bar.addPlayer(player);
		}
	}

	public static void removeForShulker(Shulker shulker) {
		if (shulker == null) return;
		UUID id = shulker.getUUID();
		ServerBossEvent bar = BARS.remove(id);
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
		if (bar == null) return;
		if (world == null) return;
		MinecraftServer server = world.getServer();
		if (server == null) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			bar.removePlayer(player);
		}
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
