# Paper-Chunk-Randomizer (Beta)

Paper-Chunk-Randomizer is a beta Paper plugin build of the Random Chunks challenge originally created by Eckelsoft.

Every new chunk a player enters is transformed into one deterministic replacement block for that chunk. The result is a chaotic survival run where terrain, caves, oceans, and structures can all turn into something completely different while you try to reach the End and finish the game.

<img width="768" height="512" alt="image" src="https://github.com/user-attachments/assets/177cf0a3-ef05-429b-9402-5b733e652fcd" />

## Status

- Current release line: `0.2.0`
- Target platform: Paper `1.21.11`
- Required Java version: `21`

## Credits

- Original creator of Chunk Randomizer / Random Chunks: `Eckelsoft`
- Original project source: [eckelsoft/Chunk-Randomizer](https://github.com/eckelsoft/Chunk-Randomizer)
- This repository contains a beta Paper adaptation of that original project

## What It Does

- Replaces blocks in newly entered chunks with a chunk-specific random material
- Applies chunk-based potion effects when players enter a new chunk
- Spawns surprise mobs after a configurable number of unique chunk visits
- Shows a challenge timer in the action bar
- Supports a custom replacement blacklist
- Gives players Chunk Walker boots when the run starts

## Download

The compiled beta plugin jar is written with a unique build suffix each time you build, for example:

`build/libs/Paper-Chunk-Randomizer-0.2.0-build.20260410000000.jar`

Drop that jar into your server's `plugins/` folder and restart the server.

## Installation

1. Stop your Paper server.
2. Copy the newest `Paper-Chunk-Randomizer-0.2.0-build.<timestamp>.jar` into `plugins/`.
3. Start the server.
4. Configure the plugin in `plugins/Paper-Chunk-Randomizer/config.yml` if needed.
5. Use `/rc start` in-game to begin the challenge.

## Build From Source

```bash
gradle build
```

The build output is written to `build/libs/`.

If you are targeting a different Paper `1.21.x` version, update these values in `gradle.properties`:

- `paper_api_version`
- `paper_api_compat_version`

## Quick Command Reference

| Command | Description |
|:--|:--|
| `/rc start` | Starts the challenge and timer |
| `/rc stop` | Pauses the challenge and timer |
| `/rc reset` | Resets the timer to `00:00:00` |
| `/rc timer` | Toggles the action-bar timer |
| `/rc timer prefix <text>` | Sets text before the timer |
| `/rc timer suffix <text>` | Sets text after the timer |
| `/rc effects` | Toggles chunk-based potion effects |
| `/rc fluids <true|false>` | Toggles whether water, lava, and fire may be replaced |
| `/rc exclude add <block>` | Adds a block to the replacement blacklist |
| `/rc exclude clear` | Clears the custom blacklist |
| `/rc spawnmonster chunks <value>` | Sets how many unique visited chunks trigger mob spawns |
| `/rc spawnmonster max <value>` | Sets the max number of surprise mobs, including `0` to disable them |
| `/rc debug <0-3>` | Sets debug output level |

Detailed command documentation lives in [docs/COMMANDS-WIKI.md](/Users/razvancostache/Documents/chuckys/docs/COMMANDS-WIKI.md).

## Permissions

| Permission | Description | Default |
|:--|:--|:--|
| `paperchunkrandomizer.admin` | Allows `/rc exclude add` and `/rc exclude clear` | `op` |

## Default Config

```yaml
debug-level: 1
timer-visible: true
effects-enabled: true
spawn-interval: 16
max-mobs: 8
replace-fluids: false
timer-prefix: ""
timer-suffix: ""
custom-blacklist: []
```

## Config Options

| Key | Description |
|:--|:--|
| `debug-level` | `0-3` debug output level |
| `timer-visible` | Whether the action-bar timer is shown |
| `effects-enabled` | Whether chunk potion effects are active |
| `spawn-interval` | Unique chunk visits required to trigger surprise spawns |
| `max-mobs` | Maximum amount of surprise mobs spawned at once, with `0` disabling surprise mobs |
| `replace-fluids` | Whether protected fluids and fire may be replaced |
| `timer-prefix` | Text shown before the timer |
| `timer-suffix` | Text shown after the timer |
| `custom-blacklist` | Materials excluded from becoming chunk replacements |

## How The Paper Beta Works

- Chunk replacement is deterministic per world and chunk, so revisiting the same chunk gives the same result.
- The plugin uses Paper `Material`, `PotionEffectType`, and `EntityType` pools instead of Fabric registries.
- Replacement candidates are filtered to avoid fragile or problematic block types such as portals, stairs, rails, banners, beds, signs, and similar special blocks.
- This is a beta Paper adaptation, so behavior is intentionally close to the original but not a byte-for-byte Fabric clone.

## Gameplay Notes

- Players receive Chunk Walker boots the first time the challenge is started.
- Chunk Walker boots prevent chunk randomization and chunk effects for the wearer.
- Chunk Walker boots lose `10` durability per protected chunk entry.
- Use `/rc exclude add minecraft:...` to avoid especially laggy or annoying replacement blocks.
- Use `/rc spawnmonster chunks 99999` if you want to effectively disable surprise mob spawns.

## Included Docs

- [README.md](/Users/razvancostache/Documents/chuckys/README.md)
- [docs/COMMANDS-WIKI.md](/Users/razvancostache/Documents/chuckys/docs/COMMANDS-WIKI.md)

## Credit Notice

Full credit for the original Chunk Randomizer idea and original project goes to Eckelsoft. This Paper-Chunk-Randomizer beta build is an adaptation built on top of that original work.
