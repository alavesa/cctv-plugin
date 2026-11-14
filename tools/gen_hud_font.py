"""Generates the CCTV HUD font: textures/font/osd.png and font/hud_*.json, font/space.json.

Design (see docs/HUD.md):
- One glyph sheet, 16 columns x 7 rows of cells, each cell 6 px wide and CELL_H px tall. The glyph content sits in
  the TOP 8 rows of the cell; the rest is transparent. Tall cells are needed because a bitmap provider may not have
  ascent > height (verified, 26.3 BitmapProvider.Definition.validate): with height == CELL_H a row-font can lift its
  glyphs up to CELL_H font pixels above the line.
- Every visible pixel has alpha 254, never 255. The core/text shader treats only alpha-254 texels as CCTV glyphs,
  so vanilla glyphs (alpha 255) never get CCTV effects even if their text colour happens to match the marker.
- Each cell has one alpha-1 pixel in its last column (invisible: the shader discards alpha < 0.1). The client
  measures a bitmap glyph's width from its last non-transparent column, so this makes every glyph exactly 6 wide:
  advance = round(6 * height/CELL_H) + 1 = 7 font px at 1x, 13 at 2x. The plugin relies on that to lay text out.
- One font per HUD row (cctv:hud_0 .. hud_N, cctv:hud_big_0 ..), all using the same sheet, differing only in
  ascent. Fonts are separate namespaced ids, so the ASCII codepoints inside them can't collide with any other font.

Run: python3 tools/gen_hud_font.py
"""
import json, os, struct, zlib

ROOT = os.path.join(os.path.dirname(__file__), '..', 'resourcepack', 'assets', 'cctv')
CELL_W, CELL_H, COLS, ROWS = 6, 64, 16, 7
ROW_COUNT, ROW_STEP, ROW_TOP = 14, 9, 64        # 1x rows: ascent = ROW_TOP - r * ROW_STEP
BIG_ROWS, BIG_STEP, BIG_TOP = 6, 18, 128        # 2x rows: height = 2 * CELL_H

G = {}
def g(ch, *rows):
    G[ch] = rows
g(' ', *['.....'] * 7)
g('0', '.###.', '#...#', '#..##', '#.#.#', '##..#', '#...#', '.###.')
g('1', '..#..', '.##..', '..#..', '..#..', '..#..', '..#..', '.###.')
g('2', '.###.', '#...#', '....#', '...#.', '..#..', '.#...', '#####')
g('3', '#####', '...#.', '..#..', '...#.', '....#', '#...#', '.###.')
g('4', '...#.', '..##.', '.#.#.', '#..#.', '#####', '...#.', '...#.')
g('5', '#####', '#....', '####.', '....#', '....#', '#...#', '.###.')
g('6', '..##.', '.#...', '#....', '####.', '#...#', '#...#', '.###.')
g('7', '#####', '....#', '...#.', '..#..', '.#...', '.#...', '.#...')
g('8', '.###.', '#...#', '#...#', '.###.', '#...#', '#...#', '.###.')
g('9', '.###.', '#...#', '#...#', '.####', '....#', '...#.', '.##..')
g('A', '.###.', '#...#', '#...#', '#####', '#...#', '#...#', '#...#')
g('B', '####.', '#...#', '#...#', '####.', '#...#', '#...#', '####.')
g('C', '.###.', '#...#', '#....', '#....', '#....', '#...#', '.###.')
g('D', '###..', '#..#.', '#...#', '#...#', '#...#', '#..#.', '###..')
g('E', '#####', '#....', '#....', '####.', '#....', '#....', '#####')
g('F', '#####', '#....', '#....', '####.', '#....', '#....', '#....')
g('G', '.###.', '#...#', '#....', '#.###', '#...#', '#...#', '.####')
g('H', '#...#', '#...#', '#...#', '#####', '#...#', '#...#', '#...#')
g('I', '.###.', '..#..', '..#..', '..#..', '..#..', '..#..', '.###.')
g('J', '..###', '...#.', '...#.', '...#.', '...#.', '#..#.', '.##..')
g('K', '#...#', '#..#.', '#.#..', '##...', '#.#..', '#..#.', '#...#')
g('L', '#....', '#....', '#....', '#....', '#....', '#....', '#####')
g('M', '#...#', '##.##', '#.#.#', '#.#.#', '#...#', '#...#', '#...#')
g('N', '#...#', '#...#', '##..#', '#.#.#', '#..##', '#...#', '#...#')
g('O', '.###.', '#...#', '#...#', '#...#', '#...#', '#...#', '.###.')
g('P', '####.', '#...#', '#...#', '####.', '#....', '#....', '#....')
g('Q', '.###.', '#...#', '#...#', '#...#', '#.#.#', '#..#.', '.##.#')
g('R', '####.', '#...#', '#...#', '####.', '#.#..', '#..#.', '#...#')
g('S', '.####', '#....', '#....', '.###.', '....#', '....#', '####.')
g('T', '#####', '..#..', '..#..', '..#..', '..#..', '..#..', '..#..')
g('U', '#...#', '#...#', '#...#', '#...#', '#...#', '#...#', '.###.')
g('V', '#...#', '#...#', '#...#', '#...#', '#...#', '.#.#.', '..#..')
g('W', '#...#', '#...#', '#...#', '#.#.#', '#.#.#', '#.#.#', '.#.#.')
g('X', '#...#', '#...#', '.#.#.', '..#..', '.#.#.', '#...#', '#...#')
g('Y', '#...#', '#...#', '.#.#.', '..#..', '..#..', '..#..', '..#..')
g('Z', '#####', '....#', '...#.', '..#..', '.#...', '#....', '#####')
g('!', '..#..', '..#..', '..#..', '..#..', '..#..', '.....', '..#..')
g('"', '.#.#.', '.#.#.', '.....', '.....', '.....', '.....', '.....')
g('#', '.#.#.', '.#.#.', '#####', '.#.#.', '#####', '.#.#.', '.#.#.')
g('%', '##...', '##..#', '...#.', '..#..', '.#...', '#..##', '...##')
g('&', '.##..', '#..#.', '#.#..', '.#...', '#.#.#', '#..#.', '.##.#')
g("'", '..#..', '..#..', '.#...', '.....', '.....', '.....', '.....')
g('(', '...#.', '..#..', '.#...', '.#...', '.#...', '..#..', '...#.')
g(')', '.#...', '..#..', '...#.', '...#.', '...#.', '..#..', '.#...')
g('*', '.....', '..#..', '#.#.#', '.###.', '#.#.#', '..#..', '.....')
g('+', '.....', '..#..', '..#..', '#####', '..#..', '..#..', '.....')
g(',', '.....', '.....', '.....', '.....', '.##..', '..#..', '.#...')
g('-', '.....', '.....', '.....', '.###.', '.....', '.....', '.....')
g('.', '.....', '.....', '.....', '.....', '.....', '.##..', '.##..')
g('/', '....#', '....#', '...#.', '..#..', '.#...', '#....', '#....')
g(':', '.....', '.##..', '.##..', '.....', '.##..', '.##..', '.....')
g(';', '.....', '.##..', '.##..', '.....', '.##..', '..#..', '.#...')
g('<', '...#.', '..#..', '.#...', '#....', '.#...', '..#..', '...#.')
g('=', '.....', '.....', '#####', '.....', '#####', '.....', '.....')
g('>', '.#...', '..#..', '...#.', '....#', '...#.', '..#..', '.#...')
g('?', '.###.', '#...#', '....#', '...#.', '..#..', '.....', '..#..')
g('@', '.###.', '#...#', '#.###', '#.#.#', '#.###', '#....', '.###.')
g('[', '.###.', '.#...', '.#...', '.#...', '.#...', '.#...', '.###.')
g(']', '.###.', '...#.', '...#.', '...#.', '...#.', '...#.', '.###.')
g('_', '.....', '.....', '.....', '.....', '.....', '.....', '#####')
g('|', '..#..', '..#..', '..#..', '..#..', '..#..', '..#..', '..#..')

# graphics, Private Use Area (rows are 8 tall here: they may use the descender row)
WHITE, RED, DIM = (255, 255, 255), (235, 40, 35), (110, 110, 110)
GFX = {
    0xE000: (RED, ['.....', '.###.', '#####', '#####', '#####', '.###.', '.....', '.....']),   # REC dot
    0xE001: (WHITE, ['.....'] * 6 + ['##...', '##...']),                                        # signal bar 1
    0xE002: (WHITE, ['.....'] * 4 + ['##...'] * 4),                                             # signal bar 2
    0xE003: (WHITE, ['.....'] * 2 + ['##...'] * 6),                                             # signal bar 3
    0xE004: (WHITE, ['##...'] * 8),                                                             # signal bar 4
    0xE005: (DIM, ['.....'] * 6 + ['##...', '##...']),                                          # empty bars
    0xE006: (DIM, ['.....'] * 4 + ['##...'] * 4),
    0xE007: (DIM, ['.....'] * 2 + ['##...'] * 6),
    0xE008: (DIM, ['##...'] * 8),
    0xE009: (WHITE, ['######'] * 8),                                                            # solid block
    0xE00A: (WHITE, ['#....', '##...', '###..', '####.', '###..', '##...', '#....', '.....']),  # pointer
}

def sheet_chars():
    order = [chr(c) for c in range(0x20, 0x7F)]          # 95 ASCII cells
    order += [chr(c) for c in sorted(GFX)]               # graphics
    while len(order) < COLS * ROWS:
        order.append(chr(0xE0F0 + len(order)))           # unused filler codepoints, never sent
    return order

def png(path, w, h, px):
    raw = b''.join(b'\x00' + bytes(c for p in px[y * w:(y + 1) * w] for c in p) for y in range(h))
    def chunk(t, d):
        c = struct.pack('>I', len(d)) + t + d
        return c + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, 'wb').write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
                           + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))

def main():
    chars = sheet_chars()
    w, h = COLS * CELL_W, ROWS * CELL_H
    px = [(0, 0, 0, 0)] * (w * h)
    for i, ch in enumerate(chars):
        cx, cy = (i % COLS) * CELL_W, (i // COLS) * CELL_H
        if ord(ch) in GFX:
            color, rows = GFX[ord(ch)]
        else:
            key = ch.upper() if ch.upper() in G else (ch if ch in G else '?')
            color, rows = WHITE, list(G[key]) + ['.....']
        for y, row in enumerate(rows):
            for x, c in enumerate(row):
                if c == '#':
                    px[(cy + y) * w + cx + x] = color + (254,)
        # width keeper: one alpha-1 pixel in the last column (see module doc)
        if px[(cy + 7) * w + cx + CELL_W - 1][3] == 0:
            px[(cy + 7) * w + cx + CELL_W - 1] = (0, 0, 0, 1)
    png(os.path.join(ROOT, 'textures', 'font', 'osd.png'), w, h, px)

    rows = [''.join(chars[r * COLS:(r + 1) * COLS]) for r in range(ROWS)]
    fdir = os.path.join(ROOT, 'font')
    os.makedirs(fdir, exist_ok=True)
    for r in range(ROW_COUNT):
        json.dump({'providers': [{'type': 'bitmap', 'file': 'cctv:font/osd.png', 'height': CELL_H,
                                  'ascent': ROW_TOP - r * ROW_STEP, 'chars': rows}]},
                  open(os.path.join(fdir, f'hud_{r}.json'), 'w'), indent=1, ensure_ascii=False)
    for r in range(BIG_ROWS):
        json.dump({'providers': [{'type': 'bitmap', 'file': 'cctv:font/osd.png', 'height': CELL_H * 2,
                                  'ascent': BIG_TOP - r * BIG_STEP, 'chars': rows}]},
                  open(os.path.join(fdir, f'hud_big_{r}.json'), 'w'), indent=1, ensure_ascii=False)
    # signed spaces: U+F000 + n = +2^n font px, U+F100 + n = -2^n (n = 0..9), U+F0FF = +0.5, U+F1FF = -0.5
    adv = {}
    for n in range(10):
        adv[chr(0xF000 + n)] = float(2 ** n)
        adv[chr(0xF100 + n)] = -float(2 ** n)
    adv[chr(0xF0FF)] = 0.5
    adv[chr(0xF1FF)] = -0.5
    json.dump({'providers': [{'type': 'space', 'advances': adv}]}, open(os.path.join(fdir, 'space.json'), 'w'),
              indent=1, ensure_ascii=False)

if __name__ == '__main__':
    main()
