# Drop Events (1.21.11)

Server-only mod behavior: it runs entirely on the server and does not require any client-side code.

## What it does
- Watches for `minecraft:item_display` entities tagged `drop_event_item` (scoreboard tag).
- Spawns and links a shulker guard to that display (server-side only).
- Teleports the shulker to the display every tick.
- If the shulker dies, the display drops its item and is removed.

## Commands

This mod exposes a simple server-only command to control an active drop-event display near the command source.

- `/dropevent start` — Targets the nearest `minecraft:item_display` with the tag `drop_event_item`. If that display is linked to a guard shulker, the mod creates a ServerBossBar visible to all players.
	- BossBar title format: `{Item Name} at {X, Y, Z}` (rounded integers from the display position).
	- The BossBar lifecycle is tied to the linked shulker (the one referenced by the display tag `linked_shulker:<uuid>`).

- `/dropevent stop` — Manually removes the BossBar for the targeted linked shulker, drops the linked display item, and cleans up the shulker/display entities.

Automatic cleanup: when a `drop_event_shulker` dies, the mod automatically removes/hides the associated BossBar for all players and then drops the display's item.

## How to summon a tagged item display
1) Summon an item display with an item:
```
/summon minecraft:item_display ~ ~ ~ {item:{id:"minecraft:diamond",count:1}}
```

2) Add the scoreboard tag:
```
/tag @e[type=minecraft:item_display,sort=nearest,limit=1] add drop_event_item
```

The shulker should spawn and follow the display.

## Notes
- The shulker is invisible, has 1000 max health, no AI, and does not drop any items when it dies.
- Linked shulkers carry the `drop_event_shulker` tag; displays store `linked_shulker:<uuid>` internally.

## Testing
From the mod folder:
```
.\\gradlew build
.\\gradlew runClient
```
Then, create a world and run the commands above in chat to verify behavior.