# farview-but-good

Paper and Folia 26.2 plugin that lets players see terrain beyond the server's view distance.

It reads already-generated chunks straight from the world's region files and sends them to the client as chunk packets over netty. The server never loads, ticks, or generates them. Fake chunks are a disk snapshot: no entities and no block updates until the player walks into the real view distance. Only chunks that are generated, saved and lit are sent, and players still need to raise their own client render distance.

Per-player send rate adapts to measured ping, jitter and netty backpressure, or a player can pin a fixed speed.

## Commands

Alias: `/fv`

| Command | What it does |
|---|---|
| `/farview status` | Your current settings and measured connection |
| `/farview on` / `off` | Toggle the extended view for yourself |
| `/farview distance <4..32>` | Chunk radius you want |
| `/farview rate auto` | Let the plugin adapt the send rate |
| `/farview rate <kbps>` | Pin a send rate; snaps to the nearest configured step |
| `/farview reload` | Reload config and restart sessions |

## Permissions

| Permission | Grants |
|---|---|
| `farview.use` | All player commands |
| `farview.reload` | `/farview reload` |

## Config

`plugins/farview/farview.conf`, seeded with comments on first start. Player choices are stored in `plugins/farview/preferences.json`.

## Build

```
./gradlew build
```

Output: `build/libs/farview-<version>-all.jar`. Pushes to `main` publish a release tagged with the commit hash.
