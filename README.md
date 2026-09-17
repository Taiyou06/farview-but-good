# farview-but-good

Paper 26.2 and 26.3 plugin (Folia 26.2) that lets players see terrain beyond the server's view distance.

It reads already-generated chunks straight from the world's region files and sends them to the client as chunk packets over netty. The server never loads, ticks, or generates them. Fake chunks are a disk snapshot: no entities and no block updates until the player walks into the real view distance. Only chunks that are generated, saved and lit are sent, and players still need to raise their own client render distance.

It is on for every player by default at `default-view-distance` (16), capped at the player's own client render distance plus one. A player can pick any radius up to `max-view-distance` with `/farview distance`, or turn it off for themselves.

Per-player send rate adapts to measured ping, jitter and netty backpressure, or a player can pin a fixed speed.

## Commands

Alias: `/fv`

| Command | What it does |
|---|---|
| `/farview status [player]` | Current settings and measured connection |
| `/farview on [player]` / `off [player]` | Toggle the extended view |
| `/farview distance <4..32> [player]` | Chunk radius |
| `/farview rate auto [player]` | Let the plugin adapt the send rate |
| `/farview rate <kbps> [player]` | Pin a send rate; snaps to the nearest configured step |
| `/farview reload` | Reload config and restart sessions |

The `[player]` argument targets someone else and needs `farview.others`; the console must always name a player.

## Permissions

| Permission | Grants | Default |
|---|---|---|
| `farview.use` | All player commands on yourself | everyone |
| `farview.others` | The `[player]` argument, for admins and menus | op |
| `farview.reload` | `/farview reload` | op |

## Placeholders

PlaceholderAPI: `%farview_<key>%`. MythicMobs: `<caster.farview.<key>>`, `<target.farview.<key>>`, `<trigger.farview.<key>>`, also `parent` and `owner` scopes (empty when the entity is not a player). Both hook automatically when the plugin is present.

| Key | Value |
|---|---|
| `enabled` | `true`/`false`, the player's own toggle |
| `available` | `true`/`false`, whether their world is served |
| `distance` (or `dist`) | chosen radius in chunks |
| `client_distance` | the client's render distance as seen by the server |
| `rate` | `auto` or the pinned cap in KiB/s |
| `rate_mbps` | same, formatted in Mbps |
| `ping`, `jitter` | milliseconds as measured from keepalives; ping is `-1` until measured |
| `sending`, `budget` | current send rate and budget in KiB/s |
| `sending_mbps`, `budget_mbps` | same, formatted in Mbps |
| `ratio` | sending as a percentage of budget |
| `quality` | `measuring`, `good`, `unstable`, `distant` or `saturated` |

## Config

`plugins/farview/farview.conf`, seeded with comments on first start. Player choices are stored in `plugins/farview/preferences.json`.

## Build

```
./gradlew build
```

Output: `build/libs/farview-<version>-all.jar`. Pushes to `main` publish a release tagged with the commit hash.

`./gradlew runServer` starts a Paper 26.3 test server in `run/paper-26.3`, `./gradlew runServer262` a 26.2 one in `run/paper-26.2`.

## Minecraft versions

The root project compiles against `paper-api` only. Everything that touches server internals lives behind the `FarViewNms` bridge in `nms/common`, with one self-contained implementation module per Minecraft version (`nms/v26_2`, `nms/v26_3`), each compiled against its own paperweight dev bundle. `FarViewPlugin.loadNms` picks the module matching `Bukkit.getMinecraftVersion()` at startup.

To add a version: copy the newest `nms/v26_x` module, rename its package, add its dev bundle to `gradle/libs.versions.toml`, include it in `settings.gradle.kts` and the root `build.gradle.kts` dependencies, add a case to `loadNms`, then fix whatever the compiler reports. Between 26.2 and 26.3 that was the chunk and light packet classes becoming records, the light bitsets switching to a byte-array encoding, Starlight's light state tags disappearing, and block palettes being able to store default states as plain strings.
