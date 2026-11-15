# Monitors and the live feed

## What a "live feed" really is (read this first)

A vanilla client can't show video inside a block, and no shader can look through another camera. What the plugin
does instead:

1. **The server draws the picture.** For each watched monitor it casts one ray per pixel from the camera through a
   copy of the nearby chunks (`ChunkSnapshot`, captured on the main thread, rendered on background threads). It
   shades in full colour:
   - each block's real colour per face, measured from the vanilla textures (`block_colors.tsv`, made by
     `tools/gen_block_colors.py`; only averages ship);
   - texture grain scaled by that texture's contrast;
   - Minecraft-style face shading and smooth ambient occlusion in corners and edges;
   - sky and block light, distance fog into a sky gradient;
   - see-through water with depth, tinted glass.

   The result is **dithered** into the 248 map colours, so gradients and tints are smooth instead of four flat steps.
   Non-full blocks have their shapes (slabs, stairs, fences, walls, doors, trapdoors, carpets, snow layers, chests,
   signs, ...; monitor/Shapes.java), and flowers, grass and crops are sparse crossed leaves instead of cubes.
   Players, Mannequins and FancyNpcs body doubles are drawn as the player model (head, body, arms, legs, turned to
   the body yaw) in their real skin and armour colours, and animated like the client does: legs and arms swing with
   the walking speed, the head turns and tilts where they look, sneaking leans, an attack swings the arm, swimming /
   gliding / sleeping lie flat, a hurt figure flashes red. (At the feed's 2 fps a walk shows as changing strides,
   not smooth motion.) Skins come from the profile's `textures` property (Mojang,
   or SkinsRestorer & co.) and are downloaded once from textures.minecraft.net; until then, and on offline-mode
   servers without a skin plugin, a placeholder person. Other mobs are shaded boxes by category. No name tags or
   particles.
2. **The picture goes onto maps.** Each block of screen is one 128×128 map in an invisible, fixed glow item frame.
   A 2×2 wall is 256×256 map pixels.
3. **The client's shader draws the screen.** Item-frame maps are drawn by the same `core/text` shader as the
   HUD. A sentinel pattern in each CCTV map's corners tells the shader to soften the dither back into smooth tone and
   pick the panel (pixel (3,0)):
   - **crt** (default; the `desk`, `wall`, `wall_2x2`, `wall_3x2`, `green_crt` types): curvature, scanlines,
     phosphor mask, noise, flicker, a rolling bar, vignette and edge glow per tile, like a wall of old monitors.
     Tuning: `CRT_*` constants.
   - **flat** (`screen: flat`; the `tv`, `tv_2x2`, `tv_3x2`, `tv_desk` types): a flat-screen TV. Square pixels, no
     per-tile effects, so a 2×2 or 3×2 TV reads as ONE screen inside one thin frame (`cctv:tv_tile_<mask>` bezels
     draw only the outer edges). NO SIGNAL, STANDBY, CAMERA OFFLINE and POWER FAILURE are a blue screen with the
     text, STATIC over a picture is digital macroblock break-up, boot is a backlight fade-in. Tuning: `FLAT_*`.

   Everything is animated on the GPU at no server cost. Built-in types that a server's config.yml doesn't list yet
   (the TVs, for configs written by 0.2.0) are read from the jar's defaults.

   The vertex shader pulls CCTV screens 0.03 blocks toward the viewer: an invisible item frame puts the map in the
   plane of the block face behind it, and without the pull the two depth-fight (the wall flickering through).

So the feed is a smooth-shaded, low-frame-rate picture (2 fps at the full 128 px per block by default). It reads
well as a security monitor. It is not video.

## Placing and wiring

- `/cctv monitor give <desk|wall|wall_2x2|wall_3x2|green_crt|tv|tv_2x2|tv_3x2|tv_desk>`, then right-click: the side of a block for wall
  monitors (every tile needs a solid block behind it), or the top of a block for desk monitors (an invisible barrier
  becomes the monitor body). Sneak-hit a screen (or break the wall behind it) with `cctv.monitor.pickup` to take it
  down.
- `/cctv monitor bind here <camera>` (look at the monitor).
- `/cctv monitor cycle here <seconds> <cam1,cam2,…|group:lobby>`: rotates through the cameras, with a static burst at
  each switch.
- `/cctv monitor quad here <c1> <c2> <c3> <c4>`: one screen split in four. Clicking a quadrant opens that camera.
- `/cctv monitor mode here <live|snapshot|shader|quad>` and `/cctv monitor power here <on|off>`.
- Right-click a screen to open the camera it shows full-screen (a cancellable `MonitorInteractEvent` fires first).

## Screen states (all but LIVE are drawn by the shader)

| State | When | Server cost |
|---|---|---|
| LIVE | camera online, someone watching | ray-cast at `render.fps` |
| STATIC | for `switch-static-ticks` after a cycle switch | none (state pixel only) |
| NO SIGNAL (colour bars) | no camera bound / camera removed | one frame, sent once |
| CAMERA OFFLINE / POWER FAILURE | camera destroyed, disabled or unpowered | one frame, sent once |
| BOOTING (sweep) | `boot-seconds` after placing or powering on | one frame |
| OFF | monitor switched off | one frame |

Night-vision cameras show in green phosphor, thermal cameras in the thermal ramp (the server boosts their
brightness and makes bodies glow), and `style: green|amber` monitor types tint everything.

## Modes and fallbacks

| Mode | What it costs | Trade-off |
|---|---|---|
| `live` | ray-cast every 1/`fps` s while watched | the real feed |
| `snapshot` | one frame every `snapshot-interval-seconds` | movement shows as stills; ~4–20× cheaper |
| `shader` | nothing after the first frame | a stand-in (grid plus camera name) animated by the shader; no real picture |
| `quad` | four cameras at half resolution each | about the cost of one `live` feed |

Unwatched monitors cost nothing. "Watched" means a non-spectator player within `watch-radius`, in front of the
screen, with line of sight (re-checked every 10 ticks).

## Performance

Measured costs are shown by `/cctv perf`: per-monitor render ms, frames, KB sent, watchers; plus the governor level,
chunk-cache hit rate and snapshot capture ms.

Estimates (64 px per block, 48-block view, one render thread):

| Item | Cost |
|---|---|
| Ray-cast, 1×1 monitor (64×64 rays) | ~5–20 ms per frame **off the main thread** → 10–40 ms of CPU per second at 2 fps |
| Ray-cast, 2×2 wall (128×128 rays) | ~4× that |
| Chunk snapshots (main thread) | ~0.1–0.5 ms per chunk on a cache miss, ~20–30 chunks per camera, capped at `ms-budget-per-tick` (2 ms) per tick across all monitors; cached for `chunk-cache-seconds` |
| Entities (main thread) | one `getNearbyEntities` per frame per camera |
| Watchers (main thread) | one block ray-trace per nearby player per monitor every 10 ticks |
| Bandwidth | ~16.4 KB per tile per frame **per watcher**. A 2×2 wall at 2 fps with 3 watchers ≈ 390 KB/s. This is the real limit; prefer fewer tiles, lower fps, or snapshot mode. |

The **governor** watches mspt every 2 s. Above `governor.high-mspt` it steps: (1) half the frame rate, (2) half the
resolution, (3) snapshot mode. Below `low-mspt` it steps back.

## Map files

Every monitor tile is one map (`<save>/data/minecraft/maps/<id>.dat`). The ids are kept for the monitor's life,
so restarts don't create new maps. When a monitor is removed, its map files are deleted at once. The ids are also
noted in monitors.yml (`released-maps`) and deleted again after each world save and at startup, in case the server
had the map in memory and saved it again.

Monitors removed by versions before 0.5.1 left their map files behind. `/cctv monitor cleanmaps` lists map files
that look exactly like CCTV screens (locked, no position tracking, no saved pixels, no banners) and that no monitor
uses. `/cctv monitor cleanmaps confirm` deletes them. Permission: `cctv.command.monitor.cleanmaps` (op).

## Verified, unverified, limitations

- CLIENT-VERIFIED (26.3 rig, 2026-11-14): a 2×2 wall, a quad monitor, a NO SIGNAL monitor and a green desk CRT
  bound to a night-vision camera all render. The sentinel is detected (the CRT layer appears on CCTV maps only), tiles
  line up and are not mirrored, and the colour-bar card is drawn by the shader. Measured: 17 ms per 2×2 frame and
  4–6 ms per 1×1 frame off the main thread, 4.8 mspt total, 96% chunk-cache hits.
- CLIENT-VERIFIED (0.4.0, 26.2 rig, 2026-11-14): CRT monitors look and animate as in 0.2.0; TVs: a 2×2 is one
  seamless picture in one frame, NO SIGNAL and POWER FAILURE are blue screens; players are skinned figures (a
  textures-property skin and armour colours checked); no wall bleeding through screens at a distance. The flat
  look itself was also checked on 26.3 (0.3.0).
- NOT yet verified on a client: the flat STATIC break-up, clicking a screen (and the quad quadrant pick), boot/static/off animations,
  thermal style, and behaviour across a chunk unload while watched.

- VERIFIED from the 26.3 client jar: maps in item frames are drawn with `RenderTypes.text` from one 128×128 texture
  each; the map palette (copied exactly from 26.3's `MapColor`); Paper sends item-frame maps every
  `maps.item-frame-cursor-update-interval` ticks (default 10), and `Player.sendMap` sends one immediately.
- Map ids are created once per tile and reused (stored in `monitors.yml`), so restarts create no new map files
  (26.3 keeps them in `world/data/minecraft/maps/<id>.dat`; verified: 7 tiles = 7 files, unchanged after a reboot).
  They stay there after a monitor is removed (a few KB each).
- Frame rate and bandwidth are capped by the map protocol: every frame resends whole 128×128 tiles. A PacketEvents
  backend that sends only changed rectangles could cut that a lot for static scenes. It's not included; ask if
  bandwidth becomes the problem.
- Transparent blocks (glass, water surfaces, leaves gaps) are approximations: glass is see-through, water and
  leaves are solid colour.
- Feeds never load chunks. A camera whose surroundings aren't loaded (nobody nearby) shows sky or black there.
- The frames are glow item frames, so the screens are full-bright. The CRT shader ignores world light on purpose.
