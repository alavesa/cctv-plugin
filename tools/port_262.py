#!/usr/bin/env python3
"""Generates the Minecraft 26.2 files of the CCTV pack from the 26.3 sources.

The pack supports both versions with one zip:
  resourcepack/                 base, read by 26.2 AND 26.3 clients (format 88..97)
  resourcepack/cctv_26_3/       overlay, read only by 26.3 clients (format 97); its files replace the base ones

Shaders are written once, in 26.3 style, under cctv_26_3/. This script writes their 26.2 twins into the base:
  * #include              -> #moj_import
  * no "#extension GL_ARB_separate_shader_objects" and no "layout(location = N)" on in/out variables
  * the inlined Globals uniform block in 26.2 member order
  * core/text.vsh/fsh: the vanilla 26.2 files (tools/vanilla_26_2/) with the same CCTV blocks spliced in
It also writes pack.mcmeta (formats 88..97 + the overlay entry) and the vanilla creeper/spider/invert post-effect
overrides, which carry the CCTV look on 26.2 (where the server can't send post effects) via the mob backend.

Run after editing anything under resourcepack/cctv_26_3/:  python3 tools/port_262.py
"""
import json, os, re, shutil

HERE = os.path.dirname(os.path.abspath(__file__))
PACK = os.path.join(HERE, '..', 'resourcepack')
OVERLAY = 'cctv_26_3'
SRC = os.path.join(PACK, OVERLAY)

GLOBALS_263 = '''layout(std140) uniform Globals {
    ivec3 CameraBlockPos;
    float GlintAlpha;
    vec3 CameraOffset;
    float GameTime;
    vec2 ScreenSize;
    int MenuBlurRadius;
    int UseRgss;
};'''
GLOBALS_262 = '''layout(std140) uniform Globals {
    ivec3 CameraBlockPos;
    vec3 CameraOffset;
    vec2 ScreenSize;
    float GlintAlpha;
    float GameTime;
    int MenuBlurRadius;
    int UseRgss;
};'''


def port(src):
    out = []
    for line in src.splitlines(keepends=True):
        if line.strip() == '#extension GL_ARB_separate_shader_objects : require':
            continue
        line = re.sub(r'^(\s*)#include(\s+<)', r'\1#moj_import\2', line)
        line = re.sub(r'^(\s*(?:flat\s+)?)layout\(location\s*=\s*\d+\)\s*((?:flat\s+)?(?:in|out)\s)', r'\1\2', line)
        out.append(line)
    text = ''.join(out)
    text = text.replace('same block and layout as minecraft:globals.glsl in the 26.3 client',
                        'same block and layout as minecraft:globals.glsl in the 26.2 client')
    text = text.replace('Same block and layout as minecraft:globals.glsl (26.3)',
                        'Same block and layout as minecraft:globals.glsl (26.2)')
    if GLOBALS_263 in text:
        text = text.replace(GLOBALS_263, GLOBALS_262)
    return text


BLOCK = re.compile(r'[ \t]*// ===== CCTV BEGIN =====.*?// ===== CCTV END =====[ \t]*\n', re.S)


def splice_262(vanilla, cctv_263, kind):
    inc, call = BLOCK.findall(cctv_263)
    inc = port(inc)
    lines = vanilla.splitlines(keepends=True)
    last_import = max(i for i, l in enumerate(lines) if l.lstrip().startswith('#moj_import'))
    if kind == 'vsh':
        at = next(i for i, l in enumerate(lines) if re.match(r'\s*gl_Position\s*=', l)) + 1
    else:
        # 26.2 computes `color` in an #ifdef IS_SEE_THROUGH / #else / #endif: go in right before the alpha test
        at = next(i for i, l in enumerate(lines) if re.match(r'\s*if \(color\.a < 0\.1\)', l))
    return ''.join(lines[:last_import + 1] + ['\n', inc] + lines[last_import + 1:at] + [call] + lines[at:])


def write(rel, text):
    p = os.path.join(PACK, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, 'w', newline='\n') as f:
        f.write(text)


def main():
    n = 0
    for base, _, names in os.walk(os.path.join(SRC, 'assets', 'cctv', 'shaders')):
        for name in names:
            src = os.path.join(base, name)
            rel = os.path.relpath(src, SRC)
            write(rel, port(open(src).read()))
            n += 1
    core = os.path.join('assets', 'minecraft', 'shaders', 'core')
    for kind in ('vsh', 'fsh'):
        vanilla = open(os.path.join(HERE, 'vanilla_26_2', 'text.' + kind)).read()
        ours = open(os.path.join(SRC, core, 'text.' + kind)).read()
        write(os.path.join(core, 'text.' + kind), splice_262(vanilla, ours, kind))
        n += 1
    # the mob backend's looks: spectating a creeper / spider / enderman shows these instead of the vanilla ones
    pe = os.path.join(PACK, 'assets', 'cctv', 'post_effect')
    for vanilla, ours in (('creeper', 'standard'), ('spider', 'night_vision'), ('invert', 'thermal')):
        dst = os.path.join(PACK, 'assets', 'minecraft', 'post_effect', vanilla + '.json')
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copyfile(os.path.join(pe, ours + '.json'), dst)
        n += 1
    meta = {'pack': {'description': 'CCTV: cameras, feeds and monitors (Minecraft 26.2 and 26.3)',
                     'pack_format': 88, 'min_format': 88, 'max_format': 97},
            'overlays': {'entries': [{'min_format': 97, 'max_format': 97, 'directory': OVERLAY}]}}
    write('pack.mcmeta', json.dumps(meta, indent=2) + '\n')
    print(f'wrote {n} 26.2 files and pack.mcmeta')


if __name__ == '__main__':
    main()
