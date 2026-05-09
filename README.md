# Storra plugin

Minecraft server plugin for [Storra](https://storra.xyz). Pairs to a
Storra tenant and runs in-game delivery commands (giving ranks,
items, currency) when customers complete a purchase.

## Requirements

- **Paper 1.21+** (Spigot is not officially supported — Paper-only
  APIs are used for TPS reporting)
- **Java 21** (Paper 1.21's minimum)

## Install

1. Download the latest `storra-plugin-<version>.jar` from
   [Releases](https://github.com/ShopStorra/storra-plugin/releases).
2. Drop the JAR into your server's `plugins/` folder.
3. Restart your server. The plugin creates
   `plugins/StorraPlugin/config.yml` on first run.
4. Open the Storra dashboard → **Game Servers** → click your server →
   **Generate code**.
5. From your server console, run:
   ```
   /storra pair ABCD-1234
   ```
   The plugin exchanges the code for a server-id + secret, writes
   them to `config.yml`, and starts polling Storra for deliveries.

That's it. Test purchases will dispatch in-game within
`poll-interval-seconds` (default 30s).

## Manual configuration (advanced)

If your host doesn't allow interactive console commands, the
dashboard's pairing modal also reveals the raw `server-id` and
`secret`. Paste both into `config.yml` and restart:

```yaml
api:
  base-url: "https://storra.xyz"
  server-id: "<paste from dashboard>"
  secret: "<paste from dashboard>"
```

The plaintext secret is only revealed once. If you lose it,
generate a new pairing code (which rotates the secret).

## Commands

| Command | What it does |
|---|---|
| `/storra pair <code>` | One-time bootstrap; exchanges the code for credentials and starts services |
| `/storra status` | Pairing state, last heartbeat, queue depth |
| `/storra history` | Last 100 delivered orders |
| `/storra reload` | Re-read `config.yml` (after manual edits) |

All commands require the `storra.admin` permission (default: `op`).

## Troubleshooting

- **"not paired" after restart** — `/storra pair` needs to land in
  `config.yml`. Check `plugins/StorraPlugin/config.yml` has both
  `server-id` and `secret` populated.
- **Pairing code expired** — codes are valid for 10 minutes. Generate
  a fresh one in the dashboard.
- **Deliveries not running** — check `/storra status`. If the plugin
  is paired but heartbeat is stale, your server probably can't reach
  `storra.xyz` (firewall / DNS).

## Building from source

```bash
./gradlew shadowJar
```

The packaged JAR lands in `build/libs/storra-plugin-<version>.jar`.

## License

MIT — see [LICENSE](LICENSE).
