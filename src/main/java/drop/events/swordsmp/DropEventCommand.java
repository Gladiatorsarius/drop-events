package drop.events.swordsmp;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.phys.Vec3;

public final class DropEventCommand {
	private DropEventCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		var root = Commands.literal("dropevent");

		root.then(Commands.literal("start").executes(DropEventCommand::start));
		root.then(Commands.literal("stop").executes(DropEventCommand::stop));

		dispatcher.register(root);
	}

	private static int start(CommandContext<CommandSourceStack> context) {
		try {
			CommandSourceStack source = context.getSource();
			if (!(source.getLevel() instanceof ServerLevel world)) {
				source.sendFailure(Component.literal("Error: server-only command"));
				return 0;
			}

			List<Display.ItemDisplay> displays = new ArrayList<>();
			world.getEntities(EntityType.ITEM_DISPLAY, e -> e.getTags().contains(DropEventLinker.TAG_TRIGGER), displays);
			if (displays.isEmpty()) {
				source.sendFailure(Component.literal("No drop_event_item found"));
				return 0;
			}

			int activated = 0;
			for (Display.ItemDisplay display : displays) {
				UUID shulkerId = linkedShulkerUuidFromDisplay(display);
				if (shulkerId == null) continue;
				Entity linked = world.getEntity(shulkerId);
				if (!(linked instanceof Shulker shulker)) continue;
				DropEventBossBarManager.createBossBarForDisplay(world, display, shulker);
				activated++;
			}

			if (activated == 0) {
				source.sendFailure(Component.literal("No valid linked shulkers found for displays."));
				return 0;
			}
			final int activatedCount = activated;
			source.sendSuccess(() -> Component.literal("Activated bossbars for " + activatedCount + " drop event displays."), true);
			return Command.SINGLE_SUCCESS;
		} catch (Exception e) {
			context.getSource().sendFailure(Component.literal("Error: " + e.getMessage()));
			return 0;
		}
	}

	private static int stop(CommandContext<CommandSourceStack> context) {
		try {
			CommandSourceStack source = context.getSource();
			if (!(source.getLevel() instanceof ServerLevel world)) {
				source.sendFailure(Component.literal("Error: server-only command"));
				return 0;
			}

			Vec3 pos = source.getPosition();
			List<Display.ItemDisplay> displays = new ArrayList<>();
			world.getEntities(EntityType.ITEM_DISPLAY, e -> e.getTags().contains(DropEventLinker.TAG_TRIGGER), displays);
			if (displays.isEmpty()) {
				source.sendFailure(Component.literal("No drop_event_item found"));
				return 0;
			}

			Display.ItemDisplay nearest = null;
			double best = Double.MAX_VALUE;
			for (Display.ItemDisplay d : displays) {
				double dist = d.position().distanceToSqr(pos);
				if (dist < best) {
					best = dist;
					nearest = d;
				}
			}

			if (nearest == null) {
				source.sendFailure(Component.literal("No target display found"));
				return 0;
			}

			UUID shulkerId = linkedShulkerUuidFromDisplay(nearest);
			if (shulkerId == null) {
				source.sendFailure(Component.literal("Target display is not linked to a guard shulker"));
				return 0;
			}

			Entity linked = world.getEntity(shulkerId);
			if (linked instanceof Shulker shulker) {
				// remove bossbar and perform cleanup (drop and remove entities)
				DropEventBossBarManager.removeForShulker(shulker);
				DropEventLinker.dropLinkedDisplayItem(shulker);
				shulker.discard();
				source.sendSuccess(() -> Component.literal("Drop event stopped and cleaned up"), true);
				return Command.SINGLE_SUCCESS;
			}

			// If shulker not present, try to remove bossbar by UUID
			DropEventBossBarManager.removeForShulker(shulkerId, world);
			// also discard the display entity
			nearest.discard();
			source.sendSuccess(() -> Component.literal("Drop event stopped (shulker not present)"), true);
			return Command.SINGLE_SUCCESS;
		} catch (Exception e) {
			context.getSource().sendFailure(Component.literal("Error: " + e.getMessage()));
			return 0;
		}
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

	// Helper to register via the Fabric callback from the mod initializer
	public static void registerViaCallback() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			DropEventCommand.register(dispatcher);
		});
	}
}
