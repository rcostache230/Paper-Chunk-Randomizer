# Paper-Chunk-Randomizer Commands Wiki

This page documents every command available in the beta Paper plugin version of Paper-Chunk-Randomizer.

Original project credit:

- Original Chunk Randomizer creator: `Eckelsoft`
- Original source: [eckelsoft/Chunk-Randomizer](https://github.com/eckelsoft/Chunk-Randomizer)

Base command:

```text
/rc
```

If used without arguments, the plugin shows a short usage summary.

## Permissions

Most commands are available to any player with access to the plugin command.

Admin-only commands:

- `/rc exclude add <block>`
- `/rc exclude clear`

Required permission:

- `paperchunkrandomizer.admin`

## Command List

### `/rc start`

Starts the challenge.

What it does:

- Activates chunk randomization
- Starts the timer
- Gives online players Chunk Walker boots if the timer was still at `00:00:00`

Example:

```text
/rc start
```

### `/rc stop`

Pauses the challenge.

What it does:

- Stops active chunk randomization from processing new entries
- Pauses the timer at its current value

Example:

```text
/rc stop
```

### `/rc reset`

Resets the timer to zero.

Example:

```text
/rc reset
```

### `/rc timer`

Toggles the action-bar timer display.

Running the command again switches it back.

Example:

```text
/rc timer
```

### `/rc timer prefix <text>`

Sets text shown before the timer.

Example:

```text
/rc timer prefix Hardcore Run
```

Result example:

```text
Hardcore Run 00:13:41
```

### `/rc timer suffix <text>`

Sets text shown after the timer.

Example:

```text
/rc timer suffix No Deaths
```

Result example:

```text
00:13:41 No Deaths
```

### `/rc effects`

Toggles chunk-based potion effects on or off.

When enabled:

- Entering a new chunk may apply one deterministic effect for that chunk

Running the command again switches it back.

Example:

```text
/rc effects
```

### `/rc fluids <true|false>`

Controls whether water, lava, and fire are allowed to be replaced.

Values:

- `true` enables fluid and fire replacement
- `false` keeps them protected

Examples:

```text
/rc fluids true
/rc fluids false
```

### `/rc exclude add <block>`

Adds a material to the plugin's custom replacement blacklist.

This prevents that block from being chosen as a chunk replacement target.

Permission:

- `paperchunkrandomizer.admin`

Examples:

```text
/rc exclude add minecraft:stone
/rc exclude add minecraft:obsidian
/rc exclude add minecraft:diamond_block
```

Notes:

- Use full material names when possible
- The plugin accepts names like `minecraft:stone`

### `/rc exclude clear`

Clears the custom replacement blacklist.

Permission:

- `paperchunkrandomizer.admin`

Example:

```text
/rc exclude clear
```

### `/rc spawnmonster chunks <value>`

Sets how many unique visited chunks are needed before surprise mobs spawn.

Examples:

```text
/rc spawnmonster chunks 16
/rc spawnmonster chunks 64
/rc spawnmonster chunks 99999
```

Notes:

- Higher values mean fewer surprise spawn events
- `99999` is a practical way to disable surprise spawns in normal gameplay

### `/rc spawnmonster max <value>`

Sets the maximum number of surprise mobs that may spawn in one event.

Allowed range:

- `1` to `64`

Examples:

```text
/rc spawnmonster max 4
/rc spawnmonster max 12
```

### `/rc debug <0-3>`

Controls debug output.

Levels:

- `0` disables debug output
- `1` shows mob-spawn debug
- `2` shows chosen chunk replacement block
- `3` shows chunk effect debug

Examples:

```text
/rc debug 0
/rc debug 1
/rc debug 2
/rc debug 3
```

## Common Setups

### Standard Challenge

```text
/rc reset
/rc spawnmonster chunks 16
/rc spawnmonster max 8
/rc start
```

### Less Punishing Run

```text
/rc fluids false
/rc spawnmonster chunks 64
/rc spawnmonster max 3
/rc start
```

### No Surprise Mobs

```text
/rc spawnmonster chunks 99999
/rc start
```

### Debug Session

```text
/rc debug 2
/rc start
```

### Enable Effects If They Were Turned Off Earlier

```text
/rc effects
```

## Notes For Server Admins

- Blacklist changes are saved to the plugin config.
- The timer prefix and suffix are saved to the plugin config.
- Effect toggles, timer visibility, spawn settings, debug level, and fluid replacement settings are saved to the plugin config.
- The plugin is designed for Paper `1.21.x` and Java `21`.
- The beta plugin data folder is `plugins/Paper-Chunk-Randomizer/`.

## Related Files

- [README.md](/Users/razvancostache/Documents/chuckys/README.md)
- [src/main/resources/config.yml](/Users/razvancostache/Documents/chuckys/src/main/resources/config.yml)
- [src/main/resources/plugin.yml](/Users/razvancostache/Documents/chuckys/src/main/resources/plugin.yml)
