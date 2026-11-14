# The CCTV resource pack: standalone or merged

## What is in it

| Path | Contents |
|---|---|
| `assets/cctv/items`, `models/item`, `textures/item` | camera, monitor, terminal models and GUI icons |
| `assets/cctv/post_effect`, `shaders/post` | full-screen camera look, zoom, signal effects |
| `assets/cctv/font`, `textures/font`, `textures/gui` | HUD fonts (`hud_*`, `hud_big_*`, `space`), GUI background (`gui`) |
| `assets/cctv/shaders/include` | the CCTV text-shader code (HUD effects, monitor CRT) |
| `assets/cctv/sounds.json`, `lang/en_us.json` | sound events and subtitles (you add the `.ogg` files) |
| `assets/minecraft/shaders/core/text.vsh`, `text.fsh` | **the only vanilla files overridden** |

Every id is in the `cctv` namespace: item models `cctv:camera_*`, `cctv:monitor_*`, `cctv:terminal`,
`cctv:gui_cam_*`; fonts `cctv:hud_0..13`, `cctv:hud_big_0..5`, `cctv:space`, `cctv:gui`; post effects
`cctv:standard`, `night_vision`, `thermal`, `zoom_2x/3x/4x`, `signal_weak`, `signal_critical`, `static_burst`,
`signal_lost`; sounds `cctv:camera.motor`, `feed.switch`, `monitor.hum`, `power.off`, `signal.lost`, `alert`. None
of them can collide with another pack's assets.

`pack.mcmeta` declares format **97** (Minecraft 26.3) as both min and max on purpose: the shaders use 26.3's GLSL
layout, so a future client should mark the pack incompatible rather than load broken shaders. Raise `max_format`
only after testing on the new version.

## Option 1: standalone (recommended)

The plugin sends the CCTV pack as an **additional** server resource pack (Paper multi-pack; it never replaces your
server pack), under a fixed UUID so it can track whether each client loaded it.

```yaml
pack:
  delivery: http        # or: url
  http:
    port: 8164
    public-host: ""     # empty = the address each player typed; set it behind proxies / NAT
  url: ""               # delivery: url -> where you uploaded plugins/CCTV/cctv-pack.zip
  required: false
```

Pack priority: Minecraft applies server packs in the order they arrive. CCTV only conflicts with packs that also
override `core/text.vsh`/`text.fsh` (see below). Check `/cctv pack` to see each player's status (`SUCCESSFULLY_LOADED`
or not).

## Option 2: merged into another pack

Use this when you ship one combined pack (for example the Lab `scp_and_chemistry.zip`). Set
`pack.delivery: none` in config.yml. The plugin then assumes the pack is present. To require the client to report a
successful load of *some* pack first, set `pack.trust-any-pack: true`.

### With the merge script

```
python3 tools/merge_pack.py --cctv resourcepack --into path/to/your-pack --out merged.zip
# or from the plugin's own build output:
python3 tools/merge_pack.py --cctv plugins/CCTV/cctv-pack.zip --into your-pack.zip --out merged.zip
```

It copies `assets/cctv/**`, keeps your `pack.mcmeta`/`pack.png` (warns if your pack doesn't declare 97;
`--set-format` fixes that), and handles the two text shaders:

| Your pack has `core/text.*` | Result |
|---|---|
| no | CCTV's files are used |
| yes, already merged earlier | the CCTV blocks inside are replaced with the current ones (safe to re-run after updates) |
| yes, your own version | the CCTV blocks are spliced into **your** file: the include after your last `#include`, the call after your `gl_Position = …` line (vertex) and after your `vec4 color = … texColor …` line (fragment) |
| yes, but those lines can't be found | it stops, writes nothing, and tells you which file to merge by hand |

Tested cases: a pack with no shaders; a pack with a modified vanilla `text.fsh` (the result compiles in glslang);
re-merging a merged pack (byte-identical); a pack with an unrecognisable shader (clean refusal).

### With the Lab build script

`Lab/tools/build-pack.sh` merges a list of source folders (the first source wins for `pack.mcmeta` and other duplicate
files). None of the 11 current sources ship shaders, so adding CCTV causes no conflict:

```python
SOURCES = [
    home + "/Lab/resource-pack",
    ...
    home + "/CCTV/resourcepack",      # add this line
]
```

Then rebuild, update `resource-pack-sha1` in server.properties as usual, and set `pack.delivery: none`.

**Check the combined pack's format.** Lab's `pack.mcmeta` declares `pack_format 46` with
`supported_formats 46..99`. Firearms testing on a real 26.3 client found that formats below 65 are rejected by 26.x
clients. If players see the combined pack as incompatible, change its pack.mcmeta to
`"pack_format": 97, "min_format": 97, "max_format": 97` (or a range that starts at 65 or higher).

### By hand: merging `core/text.vsh` / `text.fsh` with another pack's version

Resource packs replace whole files. If two packs ship `assets/minecraft/shaders/core/text.fsh`, only the
higher-priority one is used. To keep both:

1. Start from **their** file. Copy `assets/cctv/` from the CCTV pack next to it (the include files live there).
2. `text.vsh`: after their `#include` lines add
   ```glsl
   // ===== CCTV BEGIN =====
   #include <cctv:cctv_text_vertex.glsl>
   // ===== CCTV END =====
   ```
   and directly after the statement that assigns `gl_Position`:
   ```glsl
       // ===== CCTV BEGIN =====
       gl_Position = ProjMat * ModelViewMat * vec4(Position + cctv_gui_offset(Position, Color), 1.0);
       // ===== CCTV END =====
   ```
   (If their shader moves vertices, add `cctv_gui_offset(Position, Color)` to the position they use instead.)
3. `text.fsh`: after their `#include` lines
   ```glsl
   // ===== CCTV BEGIN =====
   #include <cctv:cctv_text_fragment.glsl>
   // ===== CCTV END =====
   ```
   and right after the line that computes the colour (before `if (color.a < 0.1) discard;`):
   ```glsl
       // ===== CCTV BEGIN =====
       color = cctv_text_fragment(color, texColor, vertexColor, Sampler0, texCoord0, ColorModulator);
       // ===== CCTV END =====
   ```
   Use their variable names if they differ. Lines of theirs that change `color` afterwards also apply to CCTV text
   and screens. Usually harmless; move the CCTV block below them if not.
4. The includes are guarded and only add `cctv_`/`CCTV_` names; no varyings or `layout(location)` slots are added,
   so nothing collides with their declarations.
5. Validate (`tools/validate_shaders.py … -DIS_GUI` and without), then load the pack in game. The client log reports
   shader compile errors.

What each CCTV block does, and why its markers can't affect normal text or maps, is in [HUD.md](HUD.md) and
[MONITORS.md](MONITORS.md).

## Packs that break CCTV visuals

- **OptiFine** and **Iris/Oculus with a shaderpack** replace or skip vanilla core and post shaders: no CCTV look, HUD
  effects or CRT. Models, the HUD layout and the feed itself still work. Sodium on its own does not touch post
  effects or text.
- Any pack with its own `core/text` shader that is higher priority than CCTV and not merged as above: HUD effects and
  the CRT layer disappear. Everything else keeps working.
