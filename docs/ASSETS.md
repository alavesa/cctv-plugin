# CCTV resource pack assets

Everything lives under `resourcepack/assets/cctv/` (namespace `cctv`). The plugin zips `resourcepack/` at startup.
To try changes without rebuilding the plugin, copy the folder to `plugins/CCTV/<folder>`, set `pack.source-folder`,
and run `/cctv reload`.

## Vanilla files overridden

`assets/minecraft/shaders/core/text.vsh/fsh` (HUD marker, monitor CRT) and `assets/minecraft/post_effect/creeper.json`,
`spider.json`, `invert.json` (the camera looks on 26.2). Details and merge help: [PACK.md](PACK.md).

Shaders live in `resourcepack/cctv_26_3/` (26.3 syntax). After editing them, run `python3 tools/port_262.py` to
regenerate the 26.2 copies in the pack base.

## Your own camera models from Blockbench (.bbmodel)

Put the file in `plugins/CCTV/models/` and point a camera type at it:

```yaml
types:
  my_ptz:
    model: {bbmodel: "my_ptz.bbmodel", placer: "cctv:camera_ptz_item"}
    ptz: true
    ...
```

`/cctv reload` converts it (the same converter as SuperbVehicles: one item model per moving part, exact rotations,
no 22.5° snapping), adds it to the resource pack, re-sends the pack and respawns those cameras. The log lists parts,
animations and any warnings. `plugins/CCTV/models/example_ptz.bbmodel` (type `example_bb`) is a working template.

**Rules**
- Any Blockbench format (Generic / Java / Bedrock). Textures embedded or next to the file.
- **The lens point is the origin (0, 0, 0) and the lens looks north (−Z).** That's where the view is taken from.
- Build it as a **floor** mount (bracket below the lens). Wall and ceiling cameras are the same model, rotated so the
  bracket's bottom faces the wall or ceiling.
- A group named **`head`** (or `lens`) and everything inside it turns with pan and tilt. Put its pivot at 0, 0, 0
  (the log warns otherwise). Everything outside it stays fixed to the bracket.
- `hitbox_*` groups are not drawn. Groups hidden in Blockbench (eye icon) are left out.

**Animations** (played by name, looping clips loop):

| Clip | Plays |
|---|---|
| `idle` | always |
| `move` | while a PTZ head is turning (falls back to `idle`) |
| `alert` | while the camera is in alert (falls back to `idle`) |
| `destroyed` | while the camera is destroyed (otherwise the model just stays as it is) |

Rotation, position and scale keyframes with linear, step, Catmull-Rom or Bézier interpolation are supported.
Animations play only while a player is within 48 blocks (2 updates per second of network traffic per camera).
The camera's own model is hidden from whoever is viewing through it.

## Camera models (built-in placeholders)

Placeholder models and textures ship, so cameras look like cameras out of the box. Replace any of them, keeping
the file names.

| Item model id (`items/*.json`) | Model | Role |
|---|---|---|
| `cctv:camera_bullet` | `models/item/camera_bullet.json` | bullet head (lens) |
| `cctv:camera_bullet_mount` | `models/item/camera_bullet_mount.json` | bullet/NV/thermal bracket |
| `cctv:camera_night_vision` | `models/item/camera_night_vision.json` | NV head (IR ring) |
| `cctv:camera_thermal` | `models/item/camera_thermal.json` | thermal head |
| `cctv:camera_dome_housing` | `models/item/camera_dome_housing.json` | dome bracket (fixed) |
| `cctv:camera_dome_lens` | `models/item/camera_dome_lens.json` | dome ball (turns) |
| `cctv:camera_dome_item` | `models/item/camera_dome_item.json` | dome placement item (housing + ball) |
| `cctv:camera_ptz_base` | `models/item/camera_ptz_base.json` | PTZ base + yoke |
| `cctv:camera_ptz_head` | `models/item/camera_ptz_head.json` | PTZ head |
| `cctv:camera_ptz_item` | `models/item/camera_ptz_item.json` | PTZ placement item |
| `cctv:camera_broken` | `models/item/camera_broken.json` | any head while DESTROYED |

**Model rules** (Blockbench "Java Block/Item" model, 16×16×16 units):

- The **pivot is the model centre (8, 8, 8)**. That's where the lens sits, and the view is taken from there.
- **Heads**: the lens points to **+Z** (Blockbench's *south* face). The whole head turns around the centre for
  pan and tilt. The view sits inside the head, but that camera's own model is hidden from its viewers, so the
  housing never blocks the picture.
- **Brackets**: author them as a **floor** mount, attached at the bottom (y ≈ 3.2, which is 0.3 blocks below the
  centre and matches `placement.offset`), front toward +Z. The plugin turns them for wall and ceiling mounts.
- Keep heads inside roughly 4–12 units, so a camera doesn't poke into the block it is mounted on.
- `display` transforms only matter for the placement item (GUI, hand, ground). The camera entities use
  `item_display` with transform NONE.
- The `item_display` renderer turns every model 180° around Y. The plugin cancels that, so author exactly as
  above. (Seen on a real 26.3 client, 2026-11-14.)

## Monitor models

- `cctv:tv_tile_<0..15>`: flat-TV frame per wall tile, only on the screen's outer edges. The number is a mask:
  1 top, 2 bottom, 4 left, 8 right, as seen from the front (15 = a 1×1 TV; a 2×2 wall uses 6, 10, 5, 9). Front is
  +Z, the viewer's right is +X. A type uses them with `model: {bezel: "cctv:tv_tile", edges: true}`; your own set
  must provide all 16 names.
- `cctv:tv_desk` (panel on a stand), `cctv:tv_wall_item` (placement item), `cctv:monitor_tile` / `monitor_desk` /
  `monitor_wall_item` (CRT look). Generated by `tools/gen_models.py`; textures `tv_bezel`, `tv_back`, `tv_led`.

## Textures

All placeholders are 16×16 RGBA PNGs from `tools/gen_placeholder_textures.py`. The UVs are auto-mapped
(no `uv` on a face = the face's own position on the texture), except lens faces, which map the full texture.

| Texture | Used for |
|---|---|
| `textures/item/camera_body.png` | light housing |
| `textures/item/camera_dark.png` | dark housing, brackets |
| `textures/item/camera_lens.png` | lens (south face of heads) |
| `textures/item/camera_lens_ir.png` | night-vision lens with IR ring |
| `textures/item/camera_lens_thermal.png` | thermal lens |
| `textures/item/camera_broken.png` | broken camera |
| `pack.png` (pack root, 64×64) | pack icon |

Higher resolutions work (32×32, 64×64). Keep them square.

## Sounds (you supply these)

`sounds.json` already defines the events. Put the files at these paths. Until they exist the client logs
"File … does not exist" and plays nothing (harmless).

| Sound event | File | Length | Notes |
|---|---|---|---|
| `cctv:camera.motor` | `sounds/camera/motor.ogg` | ~0.5 s | servo whir; restarted every `ptz.motor-sound-interval` ticks while a PTZ turns (default 10 = 0.5 s) |
| `cctv:feed.switch` | `sounds/feed/switch.ogg` | ~0.4 s | static burst; played to the viewer on start and switch |
| `cctv:monitor.hum` | `sounds/monitor/hum.ogg` | 2–4 s, seamless loop | used by monitors (Phase 5) |
| `cctv:power.off` | `sounds/power/off.ogg` | ~0.2 s | CRT click/thunk |
| `cctv:signal.lost` | `sounds/signal/lost.ogg` | ~0.8 s | tone or static drop-out |
| `cctv:alert` | `sounds/alert/alert.ogg` | ~1.5 s | security alert (Phase 6) |

Format: **Ogg Vorbis, mono** (stereo files are not positional), 44.1 or 48 kHz, peak around −3 dBFS.

## Post effects

| Id | Shader | What |
|---|---|---|
| `cctv:standard` | `cctv_look` Mode 0 | desaturated, cold, scanlines, grain, flicker, jitter bursts, vignette, barrel, brackets |
| `cctv:night_vision` | `cctv_look` Mode 1 | green phosphor, amplified (NV_GAIN) |
| `cctv:thermal` | `cctv_look` Mode 2 | false-colour **brightness** ramp (Minecraft has no temperature data) |
| `cctv:zoom_2x`, `zoom_3x`, `zoom_4x` | `cctv_zoom` | digital zoom, runs before the look |
| `cctv:signal_weak`, `signal_critical` | `cctv_signal` Mode 0 | tearing, snow, drop-outs, from camera health |
| `cctv:static_burst` | `cctv_signal` Mode 1 | feed start, switch, exit |
| `cctv:signal_lost` | `cctv_signal` Mode 2 | body hurt, or camera died with nowhere to switch |

Tune everything with the commented constants at the top of `shaders/post/cctv_*.fsh`. A post effect can't read
plugin state, so the plugin swaps between these files. A new variant is a new JSON with different
`CctvLook`/`SignalConfig` values. Another zoom level (say 6×) needs a `zoom_6x.json`.
