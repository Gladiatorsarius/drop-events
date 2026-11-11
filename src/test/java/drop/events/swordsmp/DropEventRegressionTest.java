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
import com.mojang.brigadier.CommandDispatcher;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gamerules.GameRules;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
	private Display.ItemDisplay display;
	private Shulker guard;
	private ServerPlayer player;

	@BeforeAll static void initialize() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		DropEventBossBarManager.register();
		DropEventLinker.register();
	}

	@BeforeEach void setup() {
		server = mock(MinecraftServer.class);
		world = mock(ServerLevel.class);
		when(world.getServer()).thenReturn(server);
		PlayerList players = mock(PlayerList.class);
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

	@Test void commandsRequireOperatorLevelTwoAndKeepBothSubcommands() {
		CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
		DropEventCommand.register(dispatcher);
		var root = dispatcher.getRoot().getChild("dropevent");
		assertNotNull(root.getChild("start"));
		assertNotNull(root.getChild("stop"));
		for (int level = 0; level <= 4; level++) {
			CommandSourceStack source = mock(CommandSourceStack.class);
			when(source.permissions()).thenReturn(LevelBasedPermissionSet.forLevel(PermissionLevel.byId(level)));
			assertEquals(level >= 2, root.canUse(source), "operator level " + level);
		}
	}

	private void link() {
		UUID guardId = guard.getUUID();
		UUID displayId = display.getUUID();
		when(display.getTags()).thenReturn(Set.of("linked_shulker:" + guardId));
		when(guard.getTags()).thenReturn(Set.of(DropEventLinker.TAG_SHULKER, "linked_display:" + displayId));
		when(world.getEntity(guardId)).thenReturn(guard);
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
		doAnswer(invocation -> {
			List<Display.ItemDisplay> found = invocation.getArgument(2);
			found.addAll(displays);
			return null;
		}).when(world).getEntities(eq(EntityType.ITEM_DISPLAY), any(), any(List.class));
		return source;
	}

	private int runStart(CommandSourceStack source) throws Exception {
		CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
		DropEventCommand.register(dispatcher);
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
		when(second.getTags()).thenReturn(Set.of("linked_shulker:" + otherId));
		when(second.getItemStack()).thenReturn(new ItemStack(Items.STICK));
		when(second.blockPosition()).thenReturn(new BlockPos(1, 2, 3));
		when(world.getEntity(otherId)).thenReturn(otherGuard);
		CommandSourceStack source = startSource(List.of(display, second));
		assertEquals(1, runStart(source));
		assertEquals(1, runStart(source));
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

	@Test void removedBarCanAnnounceAgainAfterRestart() throws Exception {
		link();
		when(display.blockPosition()).thenReturn(BlockPos.ZERO);
		CommandSourceStack source = startSource(List.of(display));
		assertEquals(1, runStart(source));
		DropEventBossBarManager.removeForShulker(guard);
		assertEquals(1, runStart(source));
		verify(server.getPlayerList(), times(2)).broadcastSystemMessage(any(Component.class), eq(false));
	}

}
