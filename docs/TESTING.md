# In-game test checklist

Legend: ✅ verified on a real 26.3 client (test rig, 2026-11-14) · 🖥 verified on a headless server only ·
☐ needs a person (clicking, keys, feel).

Use two accounts where marked (one to view, one to walk around). Ops pass every permission check; also test with a
normal player for anything permission- or clearance-related.

## 0. Install

- ✅ `Enabling CCTV v…` in the log, no exceptions. 🖥 `api-version: 26.3` accepted.
- ☐ Client gets the pack prompt (delivery http). `/cctv pack` shows `SUCCESSFULLY_LOADED` for that player.
- ☐ Declining the pack: cameras still work; the feed has no effects and the player is told why.

## 1. Cameras

- ✅ Models face the right way (lens toward the view direction) on floor mounts.
- ☐ Wall and ceiling mounts: bracket against the surface, lens pointing out.
- ☐ Punch: sparks, health drops; at 0 → broken model, smoke. Right-click with an iron ingot repairs.
- ☐ Sneak-punch with `cctv.pickup` takes it down (item back outside creative).
- ✅ `/cctv aim` turns a PTZ head smoothly. 🖥 limits clamp.
- 🖥 Restart: cameras come back with angles, health and power; no duplicate entities.
- 🖥 Chunk unload/reload: parts despawn and respawn.
- ☐ Arrows and explosions damage cameras.

## 2. Full-screen view

- ✅ `/cctv view <id>`: standard, night-vision and thermal looks; no hand, hotbar or hearts; corner brackets.
- ✅ The view is not blocked by the camera's own housing.
- ✅ Switching cameras (by command) works, with a static burst.
- ✅ Damaged-signal effects at < 50% and < 25% health.
- ✅ A Mannequin with your skin stands where you were. Hitting it → SIGNAL LOST → you're back → you take the damage.
- ✅ The mob-shader fallback (`effects.backend: mob`): creeper green, enderman inverted.
- ☐ **Keys**: A/D pan and W/S tilt (PTZ), Ctrl+A/D (or A/D on fixed cameras) switch, Space zoom 2×/4×, Shift exits.
- ☐ Panning looks smooth (not stepping).
- ☐ Number keys (spectator menu) do nothing; clicking another entity doesn't jump into it.
- ☐ Quit while viewing → rejoin: back where you were, original game mode.
- ☐ Kill the server process while viewing (not `stop`) → rejoin: restored by the restore record.
- ☐ Another plugin teleports you or changes your game mode mid-view: the session ends cleanly.
- ☐ Destroy or unpower the camera you're watching: you switch to the next working camera, or get SIGNAL LOST.

## 3. HUD

- ✅ REC dot, camera id/name, date/time, camera list with pointer, network, signal bars, help line.
- ✅ Chat, name tags and signs look exactly vanilla with the pack loaded.
- ✅ NO SIGNAL card; refusal cards (POWER FAILURE / CAMERA OFFLINE / ACCESS DENIED) with their chat line.
- ☐ HUD layout at **your** GUI scale and window size (tune `hud.half-width`).
- ☐ Type a chat message in colour `#0CC7FF` (e.g. `<#0CC7FF>test` with a MiniMessage chat plugin): it must look normal.

## 4. Monitors

- ✅ 2×2 wall shows a live picture, correct orientation, CRT layer; quad split; NO SIGNAL colour bars; desk CRT with
  green phosphor for a night-vision camera.
- 🖥 Restart: monitors come back bound; map files are reused (no new map ids).
- ☐ Right-click a screen → that camera full-screen; in quad mode the clicked quadrant's camera.
- ☐ `cycle` with a group: static burst at each switch.
- ☐ Power a monitor off/on: OFF screen, boot sweep, then the feed.
- ☐ A player beyond `watch-radius` sees STANDBY static; walking closer starts the feed.
- ☐ `/cctv perf` with 2–3 watchers on a 2×2: note render ms, KB sent, mspt.
- ☐ Hold a normal map in your hand and hang one in a frame: both look vanilla.

## 5. GUIs, terminal, alerts

- ✅ `/cctv browse` and `/cctv admin` open with the CCTV background; status icons per camera.
- ✅ Alerts: walking into a zone in view of its network's cameras → chat alert, red alert icons in the browser.
- ☐ Browser: tabs, search dialog, filter, pages, click to view; ACCESS DENIED for a player without clearance.
- ☐ Editor: every button (rename/network dialogs, type, power, enabled, repair, FOV, clearance, facing, limits,
  teleport, view, shift-click remove).
- ☐ Place menu hands out every placement item.
- ☐ Terminal: place, use with and without clearance, lock to a network, sneak-left-click removes.
- ☐ Alert HUD: while viewing a camera of that network, the blinking `! ALERT` line and the `!` in the list.
- ☐ A non-op player triggers alerts; giving them `cctv.alert.exempt` stops it.

## 6. Coexistence

- ☐ With your other server packs loaded at the same time, their models, fonts and GUIs still work.
- ☐ A merged pack (`tools/merge_pack.py`) behaves like the standalone one.
- ☐ Firearms flashbang (its own post effect) still works while viewing a camera (CCTV never clears other effects).
