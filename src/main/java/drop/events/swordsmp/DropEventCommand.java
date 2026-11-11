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

		for (Action action : Action.values()) {
			root.then(Commands.literal(action.literal)
				.executes(context -> run(context, action, false))
				.then(Commands.literal("all").executes(context -> run(context, action, true))));
		}
		root.then(Commands.literal("summon")
			.executes(context -> summon(context, null))
			.then(Commands.argument("item", ItemArgument.item(buildContext))
				.executes(context -> summon(context, ItemArgument.getItem(context, "item")))));

		dispatcher.register(root);
	}

	private enum Action {
		START("start", DropEventState.State.UNTOUCHED),
		PAUSE("pause", DropEventState.State.RUNNING),
		RESUME("resume", DropEventState.State.PAUSED),
		STOP("stop", null); // any state

		final String literal;
		final DropEventState.State requires;

		Action(String literal, DropEventState.State requires) {
			this.literal = literal;
			this.requires = requires;
		}
	}

	private static int skippedUnloaded;

	private record Target(ServerLevel world, Display.ItemDisplay display, Shulker guard) {}

	/**
	 * Without "all": the nearest eligible event in the source's dimension. With "all": every
	 * eligible event in every dimension. Eligible means the right state and a loaded linked guard
	 * (stop accepts any state, and a missing guard).
	 */
	private static int run(CommandContext<CommandSourceStack> context, Action action, boolean all) {
		try {
			CommandSourceStack source = context.getSource();
			if (!(source.getLevel() instanceof ServerLevel here)) {
				source.sendFailure(Component.literal("Error: server-only command"));
				return 0;
			}

			List<Target> eligible = new ArrayList<>();
			skippedUnloaded = 0;
			if (all) {
				for (ServerLevel world : source.getServer().getAllLevels()) collect(world, action, eligible);
			} else {
				collect(here, action, eligible);
			}
			if (eligible.isEmpty()) {
				source.sendFailure(Component.literal(noTarget(action) + skippedNote()));
				return 0;
			}

			List<Target> targets = eligible;
			if (!all && eligible.size() > 1) {
				Vec3 pos = source.getPosition();
				Target nearest = eligible.get(0);
				for (Target t : eligible) {
					if (t.display().position().distanceToSqr(pos) < nearest.display().position().distanceToSqr(pos)) nearest = t;
				}
				targets = List.of(nearest);
			} else if (!all) {
				targets = List.of(eligible.get(0));
			}

			int done = 0;
			String firstError = null;
			for (Target target : targets) {
				try {
					apply(action, target);
					done++;
				} catch (Exception e) {
					// One broken event must not stop the others; report it at the end.
					if (firstError == null) firstError = String.valueOf(e.getMessage());
				}
			}
			final int count = done;
			if (firstError != null) {
				source.sendFailure(Component.literal("Error: " + firstError + " (" + describe(action, count) + ")"));
				return done > 0 ? Command.SINGLE_SUCCESS : 0;
			}
			source.sendSuccess(() -> Component.literal(describe(action, count) + skippedNote()), true);
			return Command.SINGLE_SUCCESS;
		} catch (Exception e) {
			context.getSource().sendFailure(Component.literal("Error: " + e.getMessage()));
			return 0;
		}
	}

	private static void collect(ServerLevel world, Action action, List<Target> out) {
		List<Display.ItemDisplay> displays = new ArrayList<>();
		world.getEntities(EntityType.ITEM_DISPLAY, e -> e.getTags().contains(DropEventLinker.TAG_TRIGGER), displays);
		for (Display.ItemDisplay display : displays) {
			UUID shulkerId = DropEventLinker.linkedShulkerUuidFromDisplay(display);
			Shulker guard = shulkerId != null && world.getEntity(shulkerId) instanceof Shulker s ? s : null;
			if (action == Action.STOP) {
				// A guard that is merely unloaded must not be orphaned: leave that event alone.
				if (guard == null && shulkerId != null && !DropEventLinker.guardChunkLoaded(world, display)) {
					skippedUnloaded++;
					continue;
				}
				out.add(new Target(world, display, guard));
			} else if (guard != null && DropEventState.of(guard) == action.requires) {
				out.add(new Target(world, display, guard));
			}
		}
	}

	private static void apply(Action action, Target t) {
		switch (action) {
			case START -> {
				// Build before the state change so a failed message cannot leave a running
				// event that never announced.
				Component waypoint = DropEventWaypoint.waypointMessage(t.display().blockPosition(), t.world().dimension(),
					t.display().getItemStack().getHoverName().getString());
				DropEventState.set(t.display(), t.guard(), DropEventState.State.RUNNING);
				DropEventBossBarManager.showBar(t.world(), t.display(), t.guard(), false);
				// start only runs on untouched events, so this announces exactly once per start.
				t.world().getServer().getPlayerList().broadcastSystemMessage(waypoint, false);
			}
			case PAUSE -> {
				DropEventState.set(t.display(), t.guard(), DropEventState.State.PAUSED);
				DropEventBossBarManager.showBar(t.world(), t.display(), t.guard(), true);
			}
			case RESUME -> {
				// No chat message or waypoint on resume.
				DropEventState.set(t.display(), t.guard(), DropEventState.State.RUNNING);
				DropEventBossBarManager.showBar(t.world(), t.display(), t.guard(), false);
			}
			case STOP -> {
				// No reward: remove the bar, the guard and the display.
				if (t.guard() != null) {
					DropEventBossBarManager.removeForShulker(t.guard());
					DropEventState.forget(t.guard().getUUID());
					t.guard().discard();
				}
				t.display().discard();
			}
		}
	}

	private static String skippedNote() {
		return skippedUnloaded == 0 ? "" : " Skipped " + skippedUnloaded + " whose guard chunk is not loaded.";
	}

	private static String noTarget(Action action) {
		return switch (action) {
			case START -> "No untouched drop event with a linked guard found.";
			case PAUSE -> "No running drop event found.";
			case RESUME -> "No paused drop event found.";
			case STOP -> "No drop event found.";
		};
	}

	private static String describe(Action action, int count) {
		String noun = count == 1 ? "drop event" : "drop events";
		return switch (action) {
			case START -> "Started " + count + " " + noun + ".";
			case PAUSE -> "Paused " + count + " " + noun + ".";
			case RESUME -> "Resumed " + count + " " + noun + ".";
			case STOP -> "Stopped " + count + " " + noun + " without a reward.";
		};
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
