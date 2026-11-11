package drop.events.swordsmp;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

public final class DropEventCommand {
	private DropEventCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
		var root = Commands.literal("dropevent")
			.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));

		root.then(Commands.literal("start").executes(DropEventCommand::start));
		root.then(Commands.literal("stop").executes(DropEventCommand::stop));
		root.then(Commands.literal("summon")
			.executes(context -> summon(context, null))
			.then(Commands.argument("item", ItemArgument.item(buildContext))
				.executes(context -> summon(context, ItemArgument.getItem(context, "item")))));

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
				UUID shulkerId = DropEventLinker.linkedShulkerUuidFromDisplay(display);
				if (shulkerId == null) continue;
				Entity linked = world.getEntity(shulkerId);
				if (!(linked instanceof Shulker shulker)) continue;
				// Build before bar creation so a failed message cannot leave an
				// active bar that suppresses the next start's announcement.
				Component waypoint = DropEventWaypoint.waypointMessage(display.blockPosition(), world.dimension(),
					display.getItemStack().getHoverName().getString());
				if (DropEventBossBarManager.createBossBarForDisplay(world, display, shulker)) {
					world.getServer().getPlayerList().broadcastSystemMessage(
						waypoint, false);
				}
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

			UUID shulkerId = DropEventLinker.linkedShulkerUuidFromDisplay(nearest);
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
			DropEventBossBarManager.removeForShulker(shulkerId);
			// also discard the display entity
			nearest.discard();
			source.sendSuccess(() -> Component.literal("Drop event stopped (shulker not present)"), true);
			return Command.SINGLE_SUCCESS;
		} catch (Exception e) {
			context.getSource().sendFailure(Component.literal("Error: " + e.getMessage()));
			return 0;
		}
	}

	private static int summon(CommandContext<CommandSourceStack> context, ItemInput itemInput) {
		try {
			CommandSourceStack source = context.getSource();
			if (!(source.getLevel() instanceof ServerLevel world)) {
				source.sendFailure(Component.literal("Error: server-only command"));
				return 0;
			}

			ItemStack stack;
			if (itemInput != null) {
				stack = itemInput.createItemStack(1, false);
			} else {
				if (!(source.getEntity() instanceof LivingEntity living)) {
					source.sendFailure(Component.literal("Error: no item specified and the command source has no main hand. Usage: /dropevent summon <item>"));
					return 0;
				}
				ItemStack held = living.getMainHandItem();
				if (held.isEmpty()) {
					source.sendFailure(Component.literal("Error: main hand is empty and no item was specified. Usage: /dropevent summon <item>"));
					return 0;
				}
				stack = held.copyWithCount(1);
			}

			Display.ItemDisplay display = spawnEventDisplay(world, source.getPosition(), stack);
			String itemName = display.getItemStack().getHoverName().getString();
			source.sendSuccess(() -> Component.literal("Summoned drop event display with " + itemName + "."), true);
			return Command.SINGLE_SUCCESS;
		} catch (Exception e) {
			context.getSource().sendFailure(Component.literal("Error: " + e.getMessage()));
			return 0;
		}
	}

	static Display.ItemDisplay spawnEventDisplay(ServerLevel world, Vec3 pos, ItemStack stack) {
		Display.ItemDisplay display = (Display.ItemDisplay) EntityType.ITEM_DISPLAY.create(world, EntitySpawnReason.COMMAND);
		if (display == null) {
			throw new IllegalStateException("Could not create item display entity");
		}
		display.setPos(pos.x, pos.y, pos.z);
		display.setItemStack(stack.copy());
		display.addTag(DropEventLinker.TAG_TRIGGER);
		world.addFreshEntity(display);
		return display;
	}

	// Helper to register via the Fabric callback from the mod initializer
	public static void registerViaCallback() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			DropEventCommand.register(dispatcher, registryAccess);
		});
	}
}
