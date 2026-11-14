# CCTV for Paper 26.3 — Phase 1: architecture, verified formats, risks

Status: written 2026-11-14 as the Phase 1 plan; **implemented in Phases 2–7** (see the README). Kept as the design
record. Where the implementation differs, the README and the topic docs are current: for example `cctv:camera_dome`
became `camera_dome_lens/_housing`, and the HUD marker uses alpha 254 on every glyph pixel instead of corner key texels.

Every 26.3-specific claim is tagged:

- **VERIFIED (how)**: checked today against the real artifacts: the vanilla 26.3 release client jar
  (`version.json` build_time 2026-09-15, protocol 777), the Paper 26.3 server jar, the
  `paper-api-26.3.build.249-stable` jar, and the live PaperMC / GitHub APIs.
- **UNVERIFIED**: reasoned from code or prior versions but not exercised. Each one says how and when it gets
  checked. Most get checked on the real-client test rig (a vanilla 26.3 client launched offline against a local
  Paper server, with window screenshots). That rig already exists from the Firearms work.

---

## 0. Corrections to the brief (things that changed or turned out differently)

| Brief says | Actual state | Tag |
|---|---|---|
| Paper 26.3 is beta/experimental | **Stable.** Fill API: `26.3` support status `SUPPORTED`, 161 builds, latest **#249 STABLE (2026-11-11)**, Java minimum **25**. | VERIFIED (fill.papermc.io v3) |
| Override `rendertype_text.fsh/vsh` | That file no longer exists. In 26.3 it is **`assets/minecraft/shaders/core/text.vsh` / `text.fsh`**, one program shared by several pipelines through defines: `TEXT`, `TEXT_GRAYSCALE` (`IS_GRAYSCALE`), `TEXT_POLYGON_OFFSET`, `TEXT_SEE_THROUGH` (`IS_SEE_THROUGH`), `GUI_TEXT` (`IS_GUI`), plus OIT variants. | VERIFIED (jar listing + `RenderPipelines` bytecode) |
| Dispatch `/posteffect` as console | Paper exposes a real API: **`Player.postEffects()` → `io.papermc.paper.entity.PlayerPostEffects`** with `values() / add(Key) / remove(Key) / set(SequencedCollection<Key>) / clear() / update()`. That is the primary backend; console dispatch is fallback #2. | VERIFIED (javap on paper-api 249) |
| Post effects can't read plugin state | Correct. They do get the engine's **`Globals` UBO** (`GameTime`, `ScreenSize`, …): `PostPass` calls `RenderSystem.bindDefaultUniforms`, which binds `Projection`, `Fog`, `Globals`, `Lighting`. So time-animated grain/flicker/jitter works, and anything state-driven means swapping variants. | VERIFIED (bytecode of PostPass + RenderSystem) |
| Post effect also affects HUD | **No.** The GUI (title/action bar/boss bar) is drawn **after** post effects, so the HUD overlay is untouched by the full-screen shader. That's why the HUD needs its own effects in `core/text`, as the brief planned. | VERIFIED (GameRenderer order, 2026-11-04) |
| Hotbar scroll / left-right click as camera controls | **Not available** in the recommended view mode (spectator, §3). In spectator, scroll only moves the spectator menu or changes client-side fly speed, and no packet is sent. Clicks don't send swing/use. Movement keys **are** sent: `LocalPlayer.sendChanges()` sends `ServerboundPlayerInputPacket` on every input change whatever the camera entity is. That fires Paper's `PlayerInputEvent` (forward/back/left/right/jump/sneak/sprint). | VERIFIED (MouseHandler + LocalPlayer bytecode) |
| Creeper/spider/enderman post-effect trick | **Still present in 26.3.** `GameRenderer.checkEntityPostEffect` → `minecraft:creeper`, `minecraft:spider`, `minecraft:invert` (enderman), stored in a separate "spectated entity" slot next to the server-requested list. | VERIFIED (bytecode) |
| `map_color` removed | **Confirmed.** `items/filled_map.json` is a plain `minecraft:model` with no tint, and there is no `MapItemColor` component class in the server jar. Irrelevant for us: item-frame maps render the map **data texture**, not the item model. | VERIFIED (jar) |

---

## 1. Build coordinates (Gradle)

```kotlin
// settings.gradle.kts
rootProject.name = "cctv"

// build.gradle.kts
plugins { java }
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}
dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.249-stable")
    // PacketEvents: NOT a dependency in the core build (see §1.1)
}
```

- **Why this string:** Paper dropped `-R0.1-SNAPSHOT`. The 26.x scheme is `<mc>.build.<n>-<channel>`. Maven
  metadata lists `26.3.build.243-stable` … `26.3.build.249-stable` (and a stray `26.3-pre-2.build.0-alpha`).
  Pin an exact build so builds are reproducible. Moving to a newer stable build is a one-line change.
  **VERIFIED** (repo.papermc.io maven-metadata.xml; jar downloaded and inspected).
- `java 25` toolchain because Paper 26.3 requires Java ≥ 25. **VERIFIED** (Fill API `java.version.minimum: 25`).
- No paperweight-userdev / NMS: everything the design needs is in the Paper API (§1.1).
- `plugin.yml` `api-version`: your 26.x plugins use `'1.21'` and load fine on 26.3. Whether Paper accepts
  `'26.3'` there is **UNVERIFIED**. Phase 2 boot-tests `'26.3'` first and falls back to `'1.21'`.

### 1.1 Libraries

| Library | Use | Decision |
|---|---|---|
| Paper API 26.3.build.249-stable | Everything | required |
| PacketEvents | Would allow (a) **partial map patches** (the vanilla map packet carries one dirty rectangle; Bukkit `Player.sendMap` always sends the full 128×128) and (b) packet-only "virtual" item frames. | **Optional, off by default.** v**2.14.0** (2026-09-23) adds 26.3 support: its `ServerVersion` enum has `V_26_3(777)`, matching the client's protocol 777 (**VERIFIED**, GitHub API). Phase 5 measures bandwidth with plain `sendMap` first and only adds a PacketEvents backend if the numbers say it's needed. |

---

## 2. Architecture

The plugin source of truth is a **data file**; entities are disposable **views** of it.
That one rule takes care of persistence, chunk safety, and cleanup:

- Camera/monitor entities are spawned with `setPersistent(false)`, tagged in PDC (`cctv:camera=<id>`,
  `cctv:part=<role>`). On `ChunkLoadEvent`/`EntitiesLoadEvent` the registry spawns the parts for records in that
  chunk. On unload the entities just go away, because non-persistent entities aren't saved.
- So there are never orphaned or duplicated displays after a crash. A startup sweep removes any stray entity that
  still carries a `cctv:` tag (covers files from older builds).

```
fi.alavesa.cctv
├─ CctvPlugin                 bootstrap, service wiring, reload
├─ api/                       PUBLIC, stable
│   CctvApi (ServicesManager), CameraView (read-only), CameraType, CameraState,
│   ClearanceProvider, PowerProvider, ViewMode
│   event/ CameraCreateEvent, CameraRemoveEvent, CameraDamageEvent, CameraDisableEvent,
│          CameraEnableEvent, CameraRepairEvent, CameraViewStartEvent (cancellable),
│          CameraViewEndEvent (reason), CameraSwitchEvent, CameraAlertEvent, MonitorInteractEvent
├─ camera/                    Camera record, CameraRegistry, CameraStore (cameras.yml, async atomic save),
│                             CameraParts (spawn/despawn item_display + interaction), Mounting (wall/ceiling/floor
│                             → yaw/pitch/translation), PtzMotor (target angles, smoothing, limits)
├─ command/                   /cctv give|create|remove|list|rename|setgroup|tp|reload|perf|monitor ...
├─ view/                      ViewManager, ViewSession, AnchorService, BodyDouble, Controls, Transition,
│                             SessionGuard (crash-safe restore marker in player PDC)
├─ effect/                    PostEffectService (interface) + PaperPostEffects | CommandPostEffects |
│                             MobAnchorEffects | NoEffects; EffectStack (ordered list of our ids)
├─ pack/                      PackBuilder (zips /resourcepack from the jar), PackHost (external URL | same-port HTTP),
│                             PackStatus (per-player, per-pack-UUID)
├─ hud/                       HudComposer (title/subtitle components), GlyphMap, HudTicker
├─ monitor/                   Monitor, MonitorStore, MonitorParts (frames+displays+barriers),
│                             FeedScheduler (budget, watched-only), SnapshotCache (chunk snapshots),
│                             FeedRenderer (async raycaster), BlockPalette, MapSink (MapView + sendMap),
│                             QualityGovernor (TPS/MSPT scaling), PerfStats
├─ gui/                       BrowserMenu, AdminMenu, TerminalBlock, MenuFont
├─ alert/                     Zone, ZoneWatcher, AlertService
└─ config/                    CctvConfig (typed, reloadable), Messages
```

Threading: main thread does entity spawning, snapshot capture, and packet sends. One bounded
`ExecutorService` (`cctv-feed`, N = config, default 1) does raycasting. Hand-off is through immutable frame
jobs and preallocated `byte[]` ring buffers, so nothing is allocated per tick on the hot path.

---

## 3. Full-screen view: approach and why

### 3.1 Recommendation: spectator + `Player.setSpectatorTarget(anchor)`

| | **A. Spectator + setSpectatorTarget** (recommended) | B. Packet camera (`ClientboundSetCameraPacket`), player stays in survival |
|---|---|---|
| API | Paper API only | NMS or PacketEvents |
| Remote cameras | **Works anywhere.** While spectating, the server keeps the player's position on the camera, so chunks and entities around the camera stream normally. | Breaks outside the player's own view distance: the client has no chunks there and Paper can't send foreign chunks. |
| HUD clean-up | **Free and vanilla-correct.** Spectators have no hand, no hotbar, no hearts/food, and no crosshair unless looking at an entity. | Hotbar and crosshair stay visible. Hiding them needs **global** sprite overrides (`gui/sprites/hud/*`), which would break normal play. Not acceptable. |
| Exit | Native sneak-to-exit → `PlayerStopSpectatingEntityEvent` (cancellable, VERIFIED in API) | Custom |
| Controls | `PlayerInputEvent` only (WASD/jump/sneak/sprint) | Scroll, clicks, mouse-look (rotation is sent every tick while a passenger, VERIFIED in `sendChanges`) |
| Damage while viewing | Spectators can't be hurt, so a **body double** is needed (below) | Native |
| Side effects | Game mode change (tab list goes grey; other plugins may react; spectator number-key teleport menu) | Hand rendering when camera ≠ player: UNVERIFIED |

A matches how your Terminal plugin's CCTV app already works (spectator, ArmorStand eye, ArmorStand body), and it
handles every remote-camera case. B's only real advantage is mouse-look and scroll controls. Those are also only
reliable for cameras inside the viewer's own view distance. **I'm building A.** B stays possible later as a
`view.mode: packet` option for desk-local cameras.

### 3.2 Anchor entity

- Type per mode: `ITEM_DISPLAY` (default, no model, `view_range` 0 so it's never drawn) when post effects come from
  `PlayerPostEffects`; `CREEPER`/`SPIDER`/`ENDERMAN` only in the mob-trick fallback.
- Hidden from everyone else: `setVisibleByDefault(false)` + `viewer.showEntity(plugin, anchor)` (both VERIFIED in
  API). Mobs also get: `setInvisible`, `setSilent`, `setAI(false)`, `setInvulnerable`, `setCollidable(false)`,
  `setGravity(false)`, `setPersistent(false)`.
- Smooth PTZ: the anchor is rotated a little every tick (PtzMotor eases toward the target with a per-type max
  deg/s). For displays, `teleport_duration` 2–3 smooths the client-side interpolation. **UNVERIFIED** whether 26.3's
  camera follows a Display's interpolated rotation smoothly or steps per tick. Rig test in Phase 3. Fallback:
  marker ArmorStand anchor (living-entity 3-step lerp; that's what Terminal uses).

### 3.3 Body double

A `Mannequin` (`org.bukkit.entity.Mannequin`, VERIFIED in API: `setProfile(ResolvableProfile)`, `setImmovable`,
equipment, description) wearing the viewer's skin and armour copy, standing where they started viewing. It keeps a
plugin chunk ticket on its chunk while the session lasts.

- Damage to the body → `EntityDamageEvent` → cancel on the body → end the session with **SIGNAL LOST** → restore
  the player → re-apply the damage to the real player (configurable: `view.damage.forward: true`).
- Mannequin rendering and skin resolution on 26.3: **UNVERIFIED** (rig). Fallback: ArmorStand with the player head,
  as in Terminal.

### 3.4 Edge cases

| Case | Handling |
|---|---|
| Quit while viewing | `PlayerQuitEvent` restores synchronously (target null → game mode → teleport back → remove our post effects → remove body). |
| Crash / kill -9 | On session start a restore record (`cctv:restore` = origin location, game mode, effect ids) is written to the **player's PDC**, so it's saved with the player data. On join: if present → restore and remove our effect ids. |
| Death | Spectators can't die. Body death → session ends first (above). |
| Teleport | Cancel `PlayerTeleportEvent` cause `SPECTATE` (number-key spectator menu). Any other plugin teleport → end the session cleanly first, then let it through. |
| Camera destroyed / disabled mid-view | Registry event → session switches to the next camera in the network, or ends with SIGNAL LOST (config). |
| Anchor chunk unloads | Can't happen while spectated: the player's own ticket keeps it loaded. If the anchor vanishes, `PlayerStopSpectatingEntityEvent` fires and the session ends. |
| Other spectators | Spectators see each other as floating heads. Viewers are `hidePlayer`-ed from other spectators for the session. |
| Plugin disable / shutdown | `onDisable` restores every session (sync), removes every anchor/body, removes **only our** effect ids. |
| Other plugins | `CameraViewStartEvent` is cancellable. `CameraViewEndEvent` carries the reason. Vitals/Facility can veto or react. |

### 3.5 Final control scheme (spectator, PlayerInputEvent)

| Key | PTZ camera | Fixed camera |
|---|---|---|
| A / D (held) | pan left / right, smooth | previous / next camera |
| W / S (held) | tilt up / down | — |
| Ctrl (sprint) + A / D | previous / next camera | previous / next camera |
| Space (jump) | cycle zoom 1× → 2× → 4× | — |
| Shift (sneak) | exit (static transition) | exit |

The help line is shown as a subtitle in the HUD font (§5) for `hud.help-seconds` (default 4), then fades.
Key names are drawn with `keybind` components, so remapped keys show correctly (vanilla client feature).

---

## 4. Post effects

### 4.1 PostEffectService

```
interface PostEffectService {
  boolean available(Player p);          // pack loaded + backend usable
  void apply(Player p, List<Key> ours); // replaces only OUR ids, keeps foreign ones in place
  void removeOurs(Player p);            // removes every cctv:* id, never clear()
}
```

Backends, in order:

1. `PaperPostEffects`: `values()` → drop `cctv:*` → append ours → `set(list)`. Effects from other plugins (for
   example Firearms' `firearms:flash_*`) are kept.
2. `CommandPostEffects`: `posteffect remove <player> <id>` / `add` for each id.
3. `MobAnchorEffects`: anchor type gives creeper (green mono, the closest built-in match to night vision),
   spider, or enderman/invert. Tag: **trick VERIFIED in code**, not yet seen on a client.
4. `NoEffects`: HUD-only mode.

The server can't see whether an effect applied (the client keeps a `failedPostEffects` list it never reports,
VERIFIED). So full-effect mode requires `PlayerResourcePackStatusEvent` with **our pack UUID** at
`SUCCESSFULLY_LOADED` (the event carries `getID()`, VERIFIED). Otherwise the session starts in overlay-only mode
and the player gets a message.

### 4.2 Effect stacking and variants

`set()` takes an ordered list. The client keeps `requestedPostEffects` → `appliedPostEffects` as lists. So instead of a
type × zoom × signal explosion, effects **compose**:

```
[ cctv:zoom_2x ]? + [ cctv:standard | cctv:night_vision | cctv:thermal ] + [ cctv:signal_weak | cctv:signal_critical ]?
transient: cctv:static_burst (switching, ~6 ticks), cctv:signal_lost (exit/damage, ~20 ticks)
```

Zoom runs **first**, so scanlines and grain aren't magnified (digital zoom = crop + nearest upscale; honest for CCTV).
Assumption: the list order is the application order. **UNVERIFIED** (rig test with two effects whose order is
visible). If order isn't preserved, zoom becomes per-type variants (6 extra files).

Ids are flat names under `cctv` (`assets/cctv/post_effect/<name>.json`). Sub-folder ids are untested, so they're avoided.

### 4.3 Post-effect JSON format (26.3)

VERIFIED against `assets/minecraft/post_effect/creeper.json`/`invert.json`: `targets` (named internal targets),
`passes[]` with `vertex_shader: "minecraft:core/screenquad"`, `fragment_shader: "<ns>:post/<name>"`,
`inputs[{sampler_name, target}]`, `output`, and `uniforms` as **named UBO blocks**
(`"CctvConfig": [{name, type, value}]`), matching `layout(std140) uniform CctvConfig {...}` in the .fsh. Post
fragment shaders use `#version 330`, `GL_ARB_separate_shader_objects`, `layout(location=0) in vec2 texCoord`,
`SamplerInfo { OutSize; InSize }`, `layout(location=0) out vec4 fragColor` (VERIFIED, invert.fsh).

Effects to write (Phase 3): scanlines, animated grain (hash of `gl_FragCoord` + `GameTime`), barrel distortion,
vignette, chromatic aberration, H-sync jitter bursts (time-windowed), brightness flicker, desaturation grade,
green-phosphor (night vision), false-colour luminance ramp (thermal; honest: it's a luminance ramp, not heat),
plus corner brackets and a crosshair-free viewfinder drawn in screen space. These are done in the post shader
because it knows `OutSize`, so they're resolution-exact, unlike font glyphs.

---

## 5. HUD overlay

### 5.1 Channel: title + subtitle

- **Action bar: no.** On this server it's the shared Labra `ActionBars` hub, and decorations bleed into other lines.
- **Boss bar: no** for the main overlay. The bar sprite always draws, and hiding it means a **global** sprite override
  that would also hide AmmoBar and others.
- **Title: yes.** It's isolated, re-sendable with `Title.Times(0, 2s, 0)` so refreshes don't fade, and drawn at 4×
  scale. Glyphs use `height` much smaller than their texture rows (a bitmap provider scales texture rows to `height`),
  which gives 1 GUI-pixel detail at 4× scale. Subtitle (2×) carries the info lines and the help text.
- Limits (honest): positions are **relative to screen centre**, so exact screen-corner placement changes with
  aspect ratio and GUI scale. That's why the frame and corner brackets live in the post shader (§4.3). The title
  carries only centre-anchored content: REC dot, names, timestamp, signal bars, camera list, and the full-screen
  NO SIGNAL / OFFLINE / POWER FAILURE / ACCESS DENIED cards. Another plugin sending a title will overwrite ours
  until the next refresh (every 20 ticks).

### 5.2 Fonts (all in our namespace)

- `cctv:hud`: bitmap providers for the HUD glyph sheets, one provider per vertical row (different `ascent`),
  codepoints in the Private Use Area. **No collision is possible.** A font is chosen by the component's `font` key,
  so U+E000 in `cctv:hud` and U+E000 in someone else's font are different glyphs.
- `cctv:space`: our own `space` provider (advances are **floats**, VERIFIED: `SpaceProvider$Definition` uses
  `Codec.FLOAT`) for negative/positive offsets. We never touch `minecraft:font/include/space.json`.
- `cctv:gui`: the inventory backgrounds (§8).
- Format VERIFIED against `assets/minecraft/font/include/default.json` and `space.json`: `{"providers":[{"type":
  "bitmap","file":"ns:font/x.png","ascent":N,"height":N,"chars":[...]}]}` and `{"type":"space","advances":{...}}`.

### 5.3 The HUD marker in `core/text`, and why it can't collide

The HUD shader effects (flicker, scanline mask, glyph chromatic aberration, GameTime glitch offsets) run only when
**all three** gates pass:

1. **`#ifdef IS_GUI`**: world text (signs, nametags, text displays, maps) is compiled without `IS_GUI`, so it can
   never take this path, whatever its colour.
2. **Vertex colour family `#0CC7xx`**: R=0x0C, G=0xC7 is the marker; B is a bitfield (bit0 flicker, bit1 glitch,
   bit2 aberration, bit3 scanline, bit4 blink, …). Our components are sent with `shadow_color` 0 so no
   darkened-shadow vertices exist. When the marker matches, `vertexColor` becomes white × alpha, so the glyph
   texture's own colours (a red REC dot, a green phosphor) show unchanged.
3. **Texel alpha = 254/255 in a key texel.** Every CCTV glyph has key texels (alpha 254, fixed RGB) at its four
   corners. The fragment shader discards key texels and applies effects only when the glyph's texels have alpha 254.
   Vanilla font glyphs (`ascii.png`, unifont via the `unihex` provider) are only ever alpha 0 or 255.

So a chat message that happens to use `#0CC7xx` still renders vanilla: gate 3 fails and the shader restores its
original colour. The vertex-stage glitch offset also checks the key texel, using `texelFetch` from the vertex
shader at the glyph corner picked by `gl_VertexID % 4`.
**UNVERIFIED:** the glyph-quad vertex order on 26.3, and nearest/no-mip sampling of the GUI font atlas. Both are
rig-tested in Phase 4. If the vertex-stage key check proves unreliable, the vertex glitch falls back to colour-only
gating (worst case: a `#0CC7xx` chat line jitters, with no colour change).

---

## 6. Monitors and the live feed

### 6.1 What's physically possible

There's no video into a block on a vanilla client, and a shader can't sample another camera's view. The feed is
**server-rendered map pixels**:

- One map = **128×128** palette pixels on one block face. A 2×2 wall = 256×256, a 3×2 wall = 384×256.
- Maps are drawn by `MapRenderer` through **`RenderTypes.text(mapTexture)`**, the `TEXT` pipeline, i.e. our
  `core/text` program, and each map is its **own 128×128 `DynamicTexture`** (VERIFIED, MapRenderer +
  MapTextureManager bytecode). That's what makes the CRT shader possible.
- Update cadence (VERIFIED, Paper server jar): item-frame map data goes out from `ServerEntity.sendChanges` every
  `maps.item-frame-cursor-update-interval` ticks (Paper world config, default **10 → 2 fps**).
  `Player.sendMap(MapView)` (CraftPlayer → `CraftMapView.render` → full packet) sends immediately. So the
  FeedScheduler sends frames itself with `sendMap` at the configured fps, only to watchers. The `MapRenderer` we
  attach just copies the latest buffer when it's dirty, so the vanilla 10-tick path costs almost nothing.
- Map ids come from `Bukkit.createMap` once per tile and are stored in `monitors.yml` (reused forever, so no
  `map_N.dat` growth).

### 6.2 Feed renderer

Main thread, budgeted: capture `ChunkSnapshot`s for the frustum's chunks (cached per chunk, TTL plus invalidation on
block-change events in watched chunks), and an entity list (type, bounding box, no names). Async: one ray per output
pixel, voxel DDA through snapshots up to `max-distance` blocks. Colour = block map colour (`BlockData#getMapColor`,
matched once at startup to palette base ids) × shade from distance plus sky/block light. Entities are drawn as
flat-colour silhouettes by category (player/mob/item). Nametags are never drawn. Output is written into a preallocated
`byte[]` frame, then queued for the main thread to send.

Config knobs: `render-scale` (1 = 128 px per tile; 2 = 64 px upscaled, the default), `fps` per monitor (default
2, max 10), `max-distance`, `watch-radius`, `watch-requires-los`, `ms-budget-per-tick` (main-thread snapshot
work), `threads`, `governor` (MSPT thresholds → halve fps → drop render-scale → snapshot mode), `cache-ttl`.

Modes: `live` (above), `snapshot` (1 frame every 1–3 s), `shader-only` (server sends one static stand-in image
once; the shader animates it at zero ongoing cost).

### 6.3 CRT shader and the map sentinel

In `core/text`'s fragment stage, **non-GUI path only**:

1. `textureSize(Sampler0, 0) == ivec2(128)` (cheap early-out; glyph atlases and other textures fail this), **and**
2. Four corner texels (0,0), (127,0), (0,127), (127,127) hold four specific palette colours in a fixed order, read
   with `texelFetch` (raw texture, before the vertex colour/lightmap multiply, so exact).
3. Texel (1,0) is a **state code**: LIVE, STATIC, NO_SIGNAL (colour bars), OFFLINE, BOOT, OFF. Everything except
   LIVE is procedural in the shader. The server sends one cached map per state change and nothing more.
   Fixed text such as "NO SIGNAL" is baked into that cached map once, and the shader animates over it.

The sentinel texels are hidden by sampling their neighbours. The effects: scanlines, phosphor/pixel mask, curvature
(UV warp inside the quad), noise, flicker, vignette, edge glow, mono/green tint, all on GameTime.

**Why held/hung player maps are safe:** a real map would need those exact four palette colours at those exact four
corners in that order (the colours are picked from rarely produced base/shade pairs in Phase 5). Natural terrain
won't produce that. Deliberate map art copying it just looks like a CRT, which is harmless. CCTV maps never leave
their frames: invisible, `setFixed(true)`, invulnerable. The exact palette RGBs for the sentinel are extracted from
the 26.3 client's `MapColor` in Phase 5 (UNVERIFIED until then).

### 6.4 Monitor objects

Desk monitor (1×1), wall monitor (1×1), 2×2 and 3×2 walls. Each is an `item_display` model (bezel/stand), invisible
fixed item frames carrying the map tiles a fraction in front of the bezel, and barrier blocks (or one `interaction`
per tile) for clicks and collision. Placement items, a clean break (drop the placement item, remove the parts,
release the map ids back to a pool), cycle binding, and quad-split view (one 128 px tile split 2×2 → four 64 px feeds).
Clicking a tile opens the full-screen view of the camera on it.

---

## 7. Resource pack

### 7.1 pack.mcmeta

```json
{ "pack": { "description": "CCTV", "pack_format": 97, "min_format": 97, "max_format": 97 } }
```

26.3 = resource **97.1** (VERIFIED, the release client's `version.json`, not the pre-release number). This
min/max form loaded on a real 26.3 client in Firearms 0.30 (VERIFIED, 2026-11-06). `max_format` is pinned to
97 on purpose: the shaders depend on 26.3 layout rules, so a future client should mark the pack incompatible rather
than load broken shaders.

### 7.2 Vanilla files overridden: exactly two

| File | Why it can't be avoided |
|---|---|
| `assets/minecraft/shaders/core/text.vsh` | HUD marker detection and vertex glitch (§5.3); passes flags to the fragment stage. Text and maps have no namespaced pipeline we could target. |
| `assets/minecraft/shaders/core/text.fsh` | HUD glyph effects (§5.3) and the map CRT (§6.3) |

Each edit is a small block delimited by `// ===== CCTV BEGIN =====` / `// ===== CCTV END =====`: one
`#include <cctv:text_cctv.glsl>` line, one or two extra varyings at **`layout(location = 4/5)`** (vanilla uses
0–3), and one call just before `fragColor`/OIT handling. All the logic lives in **our** include file. Whether core
shaders can `#include` from a non-`minecraft` namespace is **UNVERIFIED** (glslang check plus rig in Phase 4). If
not, the logic is inlined inside the same delimited block.

Everything else (models, items, textures, fonts, post effects and their shaders, sounds, lang) is under
`assets/cctv/`. No vanilla font, sprite, model, or sound is overridden.

### 7.3 Unique ids

| Kind | Ids |
|---|---|
| Item model definitions (`assets/cctv/items/*.json`, used via `item_model` component) | `cctv:camera_dome`, `camera_bullet`, `camera_ptz_base`, `camera_ptz_head`, `camera_night_vision`, `camera_thermal`, `camera_broken`, `monitor_desk`, `monitor_wall`, `monitor_wall_2x2`, `monitor_wall_3x2`, `terminal`, `gui_icon_*` |
| Fonts | `cctv:hud`, `cctv:space`, `cctv:gui` |
| Post effects | `cctv:standard`, `night_vision`, `thermal`, `signal_weak`, `signal_critical`, `zoom_2x`, `zoom_4x`, `static_burst`, `signal_lost` |
| Sound events | `cctv:camera.motor`, `cctv:feed.switch`, `cctv:monitor.hum`, `cctv:power.off`, `cctv:signal.lost`, `cctv:alert` |
| Pack UUID | fixed `UUID.nameUUIDFromBytes("cctv:pack")`, sent with `addResourcePack(uuid, …)` / Adventure `sendResourcePacks` without replacing others (VERIFIED in API) |

`sounds.json` format VERIFIED against the 26.3 asset index (`minecraft/sounds.json`):
`{"event": {"sounds": [{"name": "ns:path", "volume", "attenuation_distance", ...}], "subtitle": "key"}}`.

### 7.4 Distribution

1. **Standalone:** the plugin builds the zip from its jar at startup, prints its sha1, and either serves it on the
   game port (the same-port HTTP sniffer proven in Firearms 0.23, which doesn't work behind Velocity/Bungee) or uses
   a configured external URL. It's sent as an additional pack, so your Lab combined pack stays.
2. **Merge:** add the CCTV pack folder to `SOURCES` in `Lab/tools/build-pack.sh` (none of the 11 family packs
   currently ship shaders or vanilla font overrides, VERIFIED by scanning them, so no conflict today). Plus a
   generic `tools/merge_pack.py` that copies `assets/cctv/` and splices the BEGIN/END blocks into a foreign
   `text.vsh/fsh` at anchor lines. If the anchors aren't found, it stops with a clear message and the merge guide
   takes over by hand.

### 7.5 Folder structure

```
CCTV/
├─ build.gradle.kts, settings.gradle.kts, gradle/wrapper/…
├─ src/main/java/fi/alavesa/cctv/…        (§2)
├─ src/main/resources/plugin.yml, config.yml, messages.yml
├─ resourcepack/                         (zipped into the jar by a Gradle task)
│  ├─ pack.mcmeta, pack.png
│  └─ assets/
│     ├─ minecraft/shaders/core/text.vsh, text.fsh       ← the only vanilla overrides
│     └─ cctv/
│        ├─ items/*.json        models/item/*.json, models/camera/*.json, models/monitor/*.json
│        ├─ textures/item/*, textures/camera/*, textures/monitor/*, textures/font/*, textures/gui/*
│        ├─ font/hud.json, space.json, gui.json
│        ├─ post_effect/*.json  shaders/post/*.fsh  shaders/include/cctv_*.glsl, text_cctv.glsl
│        ├─ sounds.json  sounds/*.ogg (you supply)  lang/en_us.json
├─ tools/merge_pack.py
└─ docs/PHASE1-ARCHITECTURE.md, MERGE.md, TESTING.md, TROUBLESHOOTING.md (Phase 7)
```

---

## 8. GUIs, audio, alerts (design only)

- Browser: a 6-row chest menu. The title is a `cctv:gui` glyph with negative space that draws a textured background
  (a standard technique; the font is our own, so no override). Pages by group, status icons via `item_model`
  (online/offline/damaged/unpowered), a search filter typed through a Paper **Dialog** text input
  (`io.papermc.paper.dialog.Dialog` exists in the API, VERIFIED; behaviour UNVERIFIED, with anvil-rename input as
  fallback), and click-to-view.
- Security terminal: its own placeable block (same parts pattern as monitors). Access is `ClearanceProvider
  .hasClearance(player, level)`. The default implementation is permission-based (`cctv.clearance.<n>`), and you can
  register an IdCards/LuckPerms-meta one.
- Admin GUI: pick camera → rename (dialog), group, type, FOV, pan/tilt limits, clearance, power, health, move.
- Sounds: you supply OGG Vorbis, **mono** (mono is needed for positional attenuation), 44.1/48 kHz. motor whir
  loop ~1.0 s seamless; feed switch static ~0.4 s; monitor hum loop ~2–4 s seamless; power-off click ~0.2 s;
  signal-lost tone ~0.8 s; alert ~1.5 s.
- Alerts: axis-aligned zones per network; a camera that sees a non-exempt player in a zone (FOV cone + one
  ray, at most every 10 ticks per camera) sets ALERT. That flips the REC glyph colour flag, fires `CameraAlertEvent`,
  and messages that network's viewers. All checks are hooks, nothing SCP-specific is hard-coded.

---

## 9. Performance budget (estimates, measured in Phase 5)

| Item | Estimate |
|---|---|
| Idle camera | ~0: parts are static displays. PTZ motor only while moving (1 teleport/tick). |
| Viewer | HUD title refresh 1×/s and controls on input; ≈ 0.02 ms/tick per viewer. |
| Live tile (64 px default) | 4 096 rays × ~25 DDA steps ≈ 0.1 M lookups/frame; at 2 fps ≈ **1–3 ms CPU/s** off-thread. 128 px is about 4× that. |
| Main-thread snapshot capture | ~0.1–0.5 ms per chunk on a cache miss; capped by `ms-budget-per-tick` (default 1.5 ms). |
| Bandwidth | `sendMap` = full 16 KB per tile per frame per watcher → 2×2 at 2 fps ≈ **128 KB/s per watcher**. This is the number most likely to force the optional PacketEvents dirty-rect backend. |

`/cctv perf` shows per-monitor fps, render ms (avg/p95), snapshot ms, cache hit %, bytes/s, watchers, and governor state.

---

## 10. Risks and fallbacks

| # | Risk | Likelihood | Fallback |
|---|---|---|---|
| R1 | Client won't run `core/text` override (GLSL error rejects pack/shader) | medium | glslang validation with the 26.3 defines before every release (trick from 2026-10-18); rig test; the plugin works without shaders (plain feed, plain HUD) |
| R2 | Another pack also overrides `core/text` (higher priority wins wholesale) | medium | BEGIN/END blocks + `merge_pack.py` + manual guide |
| R3 | Non-`minecraft` namespace `#include` in core shaders not allowed | medium | inline the logic in the block |
| R4 | Post-effect list order ≠ application order | low | per-type zoom variants |
| R5 | Display-entity anchor rotation steps instead of interpolating | medium | ArmorStand marker anchor |
| R6 | Mannequin body double renders or behaves oddly | low-med | ArmorStand + head (Terminal style) |
| R7 | Glyph vertex order / atlas sampling breaks key-texel gating | medium | colour-only gating for vertex glitch (cosmetic risk only) |
| R8 | Feed bandwidth too high | high at 128 px / 10 fps | defaults 64 px / 2 fps; snapshot mode; PacketEvents dirty-rect backend |
| R9 | Item-frame map interval (10 t) and our `sendMap` fight (old frame flashes) | low | renderer returns the same latest buffer; or raise the interval only if it shows up |
| R10 | OptiFine / Iris with a shaderpack / some Sodium setups ignore or replace core and post shaders | certain for those clients | documented; plugin keeps working with plain visuals |
| R11 | Spectator game mode clashes with other plugins (Facility, Vitals, Terminal's own CCTV) | medium | cancellable start event; documented; Terminal can delegate its CCTV app to this plugin (your call, see below) |
| R12 | Player titles from other plugins overwrite the HUD | medium | 1 s refresh; HUD is cosmetic; boss-bar line as an optional secondary channel |
| R13 | Mob-trick fallback only gives three fixed looks | certain | documented as last-resort fallback |

---

## 11. Phase 1 limitations / test list

- Nothing in this phase runs in game. The rig tests queued for Phases 3–5: post-effect stacking order, display-anchor
  smoothness, Mannequin body, spectator `PlayerInputEvent` end-to-end, `core/text` compile and gating, cross-namespace
  `#include`, sentinel palette RGBs, glyph vertex order.
- Needs **your eyes** in the end: the look of every effect, HUD placement at your GUI scale, and monitor readability.
- Open decision for you: the existing **Terminal** plugin already has a CCTV app (spectator + ArmorStand eye). Either
  (a) the two run side by side (different namespaces, no conflict), or (b) Terminal's CCTV app opens this plugin's
  browser when it's installed and its cameras are imported once via `/cctv import terminal`. Default if you don't
  choose: (a).
