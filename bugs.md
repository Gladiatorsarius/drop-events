# Review fixes (1.2.3)

## Guard death deleted unrelated shells and XP
- Bug: Killing an event guard removed every shulker-shell item stack and XP orb within 2.5 blocks, including player-dropped items and other mobs' XP.
- Cause: The death listener scanned loaded entities and deleted nearby drops without checking their origin.
- Repro: Put a shell stack and unrelated XP next to a guard, then kill the guard.
- Fix: Cancel vanilla death-loot generation only for shulkers tagged `drop_event_shulker`. Keep the separate event-display reward handler. No nearby items or XP are scanned or deleted.
- Tested: Build and mixin compilation; unit regression confirms the configured event reward is copied and spawned once. Actual mixin application and nearby-loot preservation still need an in-game test.

## Bossbars missed joining players and retained stale state
- Bug: Players joining after `/dropevent start` did not see existing bars. Departed players were retained. Fallback cleanup could forget a bar without hiding it.
- Cause: Players were added only during initial creation, and fallback cleanup removed map entries without sending removal packets. Static state survived a server session.
- Repro: Start a bar, join/reconnect another player; discard/unload a guard without its normal death handler; stop and reopen an integrated server.
- Fix: Register player join/disconnect, entity unload and server-stop cleanup. Keep each entity and bar together in one map, and remove all viewers before forgetting a bar.
- Tested: Unit regressions for late join, disconnect, invalid-guard fallback, guard unload, repeated start and server stop. Multiplayer UI and integrated-server reopen still need manual checks.

## Permanently removed displays left orphan guards
- Bug: Removing a display could leave its invisible, no-AI, no-gravity guard behind.
- Cause: The tick loop only inspected displays that still existed.
- Repro: Permanently remove a linked display without killing its guard.
- Fix: On permanent display removal, discard its living guard only if both entities' link tags agree and the guard has the event tag. Normal chunk unload does not destroy guards.
- Tested: Unit regressions for permanent removal, chunk unload and wrong-owner safety. In-game chunk unload/reload remains to check.
- Known limitation: If the guard is already in an unloaded chunk when the display is permanently removed, it cannot be found for immediate cleanup. This fix does not force-load chunks or redesign persistent event tracking.

## Avoidable per-tick work
- Bug: The linker scanned all loaded entities for item displays every tick, then repeatedly copied/re-added unchanged tags, reapplied flags and teleported stationary guards.
- Cause: No loaded-display tracking or unchanged-state checks.
- Repro: Load many unrelated entities and a stationary event; inspect tick work.
- Fix: Track loaded item displays with Fabric entity events, still inspecting their live tags each tick so `/tag` changes work immediately. Skip unchanged tag/flag mutations and teleports, respecting vanilla shulker block-position snapping. Tick frequency and event positions are unchanged.
- Tested: Unit regressions for a tag added after entity load, unchanged link tags and stationary snapped guard positions. No measured TPS claim; a real-server profile is still needed.

## Documentation drift
- Bug: The README described `/dropevent start` as nearest-only, although the code starts all loaded events in the source dimension. It also omitted the visibility/health gamerules.
- Fix: Document current behavior without changing start-all/stop-nearest semantics.

## Validation
- Baseline and changed project build with JDK 25 (as CI uses), Gradle 9.3 and Java release 21.
- 13 JUnit/Mockito regression tests pass. They use real Minecraft/Fabric classes with mocked world/player objects, not a running game.
- No unrelated scaffolding removal or gameplay redesign.
