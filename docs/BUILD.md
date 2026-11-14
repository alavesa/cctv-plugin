# Building and installing

## Requirements

| What | Version | Why |
|---|---|---|
| Server | **Paper 26.2 or 26.3** (tested: 26.2 build 135, 26.3 build 249) | compiled against the 26.2 API; 26.3-only `Player.postEffects()` is used through reflection when present |
| Java (server) | 25 or newer | Paper 26.3 minimum |
| JDK (building) | 25 or newer | the build compiles with `--release 25` |
| Clients | vanilla Java 26.2 or 26.3 + the CCTV resource pack | no mods |
| FancyNpcs (optional) | 2.12.x (tested 2.12.1+383) | player-model body double while viewing; without it a Mannequin is used |
| Python 3 (optional) | any 3.10+ | the generator, merge and validation scripts in `tools/` |

No other plugins are required. PacketEvents is not used.

## Build

```
./gradlew build
```

The wrapper downloads Gradle 9.9.1 the first time. The result is `build/libs/cctv-<version>.jar`, which contains the
resource pack sources (`resourcepack/`). The plugin zips those at startup.

Dependency: `io.papermc.paper:paper-api:26.2.build.135-stable` (compile-only, the oldest supported version) from
`https://repo.papermc.io/repository/maven-public/`. Paper's 26.x versions are `<mc>.build.<n>-<channel>`; there is
no `-R0.1-SNAPSHOT` any more. To build against a newer Paper build, change the build number in `build.gradle.kts`.

## Install / update

1. Stop the server.
2. Put `cctv-<version>.jar` in `plugins/`. **When updating, delete the old CCTV jar first.** Paper loads the first jar
   with the same plugin name it finds, so a leftover old jar silently wins and "the update did nothing". After
   starting, check the log line `Enabling CCTV v<version>`.
3. Start. The first start writes `plugins/CCTV/config.yml` and builds `plugins/CCTV/cctv-pack.zip` (the log prints
   its sha1).
4. Choose how players get the pack (`pack.delivery` in config.yml, details in [PACK.md](PACK.md)):
   - `http` (default): built-in web server on port **8164**. Open that TCP port. Behind a proxy (Velocity/Bungee),
     set `pack.http.public-host` to the address players can reach.
   - `url`: upload `plugins/CCTV/cctv-pack.zip` somewhere and set `pack.url`.
   - `none`: you merged the pack into your own server pack.
5. `/cctv reload` re-reads config.yml, rebuilds the pack and re-sends it if it changed.

Data files in `plugins/CCTV/` (all YAML, written atomically): `cameras.yml`, `monitors.yml`, `terminals.yml`,
`zones.yml`. Monitor map images live in the world (`world/data/minecraft/maps/<id>.dat`), one per screen tile,
reused forever.

## Regenerating assets

| Script | Output |
|---|---|
| `python3 tools/gen_placeholder_textures.py` | camera, monitor and terminal textures, GUI icons, GUI background, pack.png |
| `python3 tools/gen_models.py` | every item model + item definition |
| `python3 tools/gen_hud_font.py` | HUD font sheet `textures/font/osd.png`, `font/hud_*.json`, `font/space.json` |
| `python3 tools/port_262.py` | regenerates the 26.2 shaders (pack base) from the 26.3 sources in `resourcepack/cctv_26_3/`, plus pack.mcmeta and the mob-look overrides. **Run it after every shader edit.** |
| `python3 tools/validate_shaders.py <glslang> <vanilla assets>,<pack assets> <files…> [-DIS_GUI]` | compiles shaders like the client (inlines `#include` / `#moj_import`) |

`validate_shaders.py` needs the glslang binary (KhronosGroup/glslang releases, `macos-universal`/`linux`) and the
vanilla shader includes. Extract `assets/minecraft/shaders/` from the 26.3 client jar (`.minecraft/versions/26.3/26.3.jar`)
and pass that folder's parent `assets` directory.

Replace placeholders freely, keeping file names. Asset rules (model axes, sizes, sound formats) are in
[ASSETS.md](ASSETS.md).

## Source layout

```
src/main/java/fi/alavesa/cctv/
  api/          public API: CctvApi, CameraView, CameraType, ClearanceProvider, events
  camera/       camera records, entities, persistence, PTZ motor, placement
  view/         full-screen view sessions, anchor, body double, controls, plain HUD
  effect/       PostEffectService + backends (paper, command, mob, none)
  hud/          CCTV glyph HUD (HudText layout, CctvHud)
  monitor/      monitors, feed renderer, snapshot cache, map tiles, sentinel
  gui/          browser, editor, place menu, dialogs
  terminal/     security terminals
  alert/        zones, sightings
  pack/         pack builder, HTTP host, per-player pack status
  command/      /cctv
resourcepack/   pack sources (assets/cctv/**, plus assets/minecraft/shaders/core/text.vsh/fsh)
tools/          generators, merge script, shader validator
docs/           this documentation
```
