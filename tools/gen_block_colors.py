#!/usr/bin/env python3
"""Builds src/main/resources/block_colors.tsv: average colour of each block's top, side and bottom texture, plus
texture contrast, measured from a vanilla client jar. The monitor feed renderer uses it instead of map colours, so a
feed shows oak planks as oak, stone as stone grey, grass as grass green.

Only averaged colours end up in the plugin, no textures. Run with the client jar of the oldest supported version:
  python3 tools/gen_block_colors.py ~/.minecraft/versions/26.2/26.2.jar

Blocks without a recognisable texture fall back to their map colour at runtime.
"""
import io, json, os, re, struct, sys, zipfile, zlib

OUT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'block_colors.tsv')

# plains-biome tints for grayscale textures
GRASS, FOLIAGE, WATER = (0x91, 0xBD, 0x59), (0x77, 0xAB, 0x2F), (0x3F, 0x76, 0xE4)
TINTS = {
    'grass_block_top': GRASS, 'short_grass': GRASS, 'grass': GRASS, 'tall_grass_top': GRASS, 'tall_grass_bottom': GRASS,
    'fern': GRASS, 'large_fern_top': GRASS, 'large_fern_bottom': GRASS, 'sugar_cane': GRASS, 'vine': FOLIAGE,
    'oak_leaves': FOLIAGE, 'jungle_leaves': FOLIAGE, 'acacia_leaves': FOLIAGE, 'dark_oak_leaves': FOLIAGE,
    'mangrove_leaves': FOLIAGE, 'birch_leaves': (0x80, 0xA7, 0x55), 'spruce_leaves': (0x61, 0x99, 0x61),
    'lily_pad': (0x20, 0x80, 0x30), 'water_still': WATER, 'water_flow': WATER,
}


def png_rgba(data):
    """Minimal PNG decoder: greyscale / RGB / palette / grey+alpha / RGBA, bit depths 1-8, non-interlaced."""
    if data[:8] != b'\x89PNG\r\n\x1a\n':
        return None
    pos, idat, plte, trns = 8, b'', None, None
    w = h = depth = ctype = 0
    while pos < len(data):
        ln, = struct.unpack('>I', data[pos:pos + 4]); typ = data[pos + 4:pos + 8]; body = data[pos + 8:pos + 8 + ln]
        pos += 12 + ln
        if typ == b'IHDR':
            w, h, depth, ctype, _, _, interlace = struct.unpack('>IIBBBBB', body)
            if depth > 8 or interlace:
                return None
        elif typ == b'PLTE':
            plte = body
        elif typ == b'tRNS':
            trns = body
        elif typ == b'IDAT':
            idat += body
    ch = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}.get(ctype)
    if ch is None:
        return None
    raw = zlib.decompress(idat)
    bpp = max(1, ch * depth // 8)                 # bytes per pixel for the filters
    stride = (w * ch * depth + 7) // 8
    out, prev, i = [], bytearray(stride), 0
    for _ in range(h):
        f = raw[i]; line = bytearray(raw[i + 1:i + 1 + stride]); i += 1 + stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b = prev[x]
            c = prev[x - bpp] if x >= bpp else 0
            if f == 1: line[x] = (line[x] + a) & 255
            elif f == 2: line[x] = (line[x] + b) & 255
            elif f == 3: line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c; pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        prev = line
        if depth < 8:  # unpack 1/2/4-bit samples (palette or grey only)
            per = 8 // depth; mask = (1 << depth) - 1
            samples = [(line[k // per] >> (8 - depth * (k % per + 1))) & mask for k in range(w)]
            for k in samples:
                if ctype == 3:
                    al = trns[k] if trns and k < len(trns) else 255
                    out.append((plte[k * 3], plte[k * 3 + 1], plte[k * 3 + 2], al))
                else:
                    v = k * 255 // mask
                    out.append((v, v, v, 255))
            continue
        for x in range(w):
            px = line[x * ch:(x + 1) * ch]
            if ctype == 0: out.append((px[0], px[0], px[0], 255))
            elif ctype == 2: out.append((px[0], px[1], px[2], 255))
            elif ctype == 3:
                k = px[0]; al = trns[k] if trns and k < len(trns) else 255
                out.append((plte[k * 3], plte[k * 3 + 1], plte[k * 3 + 2], al))
            elif ctype == 4: out.append((px[0], px[0], px[0], px[1]))
            else: out.append(tuple(px))
    return w, h, out


def stats(jar, name):
    """(r, g, b, contrast) of a block texture, first frame of animated ones; None if missing or fully transparent."""
    path = f'assets/minecraft/textures/block/{name}.png'
    try:
        img = png_rgba(jar.read(path))
    except KeyError:
        return None
    if img is None:
        return None
    w, h, px = img
    px = px[:w * w]  # animated strips: first frame
    tint = TINTS.get(name)
    rs = gs = bs = n = 0; lum = []
    for r, g, b, a in px:
        if a < 128:
            continue
        if tint:
            r, g, b = r * tint[0] // 255, g * tint[1] // 255, b * tint[2] // 255
        rs += r; gs += g; bs += b; n += 1
        lum.append(0.299 * r + 0.587 * g + 0.114 * b)
    if n == 0:
        return None
    mean = sum(lum) / n
    std = (sum((l - mean) ** 2 for l in lum) / n) ** 0.5 / 255
    return rs // n, gs // n, bs // n, round(std, 3)


WOODS = ['oak', 'spruce', 'birch', 'jungle', 'acacia', 'dark_oak', 'mangrove', 'cherry', 'bamboo', 'crimson', 'warped',
         'pale_oak']
SUFFIXES = ['_slab', '_stairs', '_wall', '_fence_gate', '_fence', '_pressure_plate', '_button', '_wall_sign', '_sign',
            '_wall_hanging_sign', '_hanging_sign', '_door', '_trapdoor']


def bases(n):
    """Candidate texture names for a block id, most specific first."""
    c = [n]
    for s in SUFFIXES:
        if n.endswith(s):
            b = n[:-len(s)]
            c += [b, b + 's', b + '_planks', b.replace('_brick', '_bricks'), b.replace('_tile', '_tiles')]
            if b in WOODS:
                c.insert(1, b + '_planks')
    if n.endswith('_carpet'):
        c.append(n.replace('_carpet', '_wool'))
    if n.endswith('_wood') or n.endswith('_hyphae'):
        c.append(n.replace('_wood', '_log').replace('_hyphae', '_stem'))
    if n.endswith('_pane'):
        c.append(n[:-5])
    if n in ('water', 'bubble_column'):
        c.append('water_still')
    if n == 'lava':
        c.append('lava_still')
    if n == 'tall_grass':
        c.append('tall_grass_top')
    if n.startswith('potted_'):
        c.append(n[7:])
    if n in ('grass_block', 'podzol', 'mycelium', 'dirt_path'):
        c.append('dirt')
    if n.startswith('waxed_'):
        c.append(n[6:])
    return c


def face(jar, cands, kind):
    for b in cands:
        names = {'top': [b + '_top', b], 'side': [b + '_side', b, b + '_front'], 'bottom': [b + '_bottom', b + '_top', b]}[kind]
        for nm in names:
            s = stats(jar, nm)
            if s:
                return s
    return None


def main():
    jar = zipfile.ZipFile(sys.argv[1])
    blocks = sorted({m.group(1) for m in (re.match(r'assets/minecraft/blockstates/([a-z0-9_]+)\.json$', n)
                                          for n in jar.namelist()) if m})
    rows, missing = [], 0
    for b in blocks:
        c = bases(b)
        top, side, bottom = face(jar, c, 'top'), face(jar, c, 'side'), face(jar, c, 'bottom')
        if not (top or side):
            missing += 1
            continue
        top = top or side; side = side or top; bottom = bottom or side
        contrast = round((top[3] + side[3]) / 2, 3)
        hexs = lambda s: '%02X%02X%02X' % s[:3]
        rows.append(f'{b.upper()}\t{hexs(top)}\t{hexs(side)}\t{hexs(bottom)}\t{contrast}')
    with open(OUT, 'w') as f:
        f.write('# material\ttop\tside\tbottom\tcontrast  (generated by tools/gen_block_colors.py; averaged colours only)\n')
        f.write('\n'.join(rows) + '\n')
    print(f'{len(rows)} blocks written, {missing} without a recognisable texture (map colour fallback)')


if __name__ == '__main__':
    main()
