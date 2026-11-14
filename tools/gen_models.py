"""Generates every CCTV item model + item definition (cameras, monitors, terminal, GUI icons).

Models are placeholders: open them in Blockbench to restyle, keeping file names and the conventions in docs/ASSETS.md.
Run: python3 tools/gen_models.py
"""
import json, os
R = os.path.join(os.path.dirname(__file__), '..', 'resourcepack')
A = os.path.join(R, 'assets', 'cctv')
FACES = ('north', 'south', 'east', 'west', 'up', 'down')
def box(f, t, tex, lens=None, rot=None):
    faces = {}
    for d in FACES:
        if lens and d == 'south':
            faces[d] = {'uv': [0, 0, 16, 16], 'texture': '#' + lens}
        else:
            faces[d] = {'texture': '#' + tex}
    e = {'from': f, 'to': t, 'faces': faces}
    if rot: e['rotation'] = rot
    return e
DISPLAY = {
    'gui': {'rotation': [25, 200, 0], 'translation': [0, 0, 0], 'scale': [1.5, 1.5, 1.5]},
    'ground': {'rotation': [0, 0, 0], 'translation': [0, 2, 0], 'scale': [0.7, 0.7, 0.7]},
    'fixed': {'rotation': [0, 180, 0], 'translation': [0, 0, 0], 'scale': [1.4, 1.4, 1.4]},
    'thirdperson_righthand': {'rotation': [75, 45, 0], 'translation': [0, 2.5, 0], 'scale': [0.75, 0.75, 0.75]},
    'firstperson_righthand': {'rotation': [0, 135, 0], 'translation': [0, 2, 0], 'scale': [0.9, 0.9, 0.9]},
}
TEX = {'plastic': 'cctv:item/monitor_plastic', 'bezel': 'cctv:item/monitor_bezel', 'screen': 'cctv:item/terminal_screen', 'keys': 'cctv:item/terminal_keys', 'body': 'cctv:item/camera_body', 'dark': 'cctv:item/camera_dark', 'lens': 'cctv:item/camera_lens',
       'ir': 'cctv:item/camera_lens_ir', 'thermal': 'cctv:item/camera_lens_thermal', 'broken': 'cctv:item/camera_broken'}
def model(name, elements, textures, display=None):
    tx = {k: TEX[k] for k in textures}
    tx['particle'] = TEX[textures[0]]
    m = {'textures': tx, 'elements': elements, 'display': display or DISPLAY}
    p = os.path.join(A, 'models', 'item', name + '.json')
    os.makedirs(os.path.dirname(p), exist_ok=True)
    json.dump(m, open(p, 'w'), indent=2)
    d = os.path.join(A, 'items', name + '.json')
    os.makedirs(os.path.dirname(d), exist_ok=True)
    json.dump({'model': {'type': 'minecraft:model', 'model': 'cctv:item/' + name}}, open(d, 'w'), indent=2)

def bullet(housing, lens):
    return [box([6, 6.5, 3], [10, 10, 12], housing),
            box([6.5, 7, 12], [9.5, 9.5, 12.5], 'dark', lens),
            box([5.5, 10, 3], [10.5, 10.5, 13], housing),
            box([6, 6.5, 2.5], [10, 10, 3], 'dark'),
            box([7.25, 5, 6.5], [8.75, 6.5, 8], 'dark')]
BULLET_MOUNT = [box([5, 3.2, 5], [11, 4, 11], 'dark'), box([7.4, 4, 7.4], [8.6, 7, 8.6], 'dark')]
DOME_HOUSING = [box([4, 3.2, 4], [12, 4.4, 12], 'body'), box([5, 4.4, 5], [11, 5.2, 11], 'dark')]
DOME_LENS = [box([5.75, 5.75, 5.75], [10.25, 10.25, 10.25], 'dark', 'lens')]
PTZ_BASE = [box([4.5, 3.2, 4.5], [11.5, 4.2, 11.5], 'dark'), box([6, 4.2, 6], [10, 5.5, 10], 'body'),
            box([5, 5.5, 7], [5.8, 9, 9], 'dark'), box([10.2, 5.5, 7], [11, 9, 9], 'dark')]
PTZ_HEAD = [box([5.8, 6.2, 4], [10.2, 9.8, 11.5], 'body'), box([6.5, 6.8, 11.5], [9.5, 9.2, 12], 'dark', 'lens')]

model('camera_bullet', bullet('body', 'lens'), ['body', 'dark', 'lens'])
model('camera_night_vision', bullet('dark', 'ir'), ['dark', 'ir'])
model('camera_thermal', bullet('dark', 'thermal'), ['dark', 'thermal'])
model('camera_bullet_mount', BULLET_MOUNT, ['dark'])
model('camera_dome_housing', DOME_HOUSING, ['body', 'dark'])
model('camera_dome_lens', DOME_LENS, ['dark', 'lens'])
model('camera_dome_item', DOME_HOUSING + DOME_LENS, ['body', 'dark', 'lens'])
model('camera_ptz_base', PTZ_BASE, ['dark', 'body'])
model('camera_ptz_head', PTZ_HEAD, ['body', 'dark', 'lens'])
model('camera_ptz_item', PTZ_BASE + PTZ_HEAD, ['body', 'dark', 'lens'])
model('camera_broken', [box([6, 6, 3], [10, 9.5, 11], 'broken', rot={'origin': [8, 8, 8], 'axis': 'x', 'angle': 22.5}),
                        box([6.75, 5, 10], [9.25, 7, 11], 'dark', rot={'origin': [8, 8, 8], 'axis': 'z', 'angle': -22.5})],
      ['broken', 'dark'])


BLOCK_DISPLAY = {
    'gui': {'rotation': [25, 200, 0], 'scale': [0.62, 0.62, 0.62]},
    'ground': {'translation': [0, 2, 0], 'scale': [0.3, 0.3, 0.3]},
    'fixed': {'rotation': [0, 180, 0], 'scale': [0.5, 0.5, 0.5]},
    'thirdperson_righthand': {'rotation': [75, 45, 0], 'translation': [0, 2.5, 0], 'scale': [0.37, 0.37, 0.37]},
    'firstperson_righthand': {'rotation': [0, 135, 0], 'scale': [0.4, 0.4, 0.4]},
}

# ---- monitors -------------------------------------------------------------------------------------------------------
# a 1 px bezel ring standing 1 px proud of the block face (z 16..17); the map sits on the face inside it
RING = [box([0, 0, 16], [16, 1, 17], 'bezel'), box([0, 15, 16], [16, 16, 17], 'bezel'),
        box([0, 1, 16], [1, 15, 17], 'bezel'), box([15, 1, 16], [16, 15, 17], 'bezel')]
model('monitor_tile', RING, ['bezel'], BLOCK_DISPLAY)
# desk CRT: body behind the screen face (z 4..16) and a rear bulge; the screen is the block's front face
model('monitor_desk', RING + [box([0.5, 0.5, 4], [15.5, 15.5, 16], 'plastic'), box([3, 3, 0.5], [13, 13, 4], 'plastic')],
      ['plastic', 'bezel'], BLOCK_DISPLAY)
model('monitor_wall_item', RING + [box([1, 1, 15.5], [15, 15, 16], 'bezel')], ['bezel'], BLOCK_DISPLAY)

# ---- flat-screen TVs -------------------------------------------------------------------------------------------------
# One panel spans every tile of a wall screen, so a tile only draws the frame on the screen's OUTER edges:
# tv_tile_<mask>, mask bits 1 top, 2 bottom, 4 left, 8 right (as seen from the front; 15 = a 1x1 screen).
# Thin sides, slightly deeper chin with the power LED on the bottom-right tile.
TV_SIDE, TV_CHIN, TV_DEPTH = 0.5, 1.0, 0.9
def tv_frame(mask):
    e = []
    top, bottom, left, right = mask & 1, mask & 2, mask & 4, mask & 8
    z0, z1 = 16, 16 + TV_DEPTH
    if top: e.append(box([0, 16 - TV_SIDE, z0], [16, 16, z1], 'tvbezel'))
    if bottom: e.append(box([0, 0, z0], [16, TV_CHIN, z1], 'tvbezel'))
    y0, y1 = (TV_CHIN if bottom else 0), (16 - TV_SIDE if top else 16)
    # front +Z: the viewer's right is model +X (verified on a 26.2 client)
    if left: e.append(box([0, y0, z0], [TV_SIDE, y1, z1], 'tvbezel'))
    if right: e.append(box([16 - TV_SIDE, y0, z0], [16, y1, z1], 'tvbezel'))
    if bottom and right: e.append(box([14.3, 0.3, z1], [14.8, 0.6, z1 + 0.05], 'led'))
    return e
TEX.update({'tvbezel': 'cctv:item/tv_bezel', 'tvback': 'cctv:item/tv_back', 'led': 'cctv:item/tv_led'})
for mask in range(16):
    model('tv_tile_%d' % mask, tv_frame(mask) or [box([0, 0, 16], [0.01, 0.01, 16.01], 'tvbezel')],
          ['tvbezel', 'led'], BLOCK_DISPLAY)
# desk TV/monitor: slim panel (screen = the block's front face) on a neck and foot behind it
TV_DESK = tv_frame(15) + [box([0, 0, 14.6], [16, 16, 15.9], 'tvback'),
                          box([6.5, 0, 11], [9.5, 6, 14.6], 'tvback'),
                          box([4, 0, 7], [12, 0.6, 15], 'tvbezel')]
model('tv_desk', TV_DESK, ['tvbezel', 'tvback', 'led'], BLOCK_DISPLAY)
model('tv_wall_item', tv_frame(15) + [box([0, 0, 15.2], [16, 16, 16], 'tvback')], ['tvbezel', 'tvback', 'led'],
      BLOCK_DISPLAY)

# ---- security terminal: console with a slanted screen and a keyboard, front toward +Z ---------------------------------
model('terminal', [
    box([1, 0, 1], [15, 6, 15], 'plastic'),                       # desk unit
    box([1.5, 6, 9], [14.5, 7, 15], 'keys'),                       # keyboard deck
    box([2, 6, 2], [14, 15, 8], 'plastic'),                        # monitor housing
    box([2.5, 7, 7.9], [13.5, 14.5, 8.05], 'screen', 'screen'),    # screen face (south)
], ['plastic', 'keys', 'screen'], BLOCK_DISPLAY)

# ---- flat GUI icons (status of a camera in the browser) ---------------------------------------------------------------
for state in ('online', 'damaged', 'offline', 'unpowered', 'disabled', 'alert'):
    name = 'gui_cam_' + state
    json.dump({'parent': 'minecraft:item/generated', 'textures': {'layer0': 'cctv:item/' + name}},
              open(os.path.join(A, 'models', 'item', name + '.json'), 'w'), indent=2)
    json.dump({'model': {'type': 'minecraft:model', 'model': 'cctv:item/' + name}},
              open(os.path.join(A, 'items', name + '.json'), 'w'), indent=2)
