# farview-but-good

Paper plugin for 1.21.8, 1.21.11, 26.1, 26.2 and 26.3 (Folia on the versions Folia ships for) that lets players see terrain beyond the server's view distance.

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

`./gradlew runServer` starts a Paper 26.3 test server in `run/paper-26.3`; `runServer262`, `runServer261`, `runServer12111` and `runServer1218` start the other supported versions in `run/paper-<version>`.

## Minecraft versions

The root project compiles against the oldest supported `paper-api` (1.21.8) with Java 21 class files. Everything that touches server internals lives behind the `FarViewNms` bridge in `nms/common`, with one self-contained implementation module per Minecraft version (`nms/v1_21_8`, `nms/v1_21_11`, `nms/v26_1`, `nms/v26_2`, `nms/v26_3`), each compiled against its own paperweight dev bundle at that server's Java level. `FarViewPlugin.loadNms` maps the exact `Bukkit.getMinecraftVersion()` string to a module (26.1, 26.1.1 and 26.1.2 share `v26_1`) and loads it by reflection, so a server never touches class files built for another Java version. Versions not in that map are refused at startup.

To add a version: copy the closest `nms/v*` module, rename its package, add its dev bundle to `gradle/libs.versions.toml`, include it in `settings.gradle.kts` and the root `build.gradle.kts` dependencies, add the version strings to `NMS_MODULES`, then fix whatever the compiler reports. Known differences so far: 1.21.x writes one section count short where 26.x writes two; 1.21.8 has no top-level `Strategy` or `PalettedContainerFactory` and still uses `ResourceLocation`; between 26.2 and 26.3 the chunk and light packet classes became records, the light bitsets switched to a byte-array encoding, Starlight's light state tags disappeared, and block palettes can store default states as plain strings.
