# Drop Events (1.21.11)

Server-only mod behavior: it runs entirely on the server and does not require any client-side code.

## What it does
- Watches for `minecraft:item_display` entities tagged `drop_event_item` (scoreboard tag).
- Spawns and links a shulker guard to that display (server-side only).
- Teleports the shulker to the display every tick.
- If the shulker dies, the display drops its item and is removed.

## Commands

The `/dropevent start` and `/dropevent stop` commands require operator permission level 2 (gamemaster) or higher. Console and other sources with equivalent permission can also use them.

- `/dropevent start` - Activates a bossbar for every loaded `minecraft:item_display` with the tag `drop_event_item` in the command source's dimension that has a linked guard shulker. The bars are visible to all players, including players who join later.
	- BossBar title format: `{Item Name} at {X, Y, Z}` (rounded integers from the display position).
	- When a new bar appears, all currently connected players receive a Xaero's Minimap chat waypoint. Repeating start for an already active bar does not resend it. The waypoint uses the display's block position and dimension, named `Dropevent:<item display name>` (cut to Xaero's 32 character limit); the chat announcement carries only the share token (Xaero clients see Xaero's own share message with its Add button instead), and the bossbar title also shows the item name. Xaero's is optional on clients, not a server dependency.
	- Waypoints are chat shares, not automatically saved or removed markers. Joining later receives the bossbar but does not replay old waypoint messages. Unloading and restarting an event can announce it again.
	- The BossBar lifecycle is tied to the linked shulker (the one referenced by the display tag `linked_shulker:<uuid>`).

- `/dropevent stop` - Targets the nearest loaded tagged display in the command source's dimension (no distance limit). Manually removes the BossBar for the targeted linked shulker, drops the linked display item, and cleans up the shulker/display entities.

Automatic cleanup: when a `drop_event_shulker` dies, the mod automatically removes/hides the associated BossBar for all players and then drops the display's item.

## How to summon a tagged item display
1) Summon an item display with an item and the Tag: drop_event_item :

```
/summon item_display ~ ~ ~ {Tags:["drop_event_item"],item:{id:"minecraft:diamond",count:1}}
```

The shulker should spawn and follow the display.

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