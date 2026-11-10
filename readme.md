# Drop Events (1.21.11)

Server-only mod behavior: it runs entirely on the server and does not require any client-side code.

## What it does
- Watches for `minecraft:item_display` entities tagged `drop_event_item` (scoreboard tag).
- Spawns and links a shulker guard to that display (server-side only).
- Teleports the shulker to the display every tick.
- If the shulker dies, the display drops its item and is removed.

## Commands

The `/dropevent start`, `/dropevent stop` and `/dropevent summon` commands require operator permission level 2 (gamemaster) or higher. Console and other sources with equivalent permission can also use them.

- `/dropevent summon [<item>]` - Spawns a drop-event item display (tagged `drop_event_item`) at the command source's position. `<item>` uses the vanilla item syntax `namespace:item[components]`, including modded items, with tab-completion. Without `<item>`, the executing entity's main-hand item is copied (count 1, the held stack is not consumed); an empty main hand fails with an error. The linker then spawns the guard automatically; activate bossbars with `/dropevent start` as before.

- `/dropevent start` - Activates a bossbar for every loaded `minecraft:item_display` with the tag `drop_event_item` in the command source's dimension that has a linked guard shulker. The bars are visible to all players, including players who join later.
	- BossBar title format: `{Item Name} at {X, Y, Z}` (rounded integers from the display position).
	- The BossBar lifecycle is tied to the linked shulker (the one referenced by the display tag `linked_shulker:<uuid>`).

- `/dropevent stop` - Targets the nearest loaded tagged display in the command source's dimension (no distance limit). Manually removes the BossBar for the targeted linked shulker, drops the linked display item, and cleans up the shulker/display entities.

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