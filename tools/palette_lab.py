#!/usr/bin/env python3
"""Reference implementation + verification for the Youniversal palettes.

The tone generator here is the same algorithm that ships in Kotlin
(youniversal/src/main/kotlin/dev/youniversal/theme/YouniversalTonalPalette.kt).
Running this script prints the exact hex values used in YouniversalPalette.kt and
asserts WCAG 2.1 contrast for every text/surface pairing the theme ships.
"""
import difflib
import math
import os
import sys

# ---------------------------------------------------------------- sRGB <-> Lab

def srgb_to_linear(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def linear_to_srgb(c):
    c = max(0.0, min(1.0, c))
    v = c * 12.92 if c <= 0.0031308 else 1.055 * (c ** (1 / 2.4)) - 0.055
    return max(0.0, min(1.0, v)) * 255.0


def rgb_to_xyz(r, g, b):
    R, G, B = srgb_to_linear(r), srgb_to_linear(g), srgb_to_linear(b)
    return (
        0.4124564 * R + 0.3575761 * G + 0.1804375 * B,
        0.2126729 * R + 0.7151522 * G + 0.0721750 * B,
        0.0193339 * R + 0.1191920 * G + 0.9503041 * B,
    )


def xyz_to_linear_rgb(x, y, z):
    return (
        3.2404542 * x - 1.5371385 * y - 0.4985314 * z,
        -0.9692660 * x + 1.8760108 * y + 0.0415560 * z,
        0.0556434 * x - 0.2040259 * y + 1.0572252 * z,
    )


WHITE_XYZ = (0.95047, 1.0, 1.08883)  # D65


def _f(t):
    return t ** (1.0 / 3.0) if t > 216 / 24389 else (841 / 108) * t + 4 / 29


def _f_inv(t):
    return t ** 3 if t ** 3 > 216 / 24389 else (108 / 841) * (t - 4 / 29)


def xyz_to_lab(x, y, z):
    fx, fy, fz = _f(x / WHITE_XYZ[0]), _f(y / WHITE_XYZ[1]), _f(z / WHITE_XYZ[2])
    return 116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)


def lab_to_xyz(L, a, b):
    fy = (L + 16) / 116
    return (
        _f_inv(fy + a / 500) * WHITE_XYZ[0],
        _f_inv(fy) * WHITE_XYZ[1],
        _f_inv(fy - b / 200) * WHITE_XYZ[2],
    )


def hex_to_rgb(h):
    h = h.lstrip('#')
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def rgb_to_hex(rgb):
    return '#%02X%02X%02X' % tuple(max(0, min(255, int(round(c)))) for c in rgb)


# ---------------------------------------------------------------- tone engine

def in_gamut(x, y, z, epsilon=1e-3):
    return all(-epsilon <= c <= 1.0 + epsilon for c in xyz_to_linear_rgb(x, y, z))


def tone_from_seed(seed_hex, tone, chroma_scale=1.0):
    """LCh tone: keep the seed hue, force L* = tone, shrink chroma until sRGB-legal."""
    # Endpoints are exact, matching Material 3: tone 0 is black and tone 100 is white.
    if tone <= 0:
        return '#000000'
    if tone >= 100:
        return '#FFFFFF'
    L, a, b = xyz_to_lab(*rgb_to_xyz(*hex_to_rgb(seed_hex)))
    chroma = math.hypot(a, b) * chroma_scale
    hue = math.atan2(b, a)
    for _ in range(128):
        x, y, z = lab_to_xyz(tone, chroma * math.cos(hue), chroma * math.sin(hue))
        if in_gamut(x, y, z):
            return rgb_to_hex(tuple(linear_to_srgb(c) for c in xyz_to_linear_rgb(x, y, z)))
        chroma *= 0.97
    return rgb_to_hex(tuple(linear_to_srgb(c) for c in xyz_to_linear_rgb(*lab_to_xyz(tone, 0, 0))))


TONES = tuple(range(0, 101))


def tonal_palette(seed, chroma_scale=1.0):
    return {t: tone_from_seed(seed, t, chroma_scale) for t in TONES}


# ------------------------------------------------------------------- schemes

def light_scheme(p, s, t, n, nv, e):
    return {
        'primary': p[40], 'onPrimary': p[100], 'primaryContainer': p[90],
        'onPrimaryContainer': p[10], 'inversePrimary': p[80],
        'secondary': s[40], 'onSecondary': s[100], 'secondaryContainer': s[90],
        'onSecondaryContainer': s[10],
        'tertiary': t[40], 'onTertiary': t[100], 'tertiaryContainer': t[90],
        'onTertiaryContainer': t[10],
        'background': n[98], 'onBackground': n[10],
        'surface': n[98], 'onSurface': n[10],
        'surfaceVariant': nv[90], 'onSurfaceVariant': nv[30],
        'surfaceTint': p[40], 'inverseSurface': n[20], 'inverseOnSurface': n[95],
        'error': e[40], 'onError': e[100], 'errorContainer': e[90], 'onErrorContainer': e[10],
        'outline': nv[50], 'outlineVariant': nv[80], 'scrim': n[0],
        'surfaceBright': n[98], 'surfaceDim': n[87],
        'surfaceContainerLowest': n[100], 'surfaceContainerLow': n[96],
        'surfaceContainer': n[94], 'surfaceContainerHigh': n[92],
        'surfaceContainerHighest': n[90],
        'primaryFixed': p[90], 'primaryFixedDim': p[80], 'onPrimaryFixed': p[10],
        'onPrimaryFixedVariant': p[30],
        'secondaryFixed': s[90], 'secondaryFixedDim': s[80], 'onSecondaryFixed': s[10],
        'onSecondaryFixedVariant': s[30],
        'tertiaryFixed': t[90], 'tertiaryFixedDim': t[80], 'onTertiaryFixed': t[10],
        'onTertiaryFixedVariant': t[30],
    }


def dark_scheme(p, s, t, n, nv, e):
    return {
        'primary': p[80], 'onPrimary': p[20], 'primaryContainer': p[30],
        'onPrimaryContainer': p[90], 'inversePrimary': p[40],
        'secondary': s[80], 'onSecondary': s[20], 'secondaryContainer': s[30],
        'onSecondaryContainer': s[90],
        'tertiary': t[80], 'onTertiary': t[20], 'tertiaryContainer': t[30],
        'onTertiaryContainer': t[90],
        'background': n[6], 'onBackground': n[90],
        'surface': n[6], 'onSurface': n[90],
        'surfaceVariant': nv[30], 'onSurfaceVariant': nv[80],
        'surfaceTint': p[80], 'inverseSurface': n[90], 'inverseOnSurface': n[20],
        'error': e[80], 'onError': e[20], 'errorContainer': e[30], 'onErrorContainer': e[90],
        'outline': nv[60], 'outlineVariant': nv[30], 'scrim': n[0],
        'surfaceBright': n[24], 'surfaceDim': n[6],
        'surfaceContainerLowest': n[4], 'surfaceContainerLow': n[10],
        'surfaceContainer': n[12], 'surfaceContainerHigh': n[17],
        'surfaceContainerHighest': n[22],
        'primaryFixed': p[90], 'primaryFixedDim': p[80], 'onPrimaryFixed': p[10],
        'onPrimaryFixedVariant': p[30],
        'secondaryFixed': s[90], 'secondaryFixedDim': s[80], 'onSecondaryFixed': s[10],
        'onSecondaryFixedVariant': s[30],
        'tertiaryFixed': t[90], 'tertiaryFixedDim': t[80], 'onTertiaryFixed': t[10],
        'onTertiaryFixedVariant': t[30],
    }


# ------------------------------------------------------------------ contrast

def rel_luminance(rgb):
    r, g, b = (srgb_to_linear(c) for c in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast(a, b):
    la, lb = rel_luminance(hex_to_rgb(a)), rel_luminance(hex_to_rgb(b))
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


PAIRS = [
    ('onBackground', 'background'), ('onSurface', 'surface'),
    ('onSurface', 'surfaceContainer'), ('onSurface', 'surfaceContainerHigh'),
    ('onSurface', 'surfaceContainerHighest'), ('onSurfaceVariant', 'surfaceVariant'),
    ('onSurfaceVariant', 'surfaceContainer'),
    ('onPrimary', 'primary'), ('onPrimaryContainer', 'primaryContainer'),
    ('onSecondary', 'secondary'), ('onSecondaryContainer', 'secondaryContainer'),
    ('onTertiary', 'tertiary'), ('onTertiaryContainer', 'tertiaryContainer'),
    ('onError', 'error'), ('onErrorContainer', 'errorContainer'),
    ('onPrimaryFixed', 'primaryFixed'), ('onPrimaryFixedVariant', 'primaryFixedDim'),
    ('onSecondaryFixed', 'secondaryFixed'), ('onTertiaryFixed', 'tertiaryFixed'),
    ('inverseOnSurface', 'inverseSurface'),
    ('onSurface', 'surfaceContainerLowest'), ('onSurface', 'surfaceBright'),
]


def check(name, scheme, minimum=4.5):
    failures = []
    for fg, bg in PAIRS:
        ratio = contrast(scheme[fg], scheme[bg])
        if ratio < minimum:
            failures.append('  FAIL %-22s on %-22s %.2f:1' % (fg, bg, ratio))
    print('%-14s %s' % (name, 'all %d pairs >= %.1f:1' % (len(PAIRS), minimum)
                        if not failures else 'PROBLEMS:'))
    for line in failures:
        print(line)
    return not failures


KOTLIN_TARGET = os.path.join('youniversal', 'src', 'main', 'kotlin', 'dev', 'youniversal',
                             'theme', 'YouniversalPalette.kt')


def kotlin_block(name, scheme, factory):
    """Render one color-scheme function exactly as it appears in YouniversalPalette.kt."""
    order = ['primary', 'onPrimary', 'primaryContainer', 'onPrimaryContainer', 'inversePrimary',
             'secondary', 'onSecondary', 'secondaryContainer', 'onSecondaryContainer',
             'tertiary', 'onTertiary', 'tertiaryContainer', 'onTertiaryContainer',
             'background', 'onBackground', 'surface', 'onSurface', 'surfaceVariant',
             'onSurfaceVariant', 'surfaceTint', 'inverseSurface', 'inverseOnSurface',
             'error', 'onError', 'errorContainer', 'onErrorContainer',
             'outline', 'outlineVariant', 'scrim', 'surfaceBright', 'surfaceDim',
             'surfaceContainer', 'surfaceContainerHigh', 'surfaceContainerHighest',
             'surfaceContainerLow', 'surfaceContainerLowest',
             'primaryFixed', 'primaryFixedDim', 'onPrimaryFixed', 'onPrimaryFixedVariant',
             'secondaryFixed', 'secondaryFixedDim', 'onSecondaryFixed', 'onSecondaryFixedVariant',
             'tertiaryFixed', 'tertiaryFixedDim', 'onTertiaryFixed', 'onTertiaryFixedVariant']
    assert set(order) == set(scheme), set(scheme) ^ set(order)
    lines = ['\n// ---- %s ----' % name,
             'public fun youniversal%sColorScheme(): ColorScheme =' % name,
             '    %s(' % factory]
    for key in order:
        lines.append('        %s = Color(0xFF%s),' % (key, scheme[key].lstrip('#')))
    lines.append('    )')
    return '\n'.join(lines)


def dump_kotlin(name, scheme, factory):
    print(kotlin_block(name, scheme, factory))


def scheme_function(text, name):
    """Extract one generated function from the committed Kotlin file."""
    signature = 'public fun youniversal%sColorScheme(): ColorScheme =' % name
    start = text.index(signature)
    end = text.index('\n    )', start) + len('\n    )')
    return text[start:end]


def verify(path):
    """Fail if YouniversalPalette.kt has drifted from what the generator produces."""
    try:
        committed = open(path, encoding='utf-8').read()
    except OSError as exc:
        print('cannot read %s: %s' % (path, exc))
        return 1
    ok = True
    for name, scheme, factory in build_schemes():
        # The committed file carries no '// ---- X ----' separators, so compare
        # from the function signature onward.
        generated = kotlin_block(name, scheme, factory)
        generated = generated[generated.index('public fun'):].strip()
        try:
            actual = scheme_function(committed, name).strip()
        except ValueError:
            print('%-6s MISSING from %s' % (name, path))
            ok = False
            continue
        if generated == actual:
            print('%-6s matches the generator (48 roles)' % name)
        else:
            ok = False
            print('%-6s DRIFTED from the generator:' % name)
            for line in difflib.unified_diff(generated.splitlines(), actual.splitlines(),
                                             'generated', 'committed', lineterm='', n=1):
                print('    ' + line)
    print('\n%s' % ('%s is in sync with tools/palette_lab.py' % os.path.basename(path)
                     if ok else 'run `python3 tools/palette_lab.py` and paste the three '
                                'functions back into %s' % path))
    return 0 if ok else 1


def build_schemes():
    """Build the three shipped palettes. Returns (name, scheme, factory) triples."""
    indigo = tonal_palette('#3E63DD')
    slate = tonal_palette('#5C6391', 0.72)
    teal = tonal_palette('#0F9C8E')
    error = tonal_palette('#DC362E')
    neutral_cool = tonal_palette('#2F3552', 0.10)
    neutral_variant_cool = tonal_palette('#2F3552', 0.22)

    # Cream: warm, slightly-darkened paper neutrals.
    paper = tonal_palette('#8A6B3F', 0.30)
    paper_variant = tonal_palette('#8A6B3F', 0.42)
    terracotta = tonal_palette('#A14E2A')
    sand = tonal_palette('#7A6650', 0.55)
    olive = tonal_palette('#6E7A2E')

    light = light_scheme(indigo, slate, teal, neutral_cool, neutral_variant_cool, error)
    dark = dark_scheme(indigo, slate, teal, neutral_cool, neutral_variant_cool, error)
    cream = light_scheme(terracotta, sand, olive, paper, paper_variant, error)
    # Cream reads as aged paper: the whole neutral ramp sits ~11 tones below the
    # Light scheme, and containers step *down* in tone as they gain emphasis.
    cream.update({
        'background': paper[86], 'onBackground': paper[12],
        'surface': paper[88], 'onSurface': paper[12],
        'surfaceBright': paper[94], 'surfaceDim': paper[76],
        'surfaceContainerLowest': paper[93], 'surfaceContainerLow': paper[90],
        'surfaceContainer': paper[88], 'surfaceContainerHigh': paper[84],
        'surfaceContainerHighest': paper[80],
        'surfaceVariant': paper_variant[86], 'onSurfaceVariant': paper_variant[28],
        'outline': paper_variant[45], 'outlineVariant': paper_variant[76],
        'inverseSurface': paper[22], 'inverseOnSurface': paper[94],
    })

    # A scrim is always pure black; it is only ever used with alpha.
    for scheme in (light, dark, cream):
        scheme['scrim'] = '#000000'

    return [('Light', light, 'lightColorScheme'),
            ('Dark', dark, 'darkColorScheme'),
            ('Cream', cream, 'lightColorScheme')]


def main(argv):
    if '--verify' in argv:
        path = argv[argv.index('--verify') + 1] if len(argv) > argv.index('--verify') + 1 else KOTLIN_TARGET
        return verify(path)

    schemes = build_schemes()
    ok = all([check(name, scheme) for name, scheme, _ in schemes])

    for name, scheme, factory in schemes:
        dump_kotlin(name, scheme, factory)

    indigo = tonal_palette('#3E63DD')
    paper = tonal_palette('#8A6B3F', 0.30)
    print('\nSample ramps (indigo / paper):')
    for t in (10, 20, 30, 40, 80, 90, 95, 99):
        print('  tone %-3d indigo %s   paper %s' % (t, indigo[t], paper[t]))
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
