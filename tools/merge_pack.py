#!/usr/bin/env python3
"""Merge the CCTV resource pack into another resource pack.

Usage:
  python3 tools/merge_pack.py --cctv <cctv pack dir|zip> --into <other pack dir|zip> --out <result.zip|dir>
                              [--set-format]

What it does:
  * copies everything under assets/cctv/ (namespaced: it can't collide with the other pack);
  * keeps the other pack's pack.mcmeta and pack.png. With --set-format, min_format/max_format/pack_format are set to
    97 (Minecraft 26.3); otherwise it only warns if the other pack doesn't cover 97;
  * the only vanilla files CCTV overrides are assets/minecraft/shaders/core/text.vsh and text.fsh:
      - if the other pack doesn't have them, ours are used as they are;
      - if it has them and they already contain CCTV blocks (an earlier merge), those blocks are replaced with ours;
      - otherwise our "// ===== CCTV BEGIN/END =====" blocks are spliced into THEIR files at the same anchors as in
        vanilla: the include block after their last #include line, the call after their `gl_Position = ...` line
        (vertex) and after their `vec4 color = ... texColor ...` line (fragment).
  * if an anchor can't be found, it stops with an explanation and changes nothing. Merge that file by hand
    (docs/PACK.md), then run again: blocks already present are kept up to date.

Everything else in the other pack wins over ours, which is safe because CCTV ships no other vanilla files.
Validate the result with tools/validate_shaders.py and check in game (F3+T reloads resource packs).
"""
import argparse, io, json, os, re, sys, zipfile

TEXT_VSH = 'assets/minecraft/shaders/core/text.vsh'
TEXT_FSH = 'assets/minecraft/shaders/core/text.fsh'
BEGIN = '// ===== CCTV BEGIN ====='
END = '// ===== CCTV END ====='
BLOCK = re.compile(r'[ \t]*' + re.escape(BEGIN) + r'.*?' + re.escape(END) + r'[ \t]*\n', re.S)


def read_pack(path):
    files = {}
    if os.path.isdir(path):
        for base, _, names in os.walk(path):
            for n in names:
                if n == '.DS_Store':
                    continue
                full = os.path.join(base, n)
                files[os.path.relpath(full, path).replace(os.sep, '/')] = open(full, 'rb').read()
    else:
        with zipfile.ZipFile(path) as z:
            for n in z.namelist():
                if not n.endswith('/'):
                    files[n] = z.read(n)
    # some zips wrap everything in one top folder; unwrap if pack.mcmeta is one level down
    if 'pack.mcmeta' not in files:
        tops = {k.split('/', 1)[0] for k in files if '/' in k}
        if len(tops) == 1:
            top = tops.pop() + '/'
            if top + 'pack.mcmeta' in files:
                files = {k[len(top):]: v for k, v in files.items() if k.startswith(top)}
    if 'pack.mcmeta' not in files:
        sys.exit(f'{path}: no pack.mcmeta found, is this a resource pack?')
    return files


def blocks(src):
    """Our CCTV blocks in file order: [include block, call block]."""
    found = BLOCK.findall(src)
    if len(found) != 2:
        sys.exit('the CCTV pack text shader should contain exactly two CCTV blocks; is it the right pack?')
    return found


def splice(name, theirs, ours, anchor_re, what):
    """Puts our two blocks into their shader. Returns the new text or exits with an explanation."""
    inc_block, call_block = blocks(ours)
    if BEGIN in theirs:
        # earlier merge: replace the old blocks with the current ones, in order
        old = BLOCK.findall(theirs)
        if len(old) != 2:
            sys.exit(f'{name}: found {len(old)} CCTV block(s), expected 2. Fix it by hand (docs/PACK.md).')
        out = theirs.replace(old[0], inc_block, 1).replace(old[1], call_block, 1)
        return out, 'updated existing CCTV blocks'
    lines = theirs.splitlines(keepends=True)
    inc_at = None
    for i, l in enumerate(lines):
        if l.lstrip().startswith('#include') or l.lstrip().startswith('#moj_import'):
            inc_at = i
    if inc_at is None:
        for i, l in enumerate(lines):
            if l.lstrip().startswith('#version') or l.lstrip().startswith('#extension'):
                inc_at = i
    if inc_at is None:
        sys.exit(f'{name}: no #include / #version line to put the CCTV include after. Merge by hand (docs/PACK.md).')
    call_at = None
    for i, l in enumerate(lines):
        if re.search(anchor_re, l):
            call_at = i
            break
    if call_at is None:
        sys.exit(f'{name}: could not find {what} in the other pack\'s file. Merge by hand (docs/PACK.md).')
    if call_at < inc_at:
        sys.exit(f'{name}: unexpected layout ({what} before the includes). Merge by hand (docs/PACK.md).')
    # a statement split over several lines: insert after the line that ends it
    end = call_at
    while not lines[end].rstrip().endswith(';') and end + 1 < len(lines):
        end += 1
    out = lines[:inc_at + 1] + ['\n', inc_block] + lines[inc_at + 1:end + 1] + [call_block] + lines[end + 1:]
    return ''.join(out), 'spliced CCTV blocks into the other pack\'s file'


def check_format(meta_bytes, set_format):
    try:
        meta = json.loads(meta_bytes.decode('utf-8'))
    except ValueError:
        sys.exit('the other pack\'s pack.mcmeta is not valid JSON')
    pack = meta.setdefault('pack', {})
    lo = pack.get('min_format', pack.get('pack_format'))
    hi = pack.get('max_format', lo)
    sf = pack.get('supported_formats')  # older form: {min_inclusive, max_inclusive} or [min, max]
    if 'min_format' not in pack and sf is not None:
        if isinstance(sf, dict):
            lo, hi = sf.get('min_inclusive', lo), sf.get('max_inclusive', hi)
        elif isinstance(sf, list) and len(sf) == 2:
            lo, hi = sf
    lo = lo[0] if isinstance(lo, list) else lo
    hi = hi[0] if isinstance(hi, list) else hi
    if set_format:
        pack['pack_format'] = 97
        pack['min_format'] = 97
        pack['max_format'] = 97
        print('pack.mcmeta: format set to 97 (Minecraft 26.3)')
        return json.dumps(meta, indent=2).encode('utf-8')
    if not (isinstance(lo, int) and isinstance(hi, int) and lo <= 97 <= hi):
        print(f'WARNING: the other pack declares formats {lo}..{hi}, not 97 (26.3). 26.3 clients will call it '
              f'incompatible. Run again with --set-format, or edit its pack.mcmeta.')
    return meta_bytes


def write_pack(files, out):
    if out.endswith('.zip'):
        with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
            for name in sorted(files):
                info = zipfile.ZipInfo(name, date_time=(2024, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                z.writestr(info, files[name])
    else:
        for name, data in files.items():
            p = os.path.join(out, name)
            os.makedirs(os.path.dirname(p), exist_ok=True)
            open(p, 'wb').write(data)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--cctv', required=True, help='the CCTV pack (resourcepack/ folder or cctv-pack.zip)')
    ap.add_argument('--into', required=True, help='the other pack (folder or zip)')
    ap.add_argument('--out', required=True, help='result: a .zip file or a folder')
    ap.add_argument('--set-format', action='store_true', help='set the result to pack format 97 (26.3)')
    a = ap.parse_args()

    cctv = read_pack(a.cctv)
    other = read_pack(a.into)
    result = dict(other)
    copied = 0
    for name, data in cctv.items():
        if name.startswith('assets/cctv/'):
            result[name] = data
            copied += 1
    print(f'copied {copied} files under assets/cctv/')

    for name, anchor, what in ((TEXT_VSH, r'^\s*gl_Position\s*=', 'the "gl_Position = ..." line'),
                               (TEXT_FSH, r'^\s*vec4\s+color\s*=.*texColor', 'the "vec4 color = ... texColor" line')):
        ours = cctv.get(name)
        if ours is None:
            sys.exit(f'the CCTV pack has no {name}')
        if name not in other:
            result[name] = ours
            print(f'{name}: the other pack has none, using CCTV\'s')
            continue
        merged, how = splice(name, other[name].decode('utf-8'), ours.decode('utf-8'), anchor, what)
        result[name] = merged.encode('utf-8')
        print(f'{name}: {how}')

    result['pack.mcmeta'] = check_format(other['pack.mcmeta'], a.set_format)
    write_pack(result, a.out)
    print(f'wrote {a.out} ({len(result)} files). Validate the shaders, then test in game.')


if __name__ == '__main__':
    main()
