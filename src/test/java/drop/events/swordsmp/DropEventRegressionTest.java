package drop.events.swordsmp;

import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.EntityType;
import org.mockito.ArgumentCaptor;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gamerules.GameRules;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class DropEventRegressionTest {
	private MinecraftServer server;
	private ServerLevel world;
	private PlayerList players;
	private Display.ItemDisplay display;
	private Shulker guard;
	private ServerPlayer player;

	@BeforeAll static void initialize() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		DropEventBossBarManager.register();
		DropEventLinker.register();
		DropEventShulkerDropListener.register();
	}

	@BeforeEach void setup() {
		server = mock(MinecraftServer.class);
		world = mock(ServerLevel.class);
		when(world.getServer()).thenReturn(server);
		when(world.enabledFeatures()).thenReturn(FeatureFlags.REGISTRY.allFlags());
		players = mock(PlayerList.class);
		when(server.getPlayerList()).thenReturn(players);
		player = player();
		when(players.getPlayers()).thenReturn(List.of(player));
		display = mock(Display.ItemDisplay.class);
		when(display.getUUID()).thenReturn(UUID.randomUUID());
		when(display.getItemStack()).thenReturn(ItemStack.EMPTY);
		guard = mock(Shulker.class);
		when(guard.getUUID()).thenReturn(UUID.randomUUID());
		when(guard.isAlive()).thenReturn(true);
		when(guard.getMaxHealth()).thenReturn(1000f);
		when(guard.getHealth()).thenReturn(500f);
	}

	@AfterEach void clear() {
		ServerLifecycleEvents.SERVER_STOPPED.invoker().onServerStopped(server);
	}

	private static ServerPlayer player() {
		ServerPlayer p = mock(ServerPlayer.class);
		p.connection = mock(ServerGamePacketListenerImpl.class);
		when(p.connection.getPlayer()).thenReturn(p);
		return p;
	}

	private static CommandBuildContext buildContext() {
		return CommandBuildContext.simple(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY), FeatureFlags.REGISTRY.allFlags());
	}

	private CommandSourceStack operatorSource() {
		CommandSourceStack source = mock(CommandSourceStack.class);
		when(source.permissions()).thenReturn(LevelBasedPermissionSet.forLevel(PermissionLevel.byId(2)));
		when(source.getLevel()).thenReturn(world);
		when(source.getPosition()).thenReturn(new Vec3(10.5, 65.0, -20.5));
		when(source.getServer()).thenReturn(server);
		return source;
	}

	@Test void lateJoinReceivesExistingBar() {
		DropEventBossBarManager.createBossBarForDisplay(world, display, guard);
		ServerPlayer joining = player();
		ServerPlayConnectionEvents.JOIN.invoker().onPlayReady(joining.connection, null, server);
		verify(joining.connection).send(any());
		verify(server.getPlayerList(), never()).broadcastSystemMessage(any(Component.class), anyBoolean());
	}

	@Test void fallbackCleanupSendsRemove() {
		DropEventBossBarManager.createBossBarForDisplay(world, display, guard);
		clearInvocations(player.connection);
		when(guard.isAlive()).thenReturn(false);
		DropEventBossBarManager.updateAllBossBarProgress();
		verify(player.connection).send(any());
		clearInvocations(player.connection);
		DropEventBossBarManager.updateAllBossBarProgress();
		verifyNoInteractions(player.connection);
	}

	@Test void guardUnloadHidesBar() {
		DropEventBossBarManager.createBossBarForDisplay(world, display, guard);
		clearInvocations(player.connection);
		ServerEntityEvents.ENTITY_UNLOAD.invoker().onUnload(guard, world);
		verify(player.connection).send(any());
	}

	@Test void serverStopClearsBars() {
		DropEventBossBarManager.createBossBarForDisplay(world, display, guard);
		clearInvocations(player.connection);
		ServerLifecycleEvents.SERVER_STOPPED.invoker().onServerStopped(server);
		verify(player.connection).send(any());
		ServerPlayer joining = player();
		ServerPlayConnectionEvents.JOIN.invoker().onPlayReady(joining.connection, null, server);
		verifyNoInteractions(joining.connection);
	}

	@Test void disconnectedPlayerIsNotUpdated() {
		DropEventBossBarManager.createBossBarForDisplay(world, display, guard);
		ServerPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(player.connection, server);
		clearInvocations(player.connection);
		when(guard.getHealth()).thenReturn(250f);
		DropEventBossBarManager.updateAllBossBarProgress();
		verifyNoInteractions(player.connection);
	}

	@Test void startingExistingBarDoesNotDuplicateIt() {
		DropEventBossBarManager.createBossBarForDisplay(world, display, guard);
		clearInvocations(player.connection);
		DropEventBossBarManager.createBossBarForDisplay(world, display, guard);
		verifyNoInteractions(player.connection);
	}

	@Test void permanentDisplayRemovalDiscardsOnlyItsGuard() {
		link();
		when(display.getRemovalReason()).thenReturn(Entity.RemovalReason.DISCARDED);
		ServerEntityEvents.ENTITY_UNLOAD.invoker().onUnload(display, world);
		verify(guard).discard();
	}

	@Test void chunkUnloadPreservesGuard() {
		link();
		when(display.getRemovalReason()).thenReturn(Entity.RemovalReason.UNLOADED_TO_CHUNK);
		ServerEntityEvents.ENTITY_UNLOAD.invoker().onUnload(display, world);
		verify(guard, never()).discard();
	}

	@Test void displayCannotDiscardAnotherDisplaysGuard() {
		link();
		when(guard.getTags()).thenReturn(Set.of(DropEventLinker.TAG_SHULKER, "linked_display:" + UUID.randomUUID()));
		when(display.getRemovalReason()).thenReturn(Entity.RemovalReason.DISCARDED);
		ServerEntityEvents.ENTITY_UNLOAD.invoker().onUnload(display, world);
		verify(guard, never()).discard();
	}

	@Test void linkedEventRewardStillDropsOneCopiedStack() {
		UUID displayId = display.getUUID();
		when(guard.level()).thenReturn(world);
		when(guard.getTags()).thenReturn(Set.of(DropEventLinker.TAG_SHULKER, "linked_display:" + displayId));
		when(world.getEntity(displayId)).thenReturn(display);
		ItemStack reward = new ItemStack(Items.DIAMOND, 3);
		when(display.getItemStack()).thenReturn(reward);
		try (MockedConstruction<ItemEntity> drops = mockConstruction(ItemEntity.class, (mock, context) -> {
			ItemStack copy = (ItemStack) context.arguments().get(4);
			assertNotSame(reward, copy);
			assertEquals(3, copy.getCount());
			assertTrue(copy.is(Items.DIAMOND));
		})) {
			DropEventLinker.dropLinkedDisplayItem(guard);
			assertEquals(1, drops.constructed().size());
			verify(world).addFreshEntity(drops.constructed().getFirst());
			verify(display).discard();
		}
	}

	@Test void tagsAddedAfterLoadAreHandledOnNextTick() {
		link();
		UUID guardId = guard.getUUID();
		Set<String> tags = new HashSet<>(Set.of("linked_shulker:" + guardId));
		when(display.getTags()).thenReturn(tags);
		when(server.getAllLevels()).thenReturn(List.of(world));
		when(world.getGameRules()).thenReturn(mock(GameRules.class));
		when(display.position()).thenReturn(new Vec3(0, 65, 0));
		when(guard.getDeltaMovement()).thenReturn(Vec3.ZERO);
		ServerEntityEvents.ENTITY_LOAD.invoker().onLoad(display, world);
		ServerTickEvents.END_SERVER_TICK.invoker().onEndTick(server);
		verify(guard, never()).setNoAi(true);
		tags.add(DropEventLinker.TAG_TRIGGER);
		ServerTickEvents.END_SERVER_TICK.invoker().onEndTick(server);
		verify(guard).setNoAi(true);
	}

	@Test void commandsRequireOperatorLevelTwoAndKeepAllSubcommands() {
		CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
		DropEventCommand.register(dispatcher, buildContext());
		var root = dispatcher.getRoot().getChild("dropevent");
		assertNotNull(root.getChild("start"));
		assertNotNull(root.getChild("stop"));
		assertNotNull(root.getChild("summon"));
		assertNotNull(root.getChild("summon").getChild("item"));
		for (int level = 0; level <= 4; level++) {
			CommandSourceStack source = mock(CommandSourceStack.class);
			when(source.permissions()).thenReturn(LevelBasedPermissionSet.forLevel(PermissionLevel.byId(level)));
			assertEquals(level >= 2, root.canUse(source), "operator level " + level);
		}
	}

	@Test void summonWithItemSpawnsTaggedDisplay() throws Exception {
		CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
		DropEventCommand.register(dispatcher, buildContext());
		CommandSourceStack source = operatorSource();

		int result = dispatcher.execute("dropevent summon minecraft:diamond", source);

		assertEquals(Command.SINGLE_SUCCESS, result);
		ArgumentCaptor<Entity> spawned = ArgumentCaptor.forClass(Entity.class);
		verify(world).addFreshEntity(spawned.capture());
		Display.ItemDisplay spawnedDisplay = (Display.ItemDisplay) spawned.getValue();
		assertTrue(spawnedDisplay.getTags().contains(DropEventLinker.TAG_TRIGGER));
		assertTrue(spawnedDisplay.getItemStack().is(Items.DIAMOND));
		assertEquals(1, spawnedDisplay.getItemStack().getCount());
		assertEquals(BlockPos.containing(new Vec3(10.5, 65.0, -20.5)), spawnedDisplay.blockPosition());
	}

	@Test void summonWithoutArgumentCopiesOneMainHandItemWithoutConsuming() throws Exception {
		CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
		DropEventCommand.register(dispatcher, buildContext());
		CommandSourceStack source = operatorSource();
		ServerPlayer holder = mock(ServerPlayer.class);
		ItemStack held = new ItemStack(Items.DIAMOND_SWORD, 3);
		when(holder.getMainHandItem()).thenReturn(held);
		when(source.getEntity()).thenReturn(holder);

		int result = dispatcher.execute("dropevent summon", source);

		assertEquals(Command.SINGLE_SUCCESS, result);
		ArgumentCaptor<Entity> spawned = ArgumentCaptor.forClass(Entity.class);
		verify(world).addFreshEntity(spawned.capture());
		Display.ItemDisplay spawnedDisplay = (Display.ItemDisplay) spawned.getValue();
		assertTrue(spawnedDisplay.getItemStack().is(Items.DIAMOND_SWORD));
		assertEquals(1, spawnedDisplay.getItemStack().getCount());
		assertEquals(3, held.getCount());
	}

	@Test void summonWithoutArgumentAndEmptyHandFails() throws Exception {
		CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
		DropEventCommand.register(dispatcher, buildContext());
		CommandSourceStack source = operatorSource();
		ServerPlayer holder = mock(ServerPlayer.class);
		when(holder.getMainHandItem()).thenReturn(ItemStack.EMPTY);
		when(source.getEntity()).thenReturn(holder);

		dispatcher.execute("dropevent summon", source);

		verify(source).sendFailure(any());
		verify(world, never()).addFreshEntity(any());
	}

	@Test void summonWithoutArgumentFromConsoleFails() throws Exception {
		CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
		DropEventCommand.register(dispatcher, buildContext());
		CommandSourceStack source = operatorSource();

		dispatcher.execute("dropevent summon", source);

		verify(source).sendFailure(any());
		verify(world, never()).addFreshEntity(any());
	}

	private void link() {
		UUID guardId = guard.getUUID();
		UUID displayId = display.getUUID();
		trackTags(display, "linked_shulker:" + guardId);
		trackTags(guard, DropEventLinker.TAG_SHULKER, "linked_display:" + displayId);
		when(world.getEntity(guardId)).thenReturn(guard);
		when(guard.level()).thenReturn(world);
		when(world.getEntity(displayId)).thenReturn(display);
	}

	/** Makes a mocked entity keep real tag state. */
	private static Set<String> trackTags(Entity entity, String... initial) {
		Set<String> tags = new HashSet<>(Set.of(initial));
		when(entity.getTags()).thenReturn(tags);
		when(entity.addTag(anyString())).thenAnswer(i -> tags.add(i.getArgument(0)));
		when(entity.removeTag(anyString())).thenAnswer(i -> tags.remove(i.getArgument(0)));
		return tags;
	}

	@Test void stationarySnappedGuardDoesNotTeleport() throws Exception {
		when(guard.getX()).thenReturn(2.5);
		when(guard.getY()).thenReturn(10.0);
		when(guard.getZ()).thenReturn(-1.5);
		when(guard.getDeltaMovement()).thenReturn(Vec3.ZERO);
		Method method = DropEventLinker.class.getDeclaredMethod("teleportToDisplay", Shulker.class, Vec3.class);
		method.setAccessible(true);
		method.invoke(null, guard, new Vec3(2.1, 9.5, -1.8));
		verify(guard, never()).teleportTo(anyDouble(), anyDouble(), anyDouble());
	}

	@Test void unchangedLinkDoesNotMutateTags() throws Exception {
		UUID id = UUID.randomUUID();
		when(guard.getTags()).thenReturn(new HashSet<>(Set.of("linked_display:" + id)));
		Method method = DropEventLinker.class.getDeclaredMethod("ensureLinkTag", Entity.class, String.class, UUID.class);
		method.setAccessible(true);
		method.invoke(null, guard, "linked_display:", id);
		verify(guard, never()).addTag(anyString());
		verify(guard, never()).removeTag(anyString());
	}
	@Test void waypointMessageIsTerminalTenFieldTokenNamedAfterItem() {
		String message = DropEventWaypoint.waypointMessage(new BlockPos(100, 64, -20), Level.NETHER, "Diamond").getString();
		assertEquals("xaero-waypoint:Dropevent^col^Diamond:D:100:64:-20:6:false:0:Internal-the-nether-waypoints", message);
		assertEquals(10, message.split(":").length);
	}

	@Test void waypointNameEscapesXaeroDelimitersAndFormatting() {
		assertEquals("Dropevent:Diamond", DropEventWaypoint.waypointName("Diamond"));
		assertEquals("Dropevent:", DropEventWaypoint.waypointName(""));
		assertEquals("Dropevent:", DropEventWaypoint.waypointName(null));
		assertEquals("Dropevent:ca br", DropEventWaypoint.waypointName("\u00a7ca\n\tb\u00a7r"));
		assertEquals("Dropevent^col^a^min^b-c^ast^d", DropEventWaypoint.xaeroField("Dropevent:a-b_c*d"));
		String hostile = DropEventWaypoint.waypointToken(BlockPos.ZERO, Level.OVERWORLD, "x:y:z xaero-waypoint:1:2");
		assertEquals(10, hostile.split(":").length);
	}

	@Test void reservedXaeroEscapeSequencesInNamesDoNotRoundTripLiterally() {
		// Known limit: Xaero has no escape for its own markers, so these names show decoded.
		assertEquals("Dropevent^col^a^col^b", DropEventWaypoint.xaeroField("Dropevent:a^col^b"));
		String token = DropEventWaypoint.waypointToken(BlockPos.ZERO, Level.OVERWORLD, "^col^^min^^ast^");
		assertEquals(10, token.split(":").length);
	}

	@Test void waypointNameIsCutToXaeroLimit() {
		String name = DropEventWaypoint.waypointName("Enchanted Netherite Chestplate of Doom");
		assertEquals(32, name.length());
		assertEquals("Dropevent:Enchanted Netherite Ch", name);
		// never split a surrogate pair at the cut
		String emoji = DropEventWaypoint.waypointName("a".repeat(21) + "\uD83D\uDC8E");
		assertEquals("Dropevent:" + "a".repeat(21), emoji);
		assertEquals("Dropevent:" + "a".repeat(22), DropEventWaypoint.waypointName("a".repeat(40)));
	}

	@Test void customItemNameIsEscapedInTokenOnlyAnnouncement() throws Exception {
		link();
		ItemStack named = new ItemStack(Items.STICK);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("xaero\u00a7r-waypoint:forged"));
		when(display.getItemStack()).thenReturn(named);
		when(display.blockPosition()).thenReturn(BlockPos.ZERO);
		assertEquals(1, runStart(startSource(List.of(display))));
		ArgumentCaptor<Component> messages = ArgumentCaptor.forClass(Component.class);
		verify(server.getPlayerList()).broadcastSystemMessage(messages.capture(), eq(false));
		assertEquals("xaero-waypoint:Dropevent^col^xaeror^min^waypoint^col^forged:D:0:0:0:6:false:0:Internal-the-nether-waypoints", messages.getValue().getString());
	}

	@Test void plainItemNameIsUsedForWaypoint() throws Exception {
		link();
		when(display.getItemStack()).thenReturn(new ItemStack(Items.DIAMOND));
		when(display.blockPosition()).thenReturn(BlockPos.ZERO);
		assertEquals(1, runStart(startSource(List.of(display))));
		ArgumentCaptor<Component> messages = ArgumentCaptor.forClass(Component.class);
		verify(server.getPlayerList()).broadcastSystemMessage(messages.capture(), eq(false));
		assertTrue(messages.getValue().getString().startsWith("xaero-waypoint:Dropevent^col^Diamond:D:"));
	}

	@Test void failedMessageBuildDoesNotActivateBarAndCanBeRetried() throws Exception {
		link();
		when(display.blockPosition()).thenReturn(BlockPos.ZERO);
		CommandSourceStack source = startSource(List.of(display));
		// startSource stubs the dimension; apply the failure after its setup.
		when(world.dimension()).thenThrow(new IllegalStateException("test message failure")).thenReturn(Level.OVERWORLD);
		assertEquals(0, runStart(source));
		verify(server.getPlayerList(), never()).broadcastSystemMessage(any(Component.class), anyBoolean());
		assertEquals(1, runStart(source));
		verify(server.getPlayerList()).broadcastSystemMessage(any(Component.class), eq(false));
	}

	@Test void waypointDimensionEncodingHandlesAllVanillaAndModdedPaths() {
		assertEquals("overworld", DropEventWaypoint.xaeroDimension(Level.OVERWORLD.identifier()));
		assertEquals("the-nether", DropEventWaypoint.xaeroDimension(Level.NETHER.identifier()));
		assertEquals("the-end", DropEventWaypoint.xaeroDimension(Level.END.identifier()));
		assertEquals("dim%mymod$deep^min^dark", DropEventWaypoint.xaeroDimension(Identifier.fromNamespaceAndPath("mymod", "deep-dark")));
		assertEquals("dim%my^min^mod$deep-dark%cave,,", DropEventWaypoint.xaeroDimension(Identifier.fromNamespaceAndPath("my-mod", "deep_dark/cave..")));
		assertEquals("dim%mymod$inner.%cave,", DropEventWaypoint.xaeroDimension(Identifier.fromNamespaceAndPath("mymod", "inner./cave.")));
		assertEquals("dim%mymod$deep-dark%cave", DropEventWaypoint.xaeroDimension(Identifier.fromNamespaceAndPath("mymod", "deep_dark/cave")));
	}

	private CommandSourceStack startSource(List<Display.ItemDisplay> displays) {
		CommandSourceStack source = mock(CommandSourceStack.class);
		when(source.getLevel()).thenReturn(world);
		when(source.permissions()).thenReturn(LevelBasedPermissionSet.forLevel(PermissionLevel.GAMEMASTERS));
		when(world.dimension()).thenReturn(Level.NETHER);
		when(source.getPosition()).thenReturn(new Vec3(10.5, 65.0, -20.5));
		when(source.getServer()).thenReturn(server);
		doAnswer(invocation -> {
			List<Display.ItemDisplay> found = invocation.getArgument(2);
			found.addAll(displays);
			return null;
		}).when(world).getEntities(eq(EntityType.ITEM_DISPLAY), any(), any(List.class));
		return source;
	}

	private int runStart(CommandSourceStack source) throws Exception {
		CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
		DropEventCommand.register(dispatcher, buildContext());
		return dispatcher.execute("dropevent start", source);
	}

	@Test void startAnnouncesEachNewBarOnlyOnceWithEventPositionAndDimension() throws Exception {
		link();
		when(display.blockPosition()).thenReturn(BlockPos.containing(10.5, 65, -20.5));
		when(display.getItemStack()).thenReturn(new ItemStack(Items.DIAMOND));
		Display.ItemDisplay second = mock(Display.ItemDisplay.class);
		Shulker otherGuard = mock(Shulker.class);
		UUID otherId = UUID.randomUUID();
		when(otherGuard.getUUID()).thenReturn(otherId);
		when(otherGuard.getMaxHealth()).thenReturn(1000f);
		when(second.getUUID()).thenReturn(UUID.randomUUID());
		trackTags(second, "linked_shulker:" + otherId);
		trackTags(otherGuard, DropEventLinker.TAG_SHULKER);
		when(second.getItemStack()).thenReturn(new ItemStack(Items.STICK));
		when(second.blockPosition()).thenReturn(new BlockPos(1, 2, 3));
		when(world.getEntity(otherId)).thenReturn(otherGuard);
		CommandSourceStack source = startSource(List.of(display, second));
		when(server.getAllLevels()).thenReturn(List.of(world));
		assertEquals(1, runCommand("start all", source));
		assertEquals(0, runCommand("start all", source), "already running events are not started again");
		ArgumentCaptor<Component> messages = ArgumentCaptor.forClass(Component.class);
		verify(server.getPlayerList(), times(2)).broadcastSystemMessage(messages.capture(), eq(false));
		assertEquals("xaero-waypoint:Dropevent^col^Diamond:D:10:65:-21:6:false:0:Internal-the-nether-waypoints", messages.getAllValues().get(0).getString());
		assertEquals("xaero-waypoint:Dropevent^col^Stick:D:1:2:3:6:false:0:Internal-the-nether-waypoints", messages.getAllValues().get(1).getString());
	}

	@Test void startWithoutValidLinkedGuardDoesNotAnnounce() throws Exception {
		CommandSourceStack source = startSource(List.of(display));
		assertEquals(0, runStart(source));
		verify(server.getPlayerList(), never()).broadcastSystemMessage(any(Component.class), anyBoolean());
	}

	@Test void startWithoutDisplaysDoesNotAnnounce() throws Exception {
		assertEquals(0, runStart(startSource(List.of())));
		verify(server.getPlayerList(), never()).broadcastSystemMessage(any(Component.class), anyBoolean());
	}

	private int runCommand(String command, CommandSourceStack source) throws Exception {
		CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
		DropEventCommand.register(dispatcher, buildContext());
		try {
			return dispatcher.execute("dropevent " + command, source);
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			return -1;
		}
	}

	private Set<String> tags(Entity e) { return e.getTags(); }

	@Test void startOnlyAffectsUntouchedAndAnnouncesOnce() throws Exception {
		link();
		when(display.blockPosition()).thenReturn(BlockPos.ZERO);
		CommandSourceStack source = startSource(List.of(display));
		assertEquals(1, runStart(source));
		assertTrue(tags(guard).contains(DropEventState.TAG_RUNNING));
		assertTrue(tags(display).contains(DropEventState.TAG_RUNNING));
		assertEquals(0, runStart(source));
		verify(server.getPlayerList(), times(1)).broadcastSystemMessage(any(Component.class), eq(false));
		assertTrue(DropEventBossBarManager.titleOf(guard.getUUID()).endsWith(" at 0, 0, 0"));
	}

	@Test void pauseOnlyRunningKeepsBarVisibleWithSuffixAndResumeRemovesIt() throws Exception {
		link();
		when(display.blockPosition()).thenReturn(BlockPos.ZERO);
		CommandSourceStack source = startSource(List.of(display));
		assertEquals(0, runCommand("pause", source), "untouched cannot be paused");
		assertEquals(0, runCommand("resume", source), "untouched cannot be resumed");
		runStart(source);
		assertEquals(0, runCommand("resume", source), "running cannot be resumed");
		assertEquals(1, runCommand("pause", source));
		assertTrue(tags(guard).contains(DropEventState.TAG_PAUSED));
		assertFalse(tags(guard).contains(DropEventState.TAG_RUNNING));
		assertTrue(DropEventBossBarManager.titleOf(guard.getUUID()).endsWith(" (Paused)"));
		assertEquals(0, runCommand("pause", source), "paused cannot be paused");
		assertEquals(0, runCommand("start", source), "paused cannot be started");
		assertEquals(1, runCommand("resume", source));
		assertTrue(tags(guard).contains(DropEventState.TAG_RUNNING));
		assertFalse(DropEventBossBarManager.titleOf(guard.getUUID()).contains("Paused"));
		verify(server.getPlayerList(), times(1)).broadcastSystemMessage(any(Component.class), eq(false));
	}

	@Test void stopWorksInAnyStateWithoutRewardAndRemovesBarGuardAndDisplay() throws Exception {
		link();
		when(display.blockPosition()).thenReturn(BlockPos.ZERO);
		CommandSourceStack source = startSource(List.of(display));
		runStart(source);
		runCommand("pause", source);
		assertEquals(1, runCommand("stop", source));
		verify(guard).discard();
		verify(display).discard();
		verify(world, never()).addFreshEntity(any());
		assertNull(DropEventBossBarManager.titleOf(guard.getUUID()));
	}

	@Test void stopRemovesUntouchedEventAndEventThatNeverGotAGuard() throws Exception {
		link();
		CommandSourceStack source = startSource(List.of(display));
		assertEquals(1, runCommand("stop", source));
		verify(guard).discard();
		verify(display).discard();
		verify(world, never()).addFreshEntity(any());
		Display.ItemDisplay orphan = mock(Display.ItemDisplay.class);
		trackTags(orphan);
		when(orphan.position()).thenReturn(new Vec3(0, 65, 0));
		assertEquals(1, runCommand("stop", startSource(List.of(orphan))));
		verify(orphan).discard();
	}

	@Test void startAnnouncesEvenIfAStaleBarExistsForAnUntouchedGuard() throws Exception {
		link();
		when(display.blockPosition()).thenReturn(BlockPos.ZERO);
		DropEventBossBarManager.createBossBarForDisplay(world, display, guard);
		assertEquals(1, runStart(startSource(List.of(display))));
		verify(server.getPlayerList()).broadcastSystemMessage(any(Component.class), eq(false));
	}

	@Test void oneFailingEventDoesNotStopTheRestOfAll() throws Exception {
		link();
		when(display.blockPosition()).thenReturn(BlockPos.ZERO);
		Display.ItemDisplay broken = mock(Display.ItemDisplay.class);
		Shulker brokenGuard = mock(Shulker.class);
		UUID brokenId = UUID.randomUUID();
		when(brokenGuard.getUUID()).thenReturn(brokenId);
		when(broken.getUUID()).thenReturn(UUID.randomUUID());
		when(broken.getItemStack()).thenThrow(new IllegalStateException("boom"));
		trackTags(broken, "linked_shulker:" + brokenId);
		trackTags(brokenGuard, DropEventLinker.TAG_SHULKER);
		when(world.getEntity(brokenId)).thenReturn(brokenGuard);
		when(server.getAllLevels()).thenReturn(List.of(world));
		CommandSourceStack source = startSource(List.of(broken, display));
		assertEquals(1, runCommand("start all", source));
		assertTrue(tags(guard).contains(DropEventState.TAG_RUNNING));
		verify(source).sendFailure(any());
	}

	@Test void withoutAllOnlyTheNearestEligibleEventIsChanged() throws Exception {
		link();
		when(display.position()).thenReturn(new Vec3(100, 65, 100));
		when(display.blockPosition()).thenReturn(BlockPos.ZERO);
		Display.ItemDisplay near = mock(Display.ItemDisplay.class);
		Shulker nearGuard = mock(Shulker.class);
		UUID nearId = UUID.randomUUID();
		when(nearGuard.getUUID()).thenReturn(nearId);
		when(nearGuard.getMaxHealth()).thenReturn(10f);
		when(near.getUUID()).thenReturn(UUID.randomUUID());
		when(near.getItemStack()).thenReturn(ItemStack.EMPTY);
		when(near.position()).thenReturn(new Vec3(11, 65, -20));
		when(near.blockPosition()).thenReturn(BlockPos.ZERO);
		trackTags(near, "linked_shulker:" + nearId);
		trackTags(nearGuard, DropEventLinker.TAG_SHULKER);
		when(world.getEntity(nearId)).thenReturn(nearGuard);
		// the nearest event is already running, so it is not eligible for start
		tags(nearGuard).add(DropEventState.TAG_RUNNING);
		CommandSourceStack source = startSource(List.of(display, near));
		assertEquals(1, runCommand("start", source));
		assertTrue(tags(guard).contains(DropEventState.TAG_RUNNING), "nearest eligible (untouched) event started");
		// both running now: pause takes only the nearest running one
		assertEquals(1, runCommand("pause", source));
		assertTrue(tags(nearGuard).contains(DropEventState.TAG_PAUSED));
		assertTrue(tags(guard).contains(DropEventState.TAG_RUNNING));
	}

	@Test void allAffectsEveryEligibleEventInEveryDimension() throws Exception {
		link();
		when(display.blockPosition()).thenReturn(BlockPos.ZERO);
		ServerLevel nether = mock(ServerLevel.class);
		when(nether.getServer()).thenReturn(server);
		when(nether.dimension()).thenReturn(Level.NETHER);
		Display.ItemDisplay far = mock(Display.ItemDisplay.class);
		Shulker farGuard = mock(Shulker.class);
		UUID farId = UUID.randomUUID();
		when(farGuard.getUUID()).thenReturn(farId);
		when(farGuard.getMaxHealth()).thenReturn(10f);
		when(far.getUUID()).thenReturn(UUID.randomUUID());
		when(far.getItemStack()).thenReturn(ItemStack.EMPTY);
		when(far.blockPosition()).thenReturn(BlockPos.ZERO);
		trackTags(far, "linked_shulker:" + farId);
		trackTags(farGuard, DropEventLinker.TAG_SHULKER);
		when(nether.getEntity(farId)).thenReturn(farGuard);
		doAnswer(invocation -> {
			List<Display.ItemDisplay> found = invocation.getArgument(2);
			found.add(far);
			return null;
		}).when(nether).getEntities(eq(EntityType.ITEM_DISPLAY), any(), any(List.class));
		when(server.getAllLevels()).thenReturn(List.of(world, nether));
		CommandSourceStack source = startSource(List.of(display));
		when(source.getServer()).thenReturn(server);
		assertEquals(1, runCommand("start all", source));
		assertTrue(tags(guard).contains(DropEventState.TAG_RUNNING));
		assertTrue(tags(farGuard).contains(DropEventState.TAG_RUNNING));
		assertEquals(1, runCommand("pause all", source));
		assertTrue(tags(farGuard).contains(DropEventState.TAG_PAUSED));
		assertEquals(1, runCommand("stop all", source));
		verify(guard).discard();
		verify(farGuard).discard();
	}

	@Test void summonHasNoAllOption() {
		CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
		DropEventCommand.register(dispatcher, buildContext());
		var root = dispatcher.getRoot().getChild("dropevent");
		for (String name : List.of("start", "pause", "resume", "stop")) assertNotNull(root.getChild(name).getChild("all"), name);
		assertNull(root.getChild("summon").getChild("all"));
	}

	private Shulker diesWith(String... extraTags) {
		link();
		for (String t : extraTags) { guard.addTag(t); display.addTag(t); }
		when(display.getItemStack()).thenReturn(new ItemStack(Items.DIAMOND));
		return guard;
	}

	@Test void untouchedAndPausedGuardDeathDeletesDisplayWithoutReward() {
		for (String state : new String[] { null, DropEventState.TAG_PAUSED }) {
			setup();
			Shulker g = state == null ? diesWith() : diesWith(state);
			net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.invoker().afterDeath(g, null);
			verify(display).discard();
			verify(world, never()).addFreshEntity(any());
		}
	}

	@Test void runningGuardDeathStillDropsReward() {
		Shulker g = diesWith(DropEventState.TAG_RUNNING);
		try (MockedConstruction<ItemEntity> drops = mockConstruction(ItemEntity.class)) {
			net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.invoker().afterDeath(g, null);
			assertEquals(1, drops.constructed().size());
			verify(display).discard();
		}
	}

	private void tick() {
		when(server.getAllLevels()).thenReturn(List.of(world));
		when(world.getGameRules()).thenReturn(mock(GameRules.class));
		when(display.position()).thenReturn(new Vec3(0, 65, 0));
		when(guard.getDeltaMovement()).thenReturn(Vec3.ZERO);
		ServerEntityEvents.ENTITY_LOAD.invoker().onLoad(display, world);
		ServerTickEvents.END_SERVER_TICK.invoker().onEndTick(server);
	}

	@Test void staleRunningTagFromCrashBecomesPausedWithVisibleBar() {
		link();
		tags(display).add(DropEventLinker.TAG_TRIGGER);
		tags(guard).add(DropEventState.TAG_RUNNING);
		tick();
		assertTrue(tags(guard).contains(DropEventState.TAG_PAUSED));
		assertFalse(tags(guard).contains(DropEventState.TAG_RUNNING));
		assertTrue(DropEventBossBarManager.titleOf(guard.getUUID()).endsWith(" (Paused)"));
	}

	@Test void pausedEventGetsItsBarBackAfterRestartAndUntouchedStaysUntouched() {
		link();
		tags(display).add(DropEventLinker.TAG_TRIGGER);
		tick();
		assertNull(DropEventBossBarManager.titleOf(guard.getUUID()));
		assertFalse(tags(guard).contains(DropEventState.TAG_PAUSED));
		tags(guard).add(DropEventState.TAG_PAUSED);
		ServerTickEvents.END_SERVER_TICK.invoker().onEndTick(server);
		assertTrue(DropEventBossBarManager.titleOf(guard.getUUID()).endsWith(" (Paused)"));
	}

	@Test void freshlyStartedGuardIsNotPausedAndKeepsItsBarAfterReload() throws Exception {
		link();
		tags(display).add(DropEventLinker.TAG_TRIGGER);
		when(display.blockPosition()).thenReturn(BlockPos.ZERO);
		runStart(startSource(List.of(display)));
		tick();
		assertTrue(tags(guard).contains(DropEventState.TAG_RUNNING));
		ServerEntityEvents.ENTITY_UNLOAD.invoker().onUnload(guard, world);
		assertNull(DropEventBossBarManager.titleOf(guard.getUUID()));
		ServerTickEvents.END_SERVER_TICK.invoker().onEndTick(server);
		assertTrue(tags(guard).contains(DropEventState.TAG_RUNNING));
		assertFalse(DropEventBossBarManager.titleOf(guard.getUUID()).contains("Paused"));
	}

	@Test void shutdownPausesRunningEventsAndLeavesUntouchedAlone() throws Exception {
		link();
		tags(display).add(DropEventLinker.TAG_TRIGGER);
		when(display.blockPosition()).thenReturn(BlockPos.ZERO);
		runStart(startSource(List.of(display)));
		when(server.getAllLevels()).thenReturn(List.of(world));
		ServerEntityEvents.ENTITY_LOAD.invoker().onLoad(display, world);
		ServerLifecycleEvents.SERVER_STOPPING.invoker().onServerStopping(server);
		assertTrue(tags(guard).contains(DropEventState.TAG_PAUSED));
		assertTrue(tags(display).contains(DropEventState.TAG_PAUSED));
		// untouched event
		setup();
		link();
		tags(display).add(DropEventLinker.TAG_TRIGGER);
		ServerEntityEvents.ENTITY_LOAD.invoker().onLoad(display, world);
		ServerLifecycleEvents.SERVER_STOPPING.invoker().onServerStopping(server);
		assertFalse(tags(guard).contains(DropEventState.TAG_PAUSED));
		assertFalse(tags(guard).contains(DropEventState.TAG_RUNNING));
	}

	@Test void resistanceVOnlyWhileUntouchedOrPaused() {
		link();
		var captor = ArgumentCaptor.forClass(net.minecraft.world.effect.MobEffectInstance.class);
		DropEventState.applyProtection(guard);
		verify(guard).addEffect(captor.capture());
		var effect = captor.getValue();
		assertEquals(4, effect.getAmplifier());
		assertTrue(effect.isInfiniteDuration());
		assertTrue(effect.getEffect().is(net.minecraft.world.effect.MobEffects.RESISTANCE));
		// running removes our effect
		when(guard.getEffect(net.minecraft.world.effect.MobEffects.RESISTANCE)).thenReturn(effect);
		DropEventState.set(display, guard, DropEventState.State.RUNNING);
		verify(guard).removeEffect(net.minecraft.world.effect.MobEffects.RESISTANCE);
	}

	private void tickTimes(int n) {
		for (int i = 0; i < n; i++) ServerTickEvents.END_SERVER_TICK.invoker().onEndTick(server);
	}

	private void missingGuardSetup(String... extraTags) {
		link();
		tags(display).add(DropEventLinker.TAG_TRIGGER);
		for (String t : extraTags) tags(display).add(t);
		when(world.getEntity(guard.getUUID())).thenReturn(null);
		when(server.getAllLevels()).thenReturn(List.of(world));
		when(display.position()).thenReturn(new Vec3(0, 65, 0));
		ServerEntityEvents.ENTITY_LOAD.invoker().onLoad(display, world);
	}

	@Test void guardInUnloadedChunkIsNeverTreatedAsLost() {
		missingGuardSetup();
		when(world.isPositionEntityTicking(any(BlockPos.class))).thenReturn(false);
		tickTimes(DropEventLinker.MISSING_GUARD_TICKS * 3);
		verify(display, never()).discard();
		verify(world, never()).addFreshEntity(any());
	}

	@Test void inactiveGuardMissingFromItsLoadedChunkDeletesDisplayAfterGraceWithoutReward() {
		missingGuardSetup();
		when(world.isPositionEntityTicking(any(BlockPos.class))).thenReturn(true);
		tickTimes(DropEventLinker.MISSING_GUARD_TICKS - 1);
		verify(display, never()).discard();
		tickTimes(1);
		verify(display).discard();
		verify(world, never()).addFreshEntity(any());
	}

	@Test void guardReappearingResetsTheMissingCounter() {
		missingGuardSetup();
		when(world.isPositionEntityTicking(any(BlockPos.class))).thenReturn(true);
		tickTimes(DropEventLinker.MISSING_GUARD_TICKS - 1);
		when(world.getGameRules()).thenReturn(mock(GameRules.class));
		when(guard.getDeltaMovement()).thenReturn(Vec3.ZERO);
		when(world.getEntity(guard.getUUID())).thenReturn(guard);
		tickTimes(1);
		when(world.getEntity(guard.getUUID())).thenReturn(null);
		tickTimes(DropEventLinker.MISSING_GUARD_TICKS - 1);
		verify(display, never()).discard();
	}

	@Test void runningEventWithLostGuardStillPaysOutAfterGrace() {
		missingGuardSetup(DropEventState.TAG_RUNNING);
		when(display.getItemStack()).thenReturn(new ItemStack(Items.DIAMOND));
		when(world.isPositionEntityTicking(any(BlockPos.class))).thenReturn(true);
		try (MockedConstruction<ItemEntity> drops = mockConstruction(ItemEntity.class)) {
			tickTimes(DropEventLinker.MISSING_GUARD_TICKS);
			assertEquals(1, drops.constructed().size());
			verify(display).discard();
		}
	}

	@Test void stopSkipsEventWhoseLinkedGuardDoesNotResolveEvenInATickingChunk() throws Exception {
		link();
		when(world.getEntity(guard.getUUID())).thenReturn(null);
		when(world.isPositionEntityTicking(any(BlockPos.class))).thenReturn(true);
		when(display.position()).thenReturn(new Vec3(0, 65, 0));
		CommandSourceStack source = startSource(List.of(display));
		assertEquals(0, runCommand("stop", source));
		verify(display, never()).discard();
		verify(source).sendFailure(argThat(c -> c.getString().contains("Skipped 1")));
		when(world.getEntity(guard.getUUID())).thenReturn(guard);
		assertEquals(1, runCommand("stop", startSource(List.of(display))));
		verify(display).discard();
	}

	@Test void missingCounterDoesNotSurviveDisplayUnloadOrStop() {
		missingGuardSetup();
		when(world.isPositionEntityTicking(any(BlockPos.class))).thenReturn(true);
		tickTimes(DropEventLinker.MISSING_GUARD_TICKS - 1);
		when(display.getRemovalReason()).thenReturn(Entity.RemovalReason.UNLOADED_TO_CHUNK);
		ServerEntityEvents.ENTITY_UNLOAD.invoker().onUnload(display, world);
		ServerEntityEvents.ENTITY_LOAD.invoker().onLoad(display, world);
		tickTimes(DropEventLinker.MISSING_GUARD_TICKS - 1);
		verify(display, never()).discard();
	}

	@Test void barTitleIsRefreshedOnABoundedScheduleNotEveryTick() {
		link();
		when(world.getGameTime()).thenReturn(100L);
		when(display.getItemStack()).thenReturn(new ItemStack(Items.DIAMOND));
		DropEventBossBarManager.showBar(world, display, guard, false);
		String first = DropEventBossBarManager.titleOf(guard.getUUID());
		when(display.getItemStack()).thenReturn(new ItemStack(Items.STICK));
		when(world.getGameTime()).thenReturn(100L + DropEventBossBarManager.TITLE_REFRESH_TICKS - 1);
		DropEventBossBarManager.ensureBar(world, display, guard, false);
		assertEquals(first, DropEventBossBarManager.titleOf(guard.getUUID()));
		when(world.getGameTime()).thenReturn(100L + DropEventBossBarManager.TITLE_REFRESH_TICKS);
		DropEventBossBarManager.ensureBar(world, display, guard, false);
		assertNotEquals(first, DropEventBossBarManager.titleOf(guard.getUUID()));
		assertTrue(DropEventBossBarManager.titleOf(guard.getUUID()).startsWith("Stick"));
	}

	@Test void replacementGuardInheritsDisplayState() {
		link();
		tags(display).add(DropEventState.TAG_RUNNING);
		DropEventLinker.inheritState(display, guard);
		assertTrue(tags(guard).contains(DropEventState.TAG_RUNNING));
		tags(guard).clear();
		tags(display).clear();
		tags(display).add(DropEventState.TAG_PAUSED);
		DropEventLinker.inheritState(display, guard);
		assertTrue(tags(guard).contains(DropEventState.TAG_PAUSED));
		tags(guard).clear();
		tags(display).clear();
		DropEventLinker.inheritState(display, guard);
		assertFalse(tags(guard).contains(DropEventState.TAG_RUNNING) || tags(guard).contains(DropEventState.TAG_PAUSED));
	}

}
