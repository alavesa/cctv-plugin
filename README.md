# CCTV

CCTV cameras, full-screen camera view and live monitors for **Paper 26.2 and 26.3** with **vanilla clients** (plugin +
server resource pack, no client mods). One jar and one pack serve both versions.

| | Minecraft 26.3 | Minecraft 26.2 |
|---|---|---|
| Camera look | per-player post effects: standard / night vision / thermal, plus zoom, static bursts, damaged-signal and SIGNAL LOST effects | the same three looks, through the spectated-mob shaders (the camera anchor is an invisible creeper / spider / enderman); no zoom effect, static bursts or signal effects (the HUD cards still show) |
| HUD, monitors, CRT, GUIs, alerts | yes | yes |
| Body double | FancyNpcs NPC if installed, else Mannequin | same | Built in phases; see [docs/PHASE1-ARCHITECTURE.md](docs/PHASE1-ARCHITECTURE.md).

**Done so far (Phases 2–4):** camera objects, persistence, commands, events and API; the full-screen camera view
with post effects, controls and the resource pack (camera models, post effects, sounds.json); the CCTV on-screen
display (REC, camera, time, network, signal, camera list, state cards) drawn with CCTV fonts and styled by the
`core/text` shader; monitors (desk, wall, 2×2, 3×2) with a server-rendered live feed on map tiles and a CRT shader.
GUIs (camera browser, admin editor, placement menu), security terminals, restricted-zone alerts.
Docs: [build & install](docs/BUILD.md) · [resource pack: standalone or merged](docs/PACK.md) ·
[assets](docs/ASSETS.md) · [HUD and the text-shader marker](docs/HUD.md) · [monitors](docs/MONITORS.md) ·
[test checklist](docs/TESTING.md) · [troubleshooting](docs/TROUBLESHOOTING.md) ·
[original architecture plan](docs/PHASE1-ARCHITECTURE.md).

**Vanilla files the pack overrides:** `assets/minecraft/shaders/core/text.vsh` and `text.fsh`, each with two small
`// ===== CCTV BEGIN/END =====` blocks; the logic is in `assets/cctv/shaders/include/`. Everything else is in the
`cctv` namespace. If another pack overrides the same files, see [docs/HUD.md](docs/HUD.md#if-another-pack-also-overrides-coretextvsh--textfsh).

## Build

```
./gradlew build          # -> build/libs/cctv-<version>.jar
```

Needs any JDK ≥ 25. Details, install/update steps and the asset scripts: [docs/BUILD.md](docs/BUILD.md).

## Cameras

- `/cctv give <type> [player] [amount] [group]`, then right-click a block face. Top face = floor mount,
  bottom face = ceiling mount, side = wall mount. Wall cameras look straight out of the wall. Floor and ceiling
  cameras look the way you were looking.
- Hitting a camera damages it (`cctv.damage`). Arrows and explosions damage it too. At 0 health it is DESTROYED
  (broken model, smoke). Right-click it with the repair item (default iron ingot, `cctv.repair`) to repair.
- Sneak-hit with `cctv.pickup` takes the camera down. Outside creative you get the placement item back.
- Camera types are entirely defined in `config.yml` (`types:`). Five ship by default: dome, bullet, ptz,
  night_vision, thermal.

### Commands (`cctv.command.<sub>`, alias `/cam`)

| Command | |
|---|---|
| `give <type> [player] [amount] [group]` | placement item |
| `create <type> [group] [name...]` | mount on the block face you look at |
| `remove [id]` | `[id]` = the camera you look at if omitted |
| `list [group\|*] [page]` | click a line to teleport |
| `info [id]` | full record |
| `rename <id> <name...>` / `setgroup <id> <group>` | |
| `tp <id>` | stand just in front of the lens |
| `power <id\|group:name> <on\|off>` / `enable <id> <on\|off>` | power vs admin switch |
| `repair [id] [amount]` / `damage <id> <amount>` | |
| `aim <id> <pan> <tilt>` | PTZ target, clamped; the head turns at the type's `pan-speed` |
| `set <id> <fov\|clearance\|pan-min\|pan-max\|tilt-min\|tilt-max\|yaw\|pitch\|type> <value>` | |
| `view <id> [player]` / `exit [player]` | open / close a feed |
| `pack [player]` | pack sha1, effects backend, per-player pack status; with a player: re-send the pack |
| `reload` | config.yml (also rebuilds and re-sends the pack if it changed) |

## Viewing a camera

`/cctv view <id> [player]` (monitors and terminals will call the same thing in later phases; plugins use
`CctvApi.startViewing`). The player becomes a spectator attached to a hidden anchor at the lens. A Mannequin with
their skin stays where they stood. If the Mannequin is hurt, the feed cuts to SIGNAL LOST, the player is put back,
and they take the damage.

| Key (shown with your own key bindings) | PTZ camera | Fixed camera |
|---|---|---|
| A / D (hold) | pan | previous / next camera |
| W / S (hold) | tilt | – |
| Sprint + A / D | previous / next camera | previous / next camera |
| Jump | zoom 1× / 2× / 4× (per type `zoom-levels`): the view moves forward along its line of sight, stopping before walls (`view.zoom`) | same, if the type has zoom levels |
| Sneak | exit | exit |

Why these keys: a spectating vanilla client only sends its movement keys (`PlayerInputEvent`). Hotbar scroll and
clicks are never sent while spectating, so they can't be controls.

The session ends cleanly (game mode, position and flight restored, effects removed) on exit, quit, death, any
teleport by another plugin, a game-mode change by another plugin, plugin disable, and after a crash (a restore
record in the player's data is applied on the next join). If the camera dies mid-feed, the view switches to the
next working camera in the group (`view.on-camera-lost`).

### Effects and the pack

- The pack is sent as an **additional** pack (`pack.delivery: http`, built-in server on port 8164), so your
  server pack stays. Use `url` to host `plugins/CCTV/cctv-pack.zip` yourself, or `none` if you merged it.
- Effects are sent only once the client reports the CCTV pack loaded. Otherwise the feed works without them and
  the player is told.
- `effects.backend: auto` uses Paper's `Player.postEffects()`. It only ever adds or removes `cctv:` effects, never
  clears other plugins' effects. `mob` uses the vanilla creeper/spider/enderman spectator shaders (no pack needed;
  three fixed looks; the enderman version shows its own portal particles, inverted).
- OptiFine and Iris/Oculus with a shaderpack can ignore or replace post effects and core shaders.
  Sodium alone is fine for post effects.

### Permissions

| Permission | Default | What |
|---|---|---|
| `cctv.admin` | op | everything below |
| `cctv.command.<sub>` | op | each `/cctv` sub-command (give, create, remove, list, info, rename, setgroup, tp, power, enable, repair, damage, aim, set, view, exit, pack, monitor, perf, browse, admin, terminal, zone, reload) |
| `cctv.place` / `cctv.pickup` | op | mount cameras / sneak-hit them down |
| `cctv.repair`, `cctv.damage` | true | repair with the repair item / hurt cameras |
| `cctv.view` | true | view feeds at all (clearance is checked separately) |
| `cctv.clearance.<n>` / `cctv.clearance.all` | – / op | default ClearanceProvider: level n and below / everything |
| `cctv.monitor.place`, `cctv.monitor.pickup` | op | monitors |
| `cctv.monitor.use` | true | click a monitor to open its camera |
| `cctv.terminal.place`, `cctv.terminal.pickup` | op | terminals |
| `cctv.terminal.use` | true | use terminals (their clearance is checked too) |
| `cctv.control.anywhere` | op | turn PTZ cameras, zoom and switch from anywhere. Everyone else gets these controls only when they open the camera at a security terminal; from a monitor, `/cctv view` or `/cctv browse` they can watch and leave (HUD: VIEW ONLY) |
| `cctv.alert.receive` | op | get alerts in chat without viewing that network |
| `cctv.alert.exempt`, `cctv.zone.exempt.<zone>` | false | never trigger alerts (everywhere / in one zone) |

## GUIs, terminals, alerts

- `/cctv browse`: the camera browser (network tabs, search, status filter, pages). Click a camera to watch it.
  Cameras you lack clearance for show ACCESS DENIED.
- `/cctv admin`: the same browser in admin mode. Click a camera to edit it (rename and network by dialog,
  type, power, enabled, repair, FOV, clearance, facing, pan/tilt limits, teleport, view, shift-click remove).
  The chest button hands out placement items for every camera, monitor and the terminal, so nothing needs commands.
- **Security terminal** (`/cctv terminal give`, or the admin place menu): put it on a block. Right-click it to
  open the browser, after `cctv.terminal.use` and the terminal's clearance level pass through the ClearanceProvider.
  Lock it to one network with `/cctv terminal group here <network>`.
- **Alerts**: stand in one corner, `/cctv zone pos1`; the opposite corner, `/cctv zone pos2` (2 blocks of head room
  are added on top); then `/cctv zone create <name> <network>`. A working camera of that
  network that sees a player inside the zone (range, FOV cone, line of sight) goes into alert. Its viewers see a
  blinking `! ALERT` line and a `!` in the camera list, they and `cctv.alert.receive` players get a chat message and
  the alert sound, and `CameraAlertEvent` fires (cancellable) for your own rules.

### State

`DISABLED` (admin) › `DESTROYED` (0 hp) › `UNPOWERED` › `DAMAGED` (< `damaged-threshold` × max) › `ONLINE`.
Only ONLINE and DAMAGED are viewable.

## API

```java
CctvApi cctv = Bukkit.getServicesManager().load(CctvApi.class);
cctv.setGroupPowered("heavy-containment", false);           // power grid outage
cctv.damage("cam-4", 6, shooter);                            // a gun plugin
cctv.setClearanceProvider((player, level) -> myCards.level(player) >= level);
```

Events (package `fi.alavesa.cctv.api.event`, all main thread):

| Event | Cancellable | When |
|---|---|---|
| `CameraCreateEvent` | yes | before a camera is registered (`getCreator()` may be null) |
| `CameraRemoveEvent` | no | after removal; cause COMMAND / PICKUP / API |
| `CameraDamageEvent` | yes, amount settable | before health drops; cause MELEE / PROJECTILE / EXPLOSION / API |
| `CameraRepairEvent` | yes, amount settable | before health rises |
| `CameraDisableEvent` | no | camera stopped being viewable; reason DESTROYED / POWER / ADMIN / REMOVED |
| `CameraEnableEvent` | no | camera became viewable again; reason REPAIRED / POWER / ADMIN |

View events (`CameraViewStartEvent`, `CameraViewEndEvent`, `CameraSwitchEvent`) arrive with Phase 3.

## How cameras exist in the world

`plugins/CCTV/cameras.yml` is the source of truth. It's written asynchronously through a temp file plus an atomic
move, at most every `storage.autosave-seconds`, and synchronously on shutdown. Each camera is up to three
**non-persistent** entities (`base` item_display, `head` item_display, `hit` interaction), tagged `cctv:camera` /
`cctv:part`. They spawn when the camera's chunk loads and are removed when it unloads. They're never saved with the
world, so a crash can't duplicate them. Any tagged entity that isn't a live part is removed when entities load.

### Model conventions (for the Phase 3 assets)

Item display transform `NONE`, models centred on the origin (Blockbench 8,8,8):

- **head**: the lens points to **+Z** (south). Rotated to the live view direction.
- **base**: authored as a **floor** bracket (attaching surface at −Y, front toward +Z). Rotated per mount: ceiling =
  flipped about Z, wall = attaching surface turned to face back into the wall.
