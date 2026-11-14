"""Writes the placeholder textures of the CCTV pack (stdlib only, deterministic).

Replace any of them with your own art of the same size; the models only care about the file names and the
UV layout documented in docs/ASSETS.md. Run: python3 tools/gen_placeholder_textures.py
"""
import os, random, struct, zlib

ROOT = os.path.join(os.path.dirname(__file__), '..', 'resourcepack', 'assets', 'cctv', 'textures')

def png(path, w, h, px):
    raw = b''.join(b'\x00' + bytes(c for p in px[y * w:(y + 1) * w] for c in p) for y in range(h))
    def chunk(t, d):
        c = struct.pack('>I', len(d)) + t + d
        return c + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    data = (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, 'wb').write(data)

def noisy(base, amount, seed, w=16, h=16):
    r = random.Random(seed)
    out = []
    for y in range(h):
        for x in range(w):
            n = r.randint(-amount, amount)
            # top-to-bottom shading so faces read as rounded housings
            s = int((h / 2 - y) * 1.2)
            out.append(tuple(max(0, min(255, c + n + s)) for c in base) + (255,))
    return out

def lens(ring, glint, seed):
    px = noisy((28, 28, 32), 4, seed)
    for y in range(16):
        for x in range(16):
            d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
            if 5.5 < d <= 7.5:
                px[y * 16 + x] = ring + (255,)
            elif d <= 5.5:
                v = int(10 + 18 * (5.5 - d) / 5.5)
                px[y * 16 + x] = (v, v, v + 6, 255)
    for (x, y) in ((5, 5), (6, 5), (5, 6)):
        px[y * 16 + x] = glint + (255,)
    return px

def main():
    item = os.path.join(ROOT, 'item')
    png(os.path.join(item, 'camera_body.png'), 16, 16, noisy((205, 205, 200), 6, 1))
    png(os.path.join(item, 'camera_dark.png'), 16, 16, noisy((58, 58, 62), 5, 2))
    png(os.path.join(item, 'camera_lens.png'), 16, 16, lens((70, 70, 78), (150, 190, 255), 3))
    png(os.path.join(item, 'camera_lens_ir.png'), 16, 16, lens((150, 20, 20), (255, 120, 120), 4))
    png(os.path.join(item, 'camera_lens_thermal.png'), 16, 16, lens((210, 120, 20), (255, 220, 120), 5))
    # broken: dark housing with bright cracks
    px = noisy((70, 68, 66), 10, 6)
    r = random.Random(7)
    x, y = 2, 3
    for _ in range(28):
        px[y * 16 + x] = (190, 190, 185, 255)
        x = max(0, min(15, x + r.choice((0, 1, 1))))
        y = max(0, min(15, y + r.choice((-1, 0, 1, 1))))
    png(os.path.join(item, 'camera_broken.png'), 16, 16, px)
    # monitors
    png(os.path.join(item, 'monitor_plastic.png'), 16, 16, noisy((48, 50, 52), 4, 11))
    png(os.path.join(item, 'monitor_bezel.png'), 16, 16, noisy((18, 18, 20), 3, 12))
    # terminal
    scr = noisy((20, 40, 28), 6, 13)
    for y in range(2, 14, 3):
        for x in range(2, 2 + (y * 7) % 11):
            scr[y * 16 + x] = (90, 220, 120, 255)
    png(os.path.join(item, 'terminal_screen.png'), 16, 16, scr)
    keys = noisy((70, 72, 74), 3, 14)
    for y in range(1, 16, 3):
        for x in range(1, 16, 2):
            keys[y * 16 + x] = (150, 152, 150, 255)
    png(os.path.join(item, 'terminal_keys.png'), 16, 16, keys)
    # flat camera status icons for the browser GUI
    shape = ['................', '................', '................', '..##########....',
             '..#........###..', '..#.####...#.#..', '..#.#..#...#.#..', '..#.####...#.#..',
             '..#........###..', '..##########....', '......##........', '.....####.......',
             '....######......', '................', '................', '................']
    for name, col in (('online', (80, 220, 90)), ('damaged', (235, 200, 60)), ('offline', (220, 60, 50)),
                      ('unpowered', (140, 140, 150)), ('disabled', (70, 70, 75)), ('alert', (255, 40, 40))):
        px = []
        for y in range(16):
            for x in range(16):
                px.append(col + (255,) if shape[y][x] == '#' else (0, 0, 0, 0))
        png(os.path.join(item, 'gui_cam_' + name + '.png'), 16, 16, px)
    # chest-GUI background (176 x 222, the vanilla 6-row container size)
    W, H = 176, 222
    bg = [(24, 28, 27, 255)] * (W * H)
    def rect(x0, y0, x1, y1, c):
        for yy in range(y0, y1):
            for xx in range(x0, x1):
                bg[yy * W + xx] = c
    rect(0, 0, W, 1, (70, 80, 76, 255)); rect(0, H - 1, W, H, (10, 12, 12, 255))
    rect(0, 0, 1, H, (70, 80, 76, 255)); rect(W - 1, 0, W, H, (10, 12, 12, 255))
    rect(1, 1, W - 1, 14, (16, 34, 24, 255))                       # title bar
    for yy in range(1, 14, 2):
        rect(1, yy, W - 1, yy + 1, (20, 40, 29, 255))              # scanlines in the title bar
    def slot(x, y):
        rect(x, y, x + 18, y + 18, (40, 46, 44, 255))
        rect(x, y, x + 17, y + 17, (8, 10, 10, 255))
        rect(x + 1, y + 1, x + 17, y + 17, (14, 17, 16, 255))
    for r in range(6):
        for c in range(9):
            slot(7 + c * 18, 17 + r * 18)
    rect(7, 128, W - 7, 129, (60, 120, 80, 255))                   # divider above the player inventory
    for r in range(3):
        for c in range(9):
            slot(7 + c * 18, 139 + r * 18)
    for c in range(9):
        slot(7 + c * 18, 197)
    png(os.path.join(ROOT, 'gui', 'browser.png'), W, H, bg)
    # pack icon: dark square with a red REC dot
    icon = []
    for yy in range(64):
        for xx in range(64):
            d = ((xx - 46) ** 2 + (yy - 18) ** 2) ** 0.5
            icon.append((220, 30, 30, 255) if d < 7 else (20, 24, 22, 255) if 4 < xx < 59 and 4 < yy < 59
                        else (120, 120, 120, 255))
    png(os.path.join(ROOT, '..', '..', '..', 'pack.png'), 64, 64, icon)

if __name__ == '__main__':
    main()
