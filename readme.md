# Drop Events (1.21.11)

Server-only mod behavior: it runs entirely on the server and does not require any client-side code.

## What it does
- Watches for `minecraft:item_display` entities tagged `drop_event_item` (scoreboard tag).
- Spawns and links a shulker guard to that display (server-side only).
- Teleports the shulker to the display every tick.
- If the shulker dies, the display drops its item and is removed.

## Commands

The `/dropevent` commands (start, pause, resume, stop, summon) require operator permission level 2 (gamemaster) or higher. Console and other sources with equivalent permission can also use them.

- `/dropevent summon [<item>]` - Spawns a drop-event item display (tagged `drop_event_item`) at the command source's position. `<item>` uses the vanilla item syntax `namespace:item[components]`, including modded items, with tab-completion. Without `<item>`, the executing entity's main-hand item is copied (count 1, the held stack is not consumed); an empty main hand fails with an error. The linker then spawns the guard automatically; activate bossbars with `/dropevent start` as before.

- `/dropevent start` - Activates a bossbar for every loaded `minecraft:item_display` with the tag `drop_event_item` in the command source's dimension that has a linked guard shulker. The bars are visible to all players, including players who join later.
	- BossBar title format: `{Item Name} at {X, Y, Z}` (rounded integers from the display position).
	- When a new bar appears, all currently connected players receive a Xaero's Minimap chat waypoint. Repeating start for an already active bar does not resend it. The waypoint uses the display's block position and dimension, named `Dropevent:<item display name>` (cut to Xaero's 32 character limit; names containing Xaero's own `^col^`, `^min^` or `^ast^` markers show them decoded); the chat announcement carries only the share token (Xaero clients see Xaero's own share message with its Add button instead), and the bossbar title also shows the item name. Xaero's is optional on clients, not a server dependency.
	- Waypoints are chat shares, not automatically saved or removed markers. Joining later receives the bossbar but does not replay old waypoint messages. Unloading and restarting an event can announce it again.
	- The BossBar lifecycle is tied to the linked shulker (the one referenced by the display tag `linked_shulker:<uuid>`).

- `/dropevent pause`, `/dropevent resume` - Pause a running event (its bossbar stays visible with ` (Paused)` appended, health is kept) and resume a paused one (suffix removed, no chat message or waypoint).
- `/dropevent stop` - Works on an event in any state: removes its bossbar, guard shulker and display with no reward.
- `start`, `pause`, `resume` and `stop` take an optional `all`. Without it the command acts on the nearest eligible event in the command source's dimension (start needs an untouched event, pause a running one, resume a paused one, stop any). With `all` it acts on every eligible event in every dimension. `summon` has no `all`.
- Event state is a tag on the guard (mirrored on the display): none = untouched, `drop_event_running`, `drop_event_paused`. Untouched and paused guards carry infinite Resistance V, which is removed while running; it does not stop `/kill` or the void. A guard that dies while untouched or paused removes its display without a reward; only a running event pays out. On server stop running events become paused; a stale running tag after a crash is paused on the next tick. Bars come back after chunk reloads. After a restart, previously running events return paused with their bars (the first tick after a crash has no Resistance yet).
- `stop` skips an event whose linked guard cannot be found, because it may still be loading; the reply says how many were skipped. A guard that stays missing for 40 consecutive ticks while its chunk is ticking is treated as lost: an inactive event's display is removed without a reward, a running one pays out.

Automatic cleanup: when a `drop_event_shulker` dies, the mod automatically removes/hides the associated BossBar for all players and then drops the display's item.

## How to summon a tagged item display
Use the command:

```
/dropevent summon minecraft:diamond
```

or hold the item in your main hand and run `/dropevent summon`. The shulker guard spawns and follows the display automatically.

Manual summoning still works exactly as before:

```
/summon item_display ~ ~ ~ {Tags:["drop_event_item"],item:{id:"minecraft:diamond",count:1}}
```

## Notes
- By default, the shulker is invisible and has 1000 max health. The `drop-events:visible_guard` and `drop-events:shulker_health` gamerules control visibility and health (health applies to newly spawned guards). Guards have no AI and do not drop vanilla loot or XP. The event display still drops its configured item.
- Removing an event display permanently removes its linked guard. Normal chunk unloading preserves the event entities, but hides the active bossbar; use `/dropevent start` after loading the event again.
- Linked shulkers carry the `drop_event_shulker` tag; displays store `linked_shulker:<uuid>` internally.

## Testing
From the mod folder:
```
.\\gradlew build
.\\gradlew runClient
```
Then, create a world and run the commands above in chat to verify behavior.