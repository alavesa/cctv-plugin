# Troubleshooting

Start with `/cctv pack` (pack sha1, effects backend, per-player pack status) and `/cctv perf` (monitors). Client-side
problems are in the player's `logs/latest.log`, not the server log.

## The update did nothing / old behaviour after updating

An old CCTV jar is still in `plugins/`. Paper loads the first jar with the same plugin name. Delete every
`cctv-*.jar` except the new one, restart, and check the `Enabling CCTV v…` line.

## CCTV is red in /plugins

The log line above the plugin list says why. `Unsupported API version` means the jar is older than this fix (0.1.0
from before 26.2 support): update it. CCTV supports Paper 26.2 and 26.3.

## Cameras, monitors or terminals are purple-black cubes

The client doesn't have the CCTV pack. Check:
- `/cctv pack` → the player's status. `DECLINED`: they said no (set `pack.required: true` to enforce).
  `FAILED_DOWNLOAD`: the URL isn't reachable.
- `delivery: http`: port 8164 must be reachable from the player. Behind Velocity/Bungee/NAT, set
  `pack.http.public-host` to the public address. The server log says if the HTTP server couldn't start (port in use).
- `delivery: url`: `pack.url` must point at the **current** `plugins/CCTV/cctv-pack.zip` (re-upload after updates; the
  sha1 changes).
- Client log `Error reading pack metadata` or the pack shown as incompatible: CCTV supports formats 88 (26.2) and 97
  (26.3) only.

## No CCTV look (no scanlines/grain) when viewing

- The pack isn't loaded for that player (see above). They get a chat message about it.
- `effects.backend` is `none`.
- On **26.2** the look comes from the mob backend: the pack's creeper/spider/invert overrides. If another pack also
  overrides `assets/minecraft/post_effect/creeper.json` (etc.) with higher priority, you get that pack's look. Zoom,
  static bursts and damaged-signal effects don't exist on 26.2.
- OptiFine or Iris/Oculus with a shaderpack disables post effects. Turn the shaderpack off.

## Text looks wrong, or the HUD has no effects

- Another pack overrides `assets/minecraft/shaders/core/text.vsh/fsh` with higher priority. Merge them
  ([PACK.md](PACK.md)).
- A merged `text.fsh` failed to compile: the client log names the shader and line. Validate with
  `tools/validate_shaders.py`.

## The HUD is cut off, or tiny

The server can't know a player's GUI scale. Lower `hud.half-width` (default 100) if corners clip; raise it for
players who use a small GUI scale. The full-screen brackets and vignette are not affected (they come from the post
effect).

## Stuck in spectator mode / lost position after a crash

Rejoining applies the restore record automatically ("You were returned from a camera feed after a restart"). If a
player is still a spectator: `/cctv exit <player>`, or `/gamemode survival <player>` and teleport them.

## Camera keys do nothing

- Controls are the movement keys (A/D/W/S, Ctrl, Space, Shift). Hotbar scroll and clicks can't work while
  spectating (the client never sends them).
- Keys are ignored for the first `view.attach-delay-ticks` while the view attaches.
- Another plugin cancelling `PlayerInputEvent` blocks them.

## Monitors stay blank or show STANDBY

- They render only while someone is **watching**: a non-spectator player within `monitors.render.watch-radius`,
  in front of the screen, with line of sight. Anyone else sees STANDBY static.
- Not bound: `/cctv monitor bind here <camera>`. "NO CAMERA" on the screen means exactly that.
- The camera is offline: the screen says so (CAMERA OFFLINE / POWER FAILURE).
- `/cctv perf` shows `frames 0`: the chunk-snapshot budget may be too small for many monitors
  (`ms-budget-per-tick`), or the camera's surroundings aren't loaded (feeds never load chunks).

## Monitors look like ordinary flat maps (no CRT)

The CRT layer lives in the same text shader: see "Text looks wrong" above. The sentinel needs the exact 26.3
palette, so pack formats for other Minecraft versions won't match.

## Nobody sees the player's body while they watch a camera

`view.body` (auto = FancyNpcs if installed, else Mannequin). The log says `FancyNpcs found: …` on start when the
bridge bound. If it says `FancyNpcs body double failed (…)`, the reason follows and a Mannequin is used instead. With
`view.body: none` the player simply vanishes while viewing (they are a spectator). FancyNpcs NPCs can be punched (that
pulls the viewer out) but not shot or blown up (they aren't real entities); a Mannequin takes everything.

## Lag

`/cctv perf`. Main-thread costs are chunk snapshots (bounded by `ms-budget-per-tick`) and entity lists. Rendering is
on `cctv-feed-*` threads. Levers, cheapest first: fewer watchers per monitor (`watch-radius`), `render.fps` 1,
`render.scale` 2 or 4, smaller walls, `mode snapshot`, `mode shader`. The governor already steps down when mspt rises
above `governor.high-mspt`. Bandwidth is about 16 KB per tile per frame per watcher.

## Alerts don't fire

- The zone's network must match the cameras' group (`/cctv zone list`, `/cctv list <group>`).
- The camera must see the player: within `alerts.range`, inside its view cone, with no blocks in between, and the
  camera must be working.
- Exempt players: `cctv.alert.exempt`, `cctv.zone.exempt.<zone>` (explicitly set), spectators, or another plugin
  cancelling `CameraAlertEvent`.
- The same camera, zone and player alert again only after `alerts.cooldown-seconds`.

## No sounds

The pack defines the sound events but ships no audio. Add the `.ogg` files listed in [ASSETS.md](ASSETS.md) (mono
Ogg Vorbis) under `resourcepack/assets/cctv/sounds/`, rebuild or `/cctv reload` with `pack.source-folder`.

## Terminal says ACCESS DENIED

The terminal's clearance (`/cctv terminal list`) is checked with the active ClearanceProvider. With the default one,
the player needs `cctv.clearance.<n>` for that level or higher (or `cctv.clearance.all`), plus `cctv.terminal.use`.

## Getting help from logs

Server: anything with `[CCTV]`. Client: `logs/latest.log` lines about `cctv:` (missing files, shader errors, pack
errors). Include both, plus `/cctv pack` and `/cctv perf` output, when reporting a problem.
