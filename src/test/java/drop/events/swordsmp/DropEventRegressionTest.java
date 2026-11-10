package drop.events.swordsmp;

import java.lang.reflect.Method;
import java.util.List;
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
}
