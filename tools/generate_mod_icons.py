"""Draws the mod.io artwork for the bundled mods.

    python3 tools/generate_mod_icons.py

For each mod it writes the three sizes mod.io asks for into branding/:

    <mod>-icon-512x512.png    square icon
    <mod>-logo-1280x720.png   16:9 card art (mod.io wants at least 512x288)
    <mod>-header-400x100.png  wordmark strip

Each piece is laid out from the canvas size rather than letterboxed, so the wide
card is a real wide composition and not a square on black bars.

Everything is drawn at 2x and downsampled. Glowing parts are painted into a
separate emissive layer that goes through the same bright-pass -> blur -> add ->
filmic tonemap the engine's shader API does, so the art looks like a frame the
engine would actually render.
"""

import os

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

SUPERSAMPLE = 2
FONT_PATH = "/System/Library/Fonts/HelveticaNeue.ttc"
FONT_BOLD = 1
FONT_CONDENSED_BLACK = 9

PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(PROJECT, "branding")


# ---------------------------------------------------------------------------
# Small drawing helpers
# ---------------------------------------------------------------------------

def font(size, index=FONT_BOLD):
    return ImageFont.truetype(FONT_PATH, int(size), index=index)


def vertical_gradient(w, h, top, bottom):
    """A solid RGB image fading from `top` to `bottom`."""
    ramp = np.linspace(0.0, 1.0, h)[:, None, None]
    pixels = np.array(top, float)[None, None, :] * (1 - ramp) + np.array(bottom, float)[None, None, :] * ramp
    return Image.fromarray(np.tile(pixels, (1, w, 1)).astype(np.uint8), "RGB")


def radial_falloff(w, h, cx, cy, radius):
    """A 0..1 field that is 1 at the centre and 0 at `radius`, for cheap lighting."""
    yy, xx = np.mgrid[0:h, 0:w].astype(float)
    dist = np.hypot(xx - cx, yy - cy) / max(radius, 1e-6)
    return np.clip(1.0 - dist, 0.0, 1.0) ** 2


def tracked_text(draw, cx, cy, text, fnt, fill, tracking):
    """Centred text with extra letter spacing; PIL has no tracking of its own."""
    widths = [draw.textlength(ch, font=fnt) for ch in text]
    total = sum(widths) + tracking * (len(text) - 1)
    x = cx - total / 2
    for ch, width in zip(text, widths):
        draw.text((x, cy), ch, font=fnt, fill=fill, anchor="lm")
        x += width + tracking


def tracked_width(draw, text, fnt, tracking):
    return sum(draw.textlength(ch, font=fnt) for ch in text) + tracking * (len(text) - 1)


def rounded_border(draw, w, h, inset, radius, color, width):
    draw.rounded_rectangle((inset, inset, w - inset, h - inset), radius=radius, outline=color, width=width)


def arch(draw, left, right, base, top, color, width=0, fill=None):
    """A doorway: straight sides under a semicircular head."""
    radius = (right - left) / 2
    head = (left, top, right, top + 2 * radius)
    if fill is not None:
        draw.pieslice(head, 180, 360, fill=fill)
        draw.rectangle((left, top + radius, right, base), fill=fill)
    if width:
        draw.arc(head, 180, 360, fill=color, width=width)
        draw.line((left + width // 2, top + radius, left + width // 2, base), fill=color, width=width)
        draw.line((right - width // 2, top + radius, right - width // 2, base), fill=color, width=width)


def rotated_rect(layer, cx, cy, w, h, radius, angle, fill):
    """A rounded rectangle turned about its own centre; PIL can only draw axis-aligned ones."""
    pad = int(max(w, h))
    stamp = Image.new("L", (pad * 2, pad * 2), 0)
    ImageDraw.Draw(stamp).rounded_rectangle(
        (pad - w / 2, pad - h / 2, pad + w / 2, pad + h / 2), radius=radius, fill=255
    )
    stamp = stamp.rotate(angle, resample=Image.BICUBIC)
    layer.paste(fill, (int(cx - pad), int(cy - pad)), stamp)


def person(draw, x, base, height, color, lean=0.0):
    """A standing figure in silhouette: head, a gap for the neck, tapering body."""
    head_r = height * 0.105
    head_cy = base - height + head_r
    hx = x + lean * height
    draw.ellipse((hx - head_r, head_cy - head_r, hx + head_r, head_cy + head_r), fill=color)
    shoulder = head_cy + head_r * 1.55
    half = height * 0.150
    draw.rounded_rectangle(
        (x - half, shoulder, x + half, base), radius=half * 0.75, fill=color
    )
    # Hips are narrower than the shoulders, so the body is not a plain slab.
    draw.rectangle((x - half * 0.72, base - height * 0.30, x + half * 0.72, base), fill=color)


# ---------------------------------------------------------------------------
# The engine's post chain, applied to the artwork
# ---------------------------------------------------------------------------

def bloom(emissive, unit):
    """Bright-pass blur chain: a few radii added together, like PostProcessor's."""
    glow = np.zeros((emissive.height, emissive.width, 3), float)
    for radius, weight in ((0.006, 0.60), (0.022, 0.45), (0.060, 0.26), (0.150, 0.13)):
        blurred = emissive.filter(ImageFilter.GaussianBlur(unit * radius))
        glow += weight * np.asarray(blurred, float)
    return glow


def tonemap(hdr):
    """ACES-ish filmic curve, so the neon rolls off instead of clipping to white."""
    x = np.clip(hdr / 255.0, 0.0, None)
    mapped = (x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14)
    return np.clip(mapped, 0.0, 1.0) * 255.0


def vignette(rgb, strength):
    h, w = rgb.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w].astype(float)
    dist = np.hypot((xx - w / 2) / (w / 2), (yy - h / 2) / (h / 2)) / 1.414
    return rgb * (1.0 - strength * dist[:, :, None] ** 2.2)


def grain(rgb, amount, seed):
    noise = np.random.default_rng(seed).normal(0.0, amount * 255.0, rgb.shape[:2])
    return rgb + noise[:, :, None]


def finish(base, emissive, unit, bloom_strength=1.0, vignette_strength=0.45, grain_amount=0.006, seed=7,
           occlusion=None):
    """Adds the bloom to `base`, then tonemaps, vignettes and grains it.

    `occlusion` is an optional 0..1 mask of solid foreground. Bloom is a lens
    effect and would technically wash over it, but letting it do so flattens a
    silhouette into fog, so the glow is held back there.
    """
    glow = bloom(emissive, unit) * bloom_strength
    if occlusion is not None:
        glow *= (1.0 - 0.85 * occlusion)[:, :, None]
    hdr = np.asarray(base, float) + glow
    rgb = tonemap(hdr)
    rgb = vignette(rgb, vignette_strength)
    rgb = grain(rgb, grain_amount, seed)
    return Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8), "RGB")


def reflect(emissive, floor_y, unit, amount=0.40):
    """Mirrors the glow below the floor line, blurred and faded, as wet-floor spill."""
    w, h = emissive.width, emissive.height
    flipped = emissive.transpose(Image.FLIP_TOP_BOTTOM)
    canvas = Image.new("RGB", (w, h), (0, 0, 0))
    # The mirror of row `floor_y - d` is row `floor_y + d`.
    canvas.paste(flipped, (0, int(2 * floor_y - h)))
    smeared = canvas.filter(ImageFilter.GaussianBlur(unit * 0.02))
    rgb = np.asarray(smeared, float)
    yy = np.mgrid[0:h, 0:w][0].astype(float)
    fade = np.clip(1.0 - (yy - floor_y) / (h - floor_y + 1e-6), 0.0, 1.0) ** 2
    fade[yy < floor_y] = 0.0
    return Image.fromarray(np.clip(rgb * fade[:, :, None] * amount, 0, 255).astype(np.uint8), "RGB")


# ---------------------------------------------------------------------------
# The Club
# ---------------------------------------------------------------------------

NEON = (236, 86, 255)
NEON_DIM = (128, 40, 150)
AMBER = (255, 176, 74)
SILHOUETTE = (9, 5, 14)


def draw_club(w, h, wordmark=True):
    """A backlit doorway: warm light pouring out of the club, guests in silhouette."""
    unit = min(w, h)
    base = vertical_gradient(w, h, (28, 15, 42), (9, 4, 15))
    emissive = Image.new("RGB", (w, h), (0, 0, 0))
    bd = ImageDraw.Draw(base)
    ed = ImageDraw.Draw(emissive)

    floor_y = h * 0.745
    cx = w / 2

    bd.rectangle((0, floor_y, w, h), fill=(14, 7, 22))

    # Neon strips running down the side walls. A wide canvas gets extra pairs
    # further out, dimmer and shorter, so the room recedes instead of leaving
    # the sides empty.
    strip_w = unit * 0.030
    strips = [(unit * 0.405, 1.00, 1.00)]
    margin = (w - unit) / 2
    if margin > unit * 0.10:
        strips += [(unit * 0.405 + margin * 0.44, 0.55, 0.82),
                   (unit * 0.405 + margin * 0.82, 0.30, 0.66)]
    for offset, brightness, length in strips:
        for side in (-1, 1):
            x = cx + side * offset
            top = floor_y - (floor_y - h * 0.15) * length
            box = (x - strip_w * length / 2, top, x + strip_w * length / 2, floor_y - unit * 0.02)
            color = tuple(int(c * brightness) for c in NEON)
            bd.rounded_rectangle(box, radius=strip_w / 2, fill=color)
            ed.rounded_rectangle(box, radius=strip_w / 2, fill=color)

    # The doorway. Warm light fills it from the floor up, so whatever stands in
    # front of it reads as a silhouette.
    arch_w = unit * 0.46
    arch_h = unit * 0.58
    left, right = cx - arch_w / 2, cx + arch_w / 2
    top = floor_y - arch_h
    opening = Image.new("L", (w, h), 0)
    arch(ImageDraw.Draw(opening), left, right, floor_y, top, None, fill=255)
    mask = np.asarray(opening, float) / 255.0

    yy = np.mgrid[0:h, 0:w][0].astype(float)
    depth = np.clip((yy - top) / max(floor_y - top, 1e-6), 0.0, 1.0) ** 1.6
    core = radial_falloff(w, h, cx, floor_y - unit * 0.02, arch_w * 0.72)
    inside = (depth * 0.85 + core * 0.9)[:, :, None] * np.array(AMBER, float)
    filled = np.asarray(base, float) * (1 - mask[:, :, None]) + mask[:, :, None] * (
        np.array([10, 5, 12], float) + inside * 1.05
    )
    base = Image.fromarray(np.clip(filled, 0, 255).astype(np.uint8), "RGB")
    emissive = Image.fromarray(
        np.clip(np.asarray(emissive, float) + mask[:, :, None] * inside * 0.70, 0, 255).astype(np.uint8), "RGB"
    )
    bd, ed = ImageDraw.Draw(base), ImageDraw.Draw(emissive)

    # Neon tube around the opening.
    tube = max(2, int(unit * 0.026))
    arch(bd, left, right, floor_y, top, NEON, width=tube)
    arch(ed, left, right, floor_y, top, NEON, width=tube)

    # Light spilling onto the floor in front of the door, blurred so it has no rim.
    pool_shape = Image.new("L", (w, h), 0)
    ImageDraw.Draw(pool_shape).ellipse(
        (cx - arch_w * 0.95, floor_y - unit * 0.030, cx + arch_w * 0.95, floor_y + unit * 0.085), fill=255
    )
    pool = np.asarray(pool_shape.filter(ImageFilter.GaussianBlur(unit * 0.045)), float) / 255.0
    base = Image.fromarray(
        np.clip(np.asarray(base, float) + pool[:, :, None] * np.array([118, 60, 52], float), 0, 255)
        .astype(np.uint8), "RGB")
    emissive = Image.fromarray(
        np.clip(np.asarray(emissive, float) + pool[:, :, None] * np.array([110, 54, 44], float), 0, 255)
        .astype(np.uint8), "RGB")
    bd, ed = ImageDraw.Draw(base), ImageDraw.Draw(emissive)

    # The queue, standing in that light. They are tracked in `solid` so the
    # doorway's glow is blocked by them instead of shining through.
    solid = Image.new("L", (w, h), 0)
    sd = ImageDraw.Draw(solid)
    for x_off, scale, lean in ((-0.330, 0.86, 0.03), (-0.150, 1.06, -0.02),
                              (0.145, 0.98, 0.02), (0.325, 0.82, -0.03)):
        for target in (bd, sd):
            person(target, cx + unit * x_off, floor_y + unit * 0.030, unit * 0.345 * scale,
                   SILHOUETTE if target is bd else 255, lean)

    # Velvet-rope posts, catching the neon.
    for side in (-1, 1):
        x = cx + side * unit * 0.255
        post = (x - unit * 0.009, floor_y - unit * 0.100, x + unit * 0.009, floor_y + unit * 0.030)
        bd.rounded_rectangle(post, radius=unit * 0.009, fill=(96, 62, 34))
        sd.rounded_rectangle(post, radius=unit * 0.009, fill=255)
        cap = (x - unit * 0.015, floor_y - unit * 0.128, x + unit * 0.015, floor_y - unit * 0.098)
        bd.ellipse(cap, fill=AMBER)
        ed.ellipse(cap, fill=(130, 82, 34))

    # The rope slung between them, sagging in the middle.
    rope_y = floor_y - unit * 0.113
    sag = unit * 0.050
    span = unit * 0.255
    rope = [
        (cx + t * span, rope_y + sag * (1 - t * t))
        for t in np.linspace(-1.0, 1.0, 24)
    ]
    bd.line(rope, fill=(126, 44, 58), width=max(2, int(unit * 0.012)), joint="curve")

    occ = np.asarray(solid.filter(ImageFilter.GaussianBlur(unit * 0.004)), float) / 255.0
    lit = np.asarray(emissive, float) * (1.0 - occ)[:, :, None]
    emissive = Image.fromarray(lit.astype(np.uint8), "RGB")
    combined = Image.fromarray(
        np.clip(lit + np.asarray(reflect(emissive, floor_y, unit), float), 0, 255).astype(np.uint8),
        "RGB",
    )
    art = finish(base, combined, unit, bloom_strength=0.95, vignette_strength=0.55, seed=11, occlusion=occ)

    if wordmark:
        d = ImageDraw.Draw(art)
        fnt = font(unit * 0.100, FONT_CONDENSED_BLACK)
        y = h * 0.885
        tracking = unit * 0.022
        tracked_text(d, cx, y, "THE CLUB", fnt, (255, 244, 252), tracking)
        rule_half = tracked_width(d, "THE CLUB", fnt, tracking) / 2
        rule_y = y + unit * 0.062
        d.line((cx - rule_half, rule_y, cx + rule_half, rule_y), fill=NEON_DIM, width=max(1, int(unit * 0.005)))

    rounded_border(
        ImageDraw.Draw(art), w, h,
        inset=unit * 0.022, radius=unit * 0.075, color=NEON_DIM, width=max(1, int(unit * 0.008)),
    )
    return art


# ---------------------------------------------------------------------------
# RoastEngine Shader API
# ---------------------------------------------------------------------------

ORANGE = np.array([255, 120, 22], float)
ACCENT = (255, 138, 38)
ACCENT_DIM = (150, 78, 24)


def draw_shader(w, h, wordmark=True, labels=False):
    """A sphere split down the middle: raw on the left, the shader pass on the right."""
    unit = min(w, h)
    cx, cy = w / 2, h * 0.430
    radius = unit * 0.250

    yy, xx = np.mgrid[0:h, 0:w].astype(float)
    nx = (xx - cx) / radius
    ny = (yy - cy) / radius
    depth = 1.0 - nx * nx - ny * ny
    on_sphere = depth > 0.0
    nz = np.sqrt(np.clip(depth, 0.0, None))
    right = xx >= cx

    light = np.array([-0.42, -0.62, 0.66])
    light /= np.linalg.norm(light)
    ndl = np.clip(nx * light[0] + ny * light[1] + nz * light[2], 0.0, 1.0)
    spec = ndl ** 64
    rim = np.clip(1.0 - nz, 0.0, 1.0) ** 3

    # Background: flat and grey where the shader is off, graded and warm where it is on.
    base = np.zeros((h, w, 3), float)
    base += np.array([26, 26, 30], float)
    warm = radial_falloff(w, h, cx, cy, unit * 0.95)
    base += right[:, :, None] * warm[:, :, None] * np.array([48, 26, 10], float)
    base = np.where(right[:, :, None], base * 0.62, base * 0.78)

    emissive = np.zeros((h, w, 3), float)

    # Left: plain diffuse grey, no highlight, no glow - the engine with shaders off.
    raw = np.array([104, 104, 112], float) * (0.18 + 0.74 * ndl)[:, :, None]
    # Right: warm albedo, a specular hit and a glowing rim, all of it feeding bloom.
    lit = ORANGE * (0.10 + 0.62 * ndl)[:, :, None]
    lit += spec[:, :, None] * np.array([255, 226, 190], float) * 0.60
    lit += rim[:, :, None] * ORANGE * 0.55

    sphere_mask = on_sphere[:, :, None]
    base = np.where(sphere_mask & right[:, :, None], lit, base)
    base = np.where(sphere_mask & ~right[:, :, None], raw, base)

    glow = rim[:, :, None] * ORANGE * 1.10 + spec[:, :, None] * np.array([255, 214, 172], float) * 0.85
    glow += (ndl ** 2)[:, :, None] * ORANGE * 0.16
    emissive = np.where(sphere_mask & right[:, :, None], glow, emissive)

    base_img = Image.fromarray(np.clip(base, 0, 255).astype(np.uint8), "RGB")
    emissive_img = Image.fromarray(np.clip(emissive, 0, 255).astype(np.uint8), "RGB")
    bd = ImageDraw.Draw(base_img)
    ed = ImageDraw.Draw(emissive_img)

    # Ambient occlusion: the contact shadow the shader pass adds.
    shadow = Image.new("L", (w, h), 0)
    ImageDraw.Draw(shadow).ellipse(
        (cx - radius * 1.05, cy + radius * 0.74, cx + radius * 1.05, cy + radius * 1.16), fill=180
    )
    shadow = shadow.filter(ImageFilter.GaussianBlur(unit * 0.030))
    occluded = np.asarray(base_img, float) * (1.0 - np.asarray(shadow, float)[:, :, None] / 255.0 * 0.85)
    base_img = Image.fromarray(np.clip(occluded, 0, 255).astype(np.uint8), "RGB")
    bd = ImageDraw.Draw(base_img)

    # The split line, with a handle where it crosses the sphere.
    line_w = max(2, int(unit * 0.008))
    split_top, split_bottom = h * 0.115, h * 0.735
    bd.line((cx, split_top, cx, split_bottom), fill=(246, 238, 232), width=line_w)
    ed.line((cx, split_top, cx, split_bottom), fill=(120, 112, 106), width=line_w)
    handle_r = unit * 0.026
    bd.ellipse((cx - handle_r, cy - handle_r, cx + handle_r, cy + handle_r), fill=(246, 238, 232))
    ed.ellipse((cx - handle_r, cy - handle_r, cx + handle_r, cy + handle_r), fill=(150, 140, 132))

    occ = np.zeros((h, w), float)
    occ[on_sphere & ~right] = 1.0
    occ = np.asarray(Image.fromarray((occ * 255).astype(np.uint8), "L")
                     .filter(ImageFilter.GaussianBlur(unit * 0.010)), float) / 255.0
    art = finish(base_img, emissive_img, unit, bloom_strength=1.0, vignette_strength=0.55, seed=23,
                 occlusion=occ * 0.85)
    d = ImageDraw.Draw(art)

    if labels:
        small = font(unit * 0.036)
        tracking = unit * 0.010
        tracked_text(d, cx - unit * 0.46, split_top + unit * 0.03, "RAW", small, (150, 150, 158), tracking)
        tracked_text(d, cx + unit * 0.46, split_top + unit * 0.03, "SHADED", small, ACCENT, tracking)

    if wordmark:
        title = font(unit * 0.092, FONT_CONDENSED_BLACK)
        kicker = font(unit * 0.034)
        tracking = unit * 0.018
        title_y = h * 0.875
        tracked_text(d, cx, h * 0.810, "ROASTENGINE", kicker, (162, 154, 150), unit * 0.024)
        tracked_text(d, cx, title_y, "SHADER API", title, (255, 244, 236), tracking)
        rule_half = tracked_width(d, "SHADER API", title, tracking) / 2
        rule_y = title_y + unit * 0.058
        d.line((cx - rule_half, rule_y, cx + rule_half, rule_y), fill=ACCENT_DIM, width=max(1, int(unit * 0.005)))

    rounded_border(
        d, w, h,
        inset=unit * 0.022, radius=unit * 0.075, color=ACCENT_DIM, width=max(1, int(unit * 0.008)),
    )
    return art


# ---------------------------------------------------------------------------
# InputEdit API
# ---------------------------------------------------------------------------

WIRE = np.array([255, 150, 52], float)
WIRE_DIM = (150, 82, 26)
NODE = (52, 50, 54)


def draw_inputedit(w, h, wordmark=True):
    """Three devices feeding one set of actions: what the API actually does."""
    unit = min(w, h)
    base = vertical_gradient(w, h, (30, 24, 20), (8, 6, 6))
    glow = radial_falloff(w, h, w / 2, h * 0.40, unit * 0.80)
    base = Image.fromarray(
        np.clip(np.asarray(base, float) + glow[:, :, None] * np.array([34, 18, 6], float), 0, 255)
        .astype(np.uint8), "RGB")
    emissive = Image.new("RGB", (w, h), (0, 0, 0))
    bd, ed = ImageDraw.Draw(base), ImageDraw.Draw(emissive)

    cx, cy = w / 2, h * 0.435
    left_x = cx - unit * 0.245
    right_x = cx + unit * 0.225
    spacing = unit * 0.150
    rows = (-1, 0, 1)

    # Left: three input devices. Right: the actions they all end up driving.
    for row in rows:
        y = cy + row * spacing
        bd.rounded_rectangle((left_x - unit * 0.085, y - unit * 0.048,
                              left_x + unit * 0.085, y + unit * 0.048),
                             radius=unit * 0.022, fill=NODE)
        bd.rounded_rectangle((right_x - unit * 0.095, y - unit * 0.040,
                              right_x + unit * 0.095, y + unit * 0.040),
                             radius=unit * 0.040, fill=(38, 30, 24))
        bd.rounded_rectangle((right_x - unit * 0.095, y - unit * 0.040,
                              right_x + unit * 0.095, y + unit * 0.040),
                             radius=unit * 0.040, outline=tuple(int(c) for c in WIRE),
                             width=max(2, int(unit * 0.009)))
        ed.rounded_rectangle((right_x - unit * 0.095, y - unit * 0.040,
                              right_x + unit * 0.095, y + unit * 0.040),
                             radius=unit * 0.040, outline=tuple(int(c * 0.8) for c in WIRE),
                             width=max(2, int(unit * 0.009)))

    # Device faces: a stick, a d-pad, a pair of keys - three different shapes of input.
    stick_y = cy - spacing
    bd.ellipse((left_x - unit * 0.030, stick_y - unit * 0.030,
                left_x + unit * 0.030, stick_y + unit * 0.030), fill=(24, 20, 18))
    bd.ellipse((left_x - unit * 0.030, stick_y - unit * 0.030,
                left_x + unit * 0.030, stick_y + unit * 0.030),
               outline=tuple(int(c) for c in WIRE), width=max(2, int(unit * 0.008)))
    ed.ellipse((left_x - unit * 0.030, stick_y - unit * 0.030,
                left_x + unit * 0.030, stick_y + unit * 0.030),
               outline=tuple(int(c * 0.7) for c in WIRE), width=max(2, int(unit * 0.008)))

    arm, thick = unit * 0.040, unit * 0.016
    bd.rounded_rectangle((left_x - arm, cy - thick, left_x + arm, cy + thick),
                         radius=thick * 0.4, fill=(24, 20, 18))
    bd.rounded_rectangle((left_x - thick, cy - arm, left_x + thick, cy + arm),
                         radius=thick * 0.4, fill=(24, 20, 18))

    key_y = cy + spacing
    for i, dx in enumerate((-unit * 0.038, 0, unit * 0.038)):
        bd.rounded_rectangle((left_x + dx - unit * 0.016, key_y - unit * 0.018,
                              left_x + dx + unit * 0.016, key_y + unit * 0.018),
                             radius=unit * 0.006, fill=(24, 20, 18))

    # The wiring: every device fans into every action, through the API in the middle.
    hub_x = cx - unit * 0.010
    wire_w = max(2, int(unit * 0.010))
    for row in rows:
        y = cy + row * spacing
        bd.line((left_x + unit * 0.085, y, hub_x, cy), fill=WIRE_DIM, width=wire_w)
        ed.line((left_x + unit * 0.085, y, hub_x, cy), fill=WIRE_DIM, width=wire_w)
        bd.line((hub_x, cy, right_x - unit * 0.095, y), fill=WIRE_DIM, width=wire_w)
        ed.line((hub_x, cy, right_x - unit * 0.095, y), fill=WIRE_DIM, width=wire_w)

    hub = unit * 0.040
    bd.ellipse((hub_x - hub, cy - hub, hub_x + hub, cy + hub), fill=tuple(int(c) for c in WIRE))
    ed.ellipse((hub_x - hub, cy - hub, hub_x + hub, cy + hub), fill=tuple(int(c) for c in WIRE))

    art = finish(base, emissive, unit, bloom_strength=0.85, vignette_strength=0.55, seed=53)

    if wordmark:
        d = ImageDraw.Draw(art)
        title = font(unit * 0.092, FONT_CONDENSED_BLACK)
        kicker = font(unit * 0.034)
        tracking = unit * 0.018
        title_y = h * 0.875
        tracked_text(d, cx, h * 0.810, "ROASTENGINE", kicker, (166, 152, 142), unit * 0.024)
        tracked_text(d, cx, title_y, "INPUTEDIT API", title, (255, 246, 238), tracking)
        rule_half = tracked_width(d, "INPUTEDIT API", title, tracking) / 2
        rule_y = title_y + unit * 0.058
        d.line((cx - rule_half, rule_y, cx + rule_half, rule_y), fill=WIRE_DIM, width=max(1, int(unit * 0.005)))

    rounded_border(
        ImageDraw.Draw(art), w, h,
        inset=unit * 0.022, radius=unit * 0.075, color=WIRE_DIM, width=max(1, int(unit * 0.008)),
    )
    return art


# ---------------------------------------------------------------------------
# Xbox Controller (an InputEdit API)
# ---------------------------------------------------------------------------

GREEN = (78, 222, 96)
GREEN_DIM = (34, 118, 48)
SHELL = (46, 48, 52)
SHELL_DARK = (26, 27, 30)


def draw_xbox(w, h, wordmark=True):
    """The pad itself, lit from above, with the sticks and guide ring glowing."""
    unit = min(w, h)
    base = vertical_gradient(w, h, (22, 30, 24), (7, 9, 8))
    warm = radial_falloff(w, h, w / 2, h * 0.38, unit * 0.75)
    base = Image.fromarray(
        np.clip(np.asarray(base, float) + warm[:, :, None] * np.array([10, 34, 14], float), 0, 255)
        .astype(np.uint8), "RGB")
    emissive = Image.new("RGB", (w, h), (0, 0, 0))

    cx, cy = w / 2, h * 0.400
    bd = ImageDraw.Draw(base)

    # Bumpers first, so the shell covers their lower half and they read as behind it.
    for side in (-1, 1):
        x = cx + side * unit * 0.245
        bd.rounded_rectangle((x - unit * 0.075, cy - unit * 0.170, x + unit * 0.075, cy - unit * 0.075),
                             radius=unit * 0.030, fill=SHELL_DARK)

    # Body: two angled grips under a wide shell with rounded shoulders.
    for side, angle in ((-1, 18), (1, -18)):
        rotated_rect(base, cx + side * unit * 0.225, cy + unit * 0.155,
                     unit * 0.170, unit * 0.300, unit * 0.080, angle, SHELL)
    bd = ImageDraw.Draw(base)
    for side in (-1, 1):
        x = cx + side * unit * 0.255
        bd.ellipse((x - unit * 0.130, cy - unit * 0.130, x + unit * 0.130, cy + unit * 0.130), fill=SHELL)
    bd.rounded_rectangle(
        (cx - unit * 0.290, cy - unit * 0.135, cx + unit * 0.290, cy + unit * 0.120),
        radius=unit * 0.085, fill=SHELL,
    )

    ed = ImageDraw.Draw(emissive)

    def stick(x, y):
        outer = unit * 0.070
        bd.ellipse((x - outer, y - outer, x + outer, y + outer), fill=SHELL_DARK)
        bd.ellipse((x - outer * 0.60, y - outer * 0.60, x + outer * 0.60, y + outer * 0.60), fill=(66, 68, 72))
        # The lit ring is what the pad looks like with the game running.
        ring = max(2, int(unit * 0.010))
        bd.ellipse((x - outer, y - outer, x + outer, y + outer), outline=GREEN, width=ring)
        ed.ellipse((x - outer, y - outer, x + outer, y + outer), outline=GREEN, width=ring)

    # Xbox layout: stick above d-pad on the left, buttons above stick on the right.
    stick(cx - unit * 0.180, cy - unit * 0.045)
    stick(cx + unit * 0.075, cy + unit * 0.070)

    fx, fy = cx + unit * 0.195, cy - unit * 0.040
    r_btn = unit * 0.023
    for dx, dy in ((0, -unit * 0.050), (unit * 0.050, 0), (0, unit * 0.050), (-unit * 0.050, 0)):
        bd.ellipse((fx + dx - r_btn, fy + dy - r_btn, fx + dx + r_btn, fy + dy + r_btn), fill=SHELL_DARK)

    dx_, dy_ = cx - unit * 0.075, cy + unit * 0.075
    arm, thick = unit * 0.044, unit * 0.019
    bd.rounded_rectangle((dx_ - arm, dy_ - thick, dx_ + arm, dy_ + thick), radius=thick * 0.4, fill=SHELL_DARK)
    bd.rounded_rectangle((dx_ - thick, dy_ - arm, dx_ + thick, dy_ + arm), radius=thick * 0.4, fill=SHELL_DARK)

    # Guide ring in the middle, the brightest thing on the pad.
    guide = unit * 0.028
    gy = cy - unit * 0.090
    bd.ellipse((cx - guide, gy - guide, cx + guide, gy + guide), fill=SHELL_DARK)
    bd.ellipse((cx - guide, gy - guide, cx + guide, gy + guide), outline=GREEN, width=max(2, int(unit * 0.009)))
    ed.ellipse((cx - guide, gy - guide, cx + guide, gy + guide), outline=GREEN, width=max(2, int(unit * 0.009)))

    art = finish(base, emissive, unit, bloom_strength=0.9, vignette_strength=0.55, seed=41)

    if wordmark:
        d = ImageDraw.Draw(art)
        title = font(unit * 0.100, FONT_CONDENSED_BLACK)
        kicker = font(unit * 0.034)
        tracking = unit * 0.024
        title_y = h * 0.875
        tracked_text(d, cx, h * 0.810, "INPUTEDIT API", kicker, (150, 162, 152), unit * 0.024)
        tracked_text(d, cx, title_y, "XBOX", title, (244, 255, 246), tracking)
        rule_half = tracked_width(d, "XBOX", title, tracking) / 2
        rule_y = title_y + unit * 0.058
        d.line((cx - rule_half, rule_y, cx + rule_half, rule_y), fill=GREEN_DIM, width=max(1, int(unit * 0.005)))

    rounded_border(
        ImageDraw.Draw(art), w, h,
        inset=unit * 0.022, radius=unit * 0.075, color=GREEN_DIM, width=max(1, int(unit * 0.008)),
    )
    return art


# ---------------------------------------------------------------------------
# Header strips: the wordmark alone, like branding/roastengine-header-400x100.png
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# Bypass API
# ---------------------------------------------------------------------------

CYAN = (86, 214, 255)
CYAN_DIM = (30, 98, 132)
WALL = (34, 38, 46)
WALL_DARK = (22, 25, 31)


def draw_bypass(w, h, wordmark=True):
    """A beam going straight through a solid wall: what the API lets a hack client do."""
    unit = min(w, h)
    base = vertical_gradient(w, h, (18, 26, 34), (6, 8, 12))
    lit = radial_falloff(w, h, w / 2, h * 0.42, unit * 0.85)
    base = Image.fromarray(
        np.clip(np.asarray(base, float) + lit[:, :, None] * np.array([8, 28, 40], float), 0, 255)
        .astype(np.uint8), "RGB")
    emissive = Image.new("RGB", (w, h), (0, 0, 0))
    bd, ed = ImageDraw.Draw(base), ImageDraw.Draw(emissive)

    cx, cy = w / 2, h * 0.44
    wall_half = unit * 0.075
    wall_top = cy - unit * 0.30
    wall_bottom = cy + unit * 0.30

    # The wall, with a lit edge facing the beam so it reads as solid.
    bd.rectangle((cx - wall_half, wall_top, cx + wall_half, wall_bottom), fill=WALL)
    bd.rectangle((cx - wall_half, wall_top, cx - wall_half + unit * 0.014, wall_bottom), fill=WALL_DARK)
    for i in range(6):
        # Brick courses: a wall that is clearly a wall at icon size.
        y = wall_top + (i + 1) * (wall_bottom - wall_top) / 7
        bd.line((cx - wall_half, y, cx + wall_half, y), fill=WALL_DARK, width=max(1, int(unit * 0.006)))

    # The beam: solid outside the wall, dimmed where it passes through it.
    beam_h = unit * 0.030
    dash = unit * 0.055
    x = cx - unit * 0.40
    while x < cx + unit * 0.40:
        inside = cx - wall_half < x < cx + wall_half
        colour = tuple(int(c * (0.35 if inside else 1.0)) for c in CYAN)
        box = (x, cy - beam_h / 2, min(x + dash * 0.62, cx + unit * 0.40), cy + beam_h / 2)
        bd.rounded_rectangle(box, radius=beam_h / 2, fill=colour)
        ed.rounded_rectangle(box, radius=beam_h / 2, fill=colour)
        x += dash

    # An arrowhead on the far side: it got through.
    tip = cx + unit * 0.42
    head = [(tip, cy), (tip - unit * 0.075, cy - unit * 0.062), (tip - unit * 0.075, cy + unit * 0.062)]
    bd.polygon(head, fill=CYAN)
    ed.polygon(head, fill=CYAN)

    # Where the beam meets the wall, a bright entry point.
    for side in (-1, 1):
        px = cx + side * wall_half
        bd.ellipse((px - beam_h, cy - beam_h, px + beam_h, cy + beam_h), fill=CYAN)
        ed.ellipse((px - beam_h, cy - beam_h, px + beam_h, cy + beam_h), fill=CYAN)

    art = finish(base, emissive, unit, bloom_strength=0.95, vignette_strength=0.55, seed=61)

    if wordmark:
        d = ImageDraw.Draw(art)
        title = font(unit * 0.098, FONT_CONDENSED_BLACK)
        kicker = font(unit * 0.034)
        tracking = unit * 0.020
        title_y = h * 0.875
        tracked_text(d, cx, h * 0.810, "ROASTENGINE", kicker, (150, 168, 178), unit * 0.024)
        tracked_text(d, cx, title_y, "BYPASS API", title, (238, 248, 255), tracking)
        rule_half = tracked_width(d, "BYPASS API", title, tracking) / 2
        rule_y = title_y + unit * 0.058
        d.line((cx - rule_half, rule_y, cx + rule_half, rule_y), fill=CYAN_DIM, width=max(1, int(unit * 0.005)))

    rounded_border(
        ImageDraw.Draw(art), w, h,
        inset=unit * 0.022, radius=unit * 0.075, color=CYAN_DIM, width=max(1, int(unit * 0.008)),
    )
    return art


# ---------------------------------------------------------------------------
# Roast Hack (runs on the Bypass API)
# ---------------------------------------------------------------------------

VIOLET = (176, 128, 255)
VIOLET_DIM = (84, 54, 138)
PANEL = (28, 24, 38)


def draw_hack(w, h, wordmark=True):
    """The menu, over a player boxed by the ESP - the two things the client is for."""
    unit = min(w, h)
    base = vertical_gradient(w, h, (26, 20, 36), (7, 6, 11))
    lit = radial_falloff(w, h, w * 0.62, h * 0.40, unit * 0.85)
    base = Image.fromarray(
        np.clip(np.asarray(base, float) + lit[:, :, None] * np.array([26, 12, 42], float), 0, 255)
        .astype(np.uint8), "RGB")
    emissive = Image.new("RGB", (w, h), (0, 0, 0))
    bd, ed = ImageDraw.Draw(base), ImageDraw.Draw(emissive)

    # Behind everything: a player, seen through the wall, with the ESP box around them.
    figure_x = w * 0.255
    figure_base = h * 0.66
    figure_h = unit * 0.40
    person(bd, figure_x, figure_base, figure_h, (16, 13, 24))
    box = (figure_x - figure_h * 0.30, figure_base - figure_h * 1.06,
           figure_x + figure_h * 0.30, figure_base + figure_h * 0.04)
    line_w = max(2, int(unit * 0.008))
    bd.rectangle(box, outline=VIOLET, width=line_w)
    ed.rectangle(box, outline=VIOLET, width=line_w)

    # The menu itself: a header and a few rows, one switch on.
    panel = (w * 0.42, h * 0.20, w * 0.80, h * 0.70)
    radius = unit * 0.035
    bd.rounded_rectangle(panel, radius=radius, fill=PANEL)
    bd.rounded_rectangle(panel, radius=radius, outline=VIOLET, width=line_w)
    ed.rounded_rectangle(panel, radius=radius, outline=tuple(int(c * 0.85) for c in VIOLET), width=line_w)

    header_y = panel[1] + unit * 0.075
    bd.line((panel[0] + unit * 0.045, header_y, panel[2] - unit * 0.045, header_y),
            fill=VIOLET_DIM, width=max(1, int(unit * 0.006)))
    bd.rounded_rectangle((panel[0] + unit * 0.045, panel[1] + unit * 0.030,
                          panel[0] + unit * 0.150, panel[1] + unit * 0.052),
                         radius=unit * 0.011, fill=VIOLET)
    ed.rounded_rectangle((panel[0] + unit * 0.045, panel[1] + unit * 0.030,
                          panel[0] + unit * 0.150, panel[1] + unit * 0.052),
                         radius=unit * 0.011, fill=VIOLET)

    rows = 4
    row_gap = (panel[3] - header_y - unit * 0.060) / rows
    for row in range(rows):
        y = header_y + row_gap * (row + 0.6)
        bd.rounded_rectangle((panel[0] + unit * 0.045, y - unit * 0.016,
                              panel[0] + unit * 0.185, y + unit * 0.016),
                             radius=unit * 0.012, fill=(52, 44, 70))
        # The switch: the first two are on and glow, the rest are off.
        on = row < 2
        sx = panel[2] - unit * 0.105
        track = (sx, y - unit * 0.020, sx + unit * 0.065, y + unit * 0.020)
        bd.rounded_rectangle(track, radius=unit * 0.020, fill=VIOLET if on else (46, 40, 60))
        if on:
            ed.rounded_rectangle(track, radius=unit * 0.020, fill=VIOLET)
        knob_x = track[2] - unit * 0.018 if on else track[0] + unit * 0.018
        bd.ellipse((knob_x - unit * 0.015, y - unit * 0.015,
                    knob_x + unit * 0.015, y + unit * 0.015), fill=(246, 242, 255))

    art = finish(base, emissive, unit, bloom_strength=0.85, vignette_strength=0.55, seed=67)

    if wordmark:
        d = ImageDraw.Draw(art)
        title = font(unit * 0.100, FONT_CONDENSED_BLACK)
        kicker = font(unit * 0.034)
        tracking = unit * 0.020
        cx = w / 2
        title_y = h * 0.875
        tracked_text(d, cx, h * 0.810, "NEEDS THE BYPASS API", kicker, (162, 146, 180), unit * 0.022)
        tracked_text(d, cx, title_y, "ROAST HACK", title, (246, 240, 255), tracking)
        rule_half = tracked_width(d, "ROAST HACK", title, tracking) / 2
        rule_y = title_y + unit * 0.058
        d.line((cx - rule_half, rule_y, cx + rule_half, rule_y), fill=VIOLET_DIM,
               width=max(1, int(unit * 0.005)))

    rounded_border(
        ImageDraw.Draw(art), w, h,
        inset=unit * 0.022, radius=unit * 0.075, color=VIOLET_DIM, width=max(1, int(unit * 0.008)),
    )
    return art


def draw_header(w, h, title, kicker, accent, accent_dim, glow, seed):
    unit = h
    base = vertical_gradient(w, h, (20, 15, 22), (5, 3, 7))
    lit = np.asarray(base, float) + radial_falloff(w, h, w / 2, h * 0.55, w * 0.60)[:, :, None] * np.array(glow, float) * 0.45
    base = Image.fromarray(np.clip(lit, 0, 255).astype(np.uint8), "RGB")

    emissive = Image.new("RGB", (w, h), (0, 0, 0))
    ed = ImageDraw.Draw(emissive)
    bd = ImageDraw.Draw(base)

    title_font = font(unit * 0.46, FONT_CONDENSED_BLACK)
    kicker_font = font(unit * 0.15)
    tracking = unit * 0.075

    tracked_text(bd, w / 2, h * 0.42, title, title_font, (255, 246, 240), tracking)
    tracked_text(ed, w / 2, h * 0.42, title, title_font, (30, 20, 15), tracking)
    tracked_text(bd, w / 2, h * 0.745, kicker, kicker_font, accent, unit * 0.10)

    rule_half = tracked_width(bd, title, title_font, tracking) / 2
    for side in (-1, 1):
        x0 = w / 2 + side * (rule_half + unit * 0.07)
        x1 = w / 2 + side * (rule_half + unit * 0.34)
        bd.line((x0, h * 0.42, x1, h * 0.42), fill=accent_dim, width=max(1, int(unit * 0.03)))

    return finish(base, emissive, w, bloom_strength=0.45, vignette_strength=0.30, grain_amount=0.004, seed=seed)


# ---------------------------------------------------------------------------

def render(name, draw_fn, size):
    w, h = size
    art = draw_fn(w * SUPERSAMPLE, h * SUPERSAMPLE)
    art = art.resize((w, h), Image.LANCZOS)
    path = os.path.join(OUT_DIR, name)
    art.save(path)
    print(f"wrote {os.path.relpath(path, PROJECT)}  ({w}x{h})")


def write_mod_icon(draw_fn, folders, size=256):
    """Also drops the square icon into the mod's own folder.

    The engine reads `icon.png` from the root of a mod folder and shows it in the Mod Browser,
    so unlike the branding/ artwork this one has to ship inside the mod's zip.
    """
    art = draw_fn(size * SUPERSAMPLE, size * SUPERSAMPLE).resize((size, size), Image.LANCZOS)
    for folder in folders:
        directory = os.path.join(PROJECT, folder)
        if not os.path.isdir(directory):
            print(f"skipped {folder}/icon.png  (no such folder)")
            continue
        path = os.path.join(directory, "icon.png")
        art.save(path)
        print(f"wrote {os.path.relpath(path, PROJECT)}  ({size}x{size})")


# --- PlaceHolder API: the bag -------------------------------------------------------

TEAL = (92, 226, 200)
TEAL_DIM = (32, 116, 102)
SLOT = (40, 46, 52)
SLOT_DARK = (24, 28, 33)


def draw_placeholder(w, h, wordmark=True):
    """A row of slots with one lit and holding something: five things, one hand."""
    unit = min(w, h)
    base = vertical_gradient(w, h, (16, 28, 30), (5, 9, 11))
    lit = radial_falloff(w, h, w / 2, h * 0.46, unit * 0.80)
    base = Image.fromarray(
        np.clip(np.asarray(base, float) + lit[:, :, None] * np.array([10, 34, 30], float), 0, 255)
        .astype(np.uint8), "RGB")
    emissive = Image.new("RGB", (w, h), (0, 0, 0))
    bd, ed = ImageDraw.Draw(base), ImageDraw.Draw(emissive)

    slots = 5
    chosen = 2
    size = unit * 0.155
    gap = unit * 0.028
    total = slots * size + (slots - 1) * gap
    x0 = w / 2 - total / 2
    y0 = h * 0.50 - size / 2
    radius = unit * 0.022

    for i in range(slots):
        x = x0 + i * (size + gap)
        box = (x, y0, x + size, y0 + size)
        bd.rounded_rectangle(box, radius=radius, fill=SLOT if i == chosen else SLOT_DARK)
        if i == chosen:
            # The slot in the player's hand: outlined, and glowing.
            bd.rounded_rectangle(box, radius=radius, outline=TEAL, width=max(1, int(unit * 0.010)))
            ed.rounded_rectangle(box, radius=radius, outline=TEAL, width=max(1, int(unit * 0.010)))
        else:
            bd.rounded_rectangle(box, radius=radius, outline=(52, 60, 66),
                                 width=max(1, int(unit * 0.005)))

    # In the lit slot, the item: a bottle, held by its base.
    cx = x0 + chosen * (size + gap) + size / 2
    body_w = size * 0.30
    body_top = y0 + size * 0.34
    body_bottom = y0 + size * 0.80
    bd.rounded_rectangle((cx - body_w, body_top, cx + body_w, body_bottom),
                         radius=body_w * 0.5, fill=TEAL)
    ed.rounded_rectangle((cx - body_w, body_top, cx + body_w, body_bottom),
                         radius=body_w * 0.5, fill=TEAL)
    neck_w = size * 0.11
    bd.rectangle((cx - neck_w, y0 + size * 0.18, cx + neck_w, body_top + size * 0.04), fill=TEAL)
    ed.rectangle((cx - neck_w, y0 + size * 0.18, cx + neck_w, body_top + size * 0.04), fill=TEAL)

    # The fist closed around its base: the item's bottom sits inside the hand.
    hand_w = size * 0.62
    hand_h = size * 0.30
    hand_y = body_bottom - hand_h * 0.55
    bd.rounded_rectangle((cx - hand_w, hand_y, cx + hand_w, hand_y + hand_h),
                         radius=hand_h * 0.42, fill=(228, 186, 148))
    for knuckle in range(3):
        kx = cx - hand_w * 0.52 + knuckle * hand_w * 0.52
        bd.line((kx, hand_y + hand_h * 0.30, kx, hand_y + hand_h * 0.82),
                fill=(196, 152, 116), width=max(1, int(unit * 0.006)))

    # The number under each slot, so the row reads as a hotbar.
    label = font(unit * 0.042, FONT_BOLD)
    for i in range(slots):
        x = x0 + i * (size + gap) + size / 2
        text = str(i + 1)
        half = bd.textlength(text, font=label) / 2
        bd.text((x - half, y0 + size + unit * 0.030), text, font=label,
                fill=TEAL if i == chosen else (96, 108, 114))

    art = finish(base, emissive, unit, bloom_strength=0.8, vignette_strength=0.5, seed=73)

    if wordmark:
        d = ImageDraw.Draw(art)
        title = font(unit * 0.100, FONT_CONDENSED_BLACK)
        kicker = font(unit * 0.034)
        tracking = unit * 0.020
        cx = w / 2
        title_y = h * 0.875
        tracked_text(d, cx, h * 0.810, "FIVE SLOTS, TWO HANDS", kicker, (146, 172, 168), unit * 0.022)
        tracked_text(d, cx, title_y, "PLACEHOLDER", title, (238, 250, 248), tracking)
        rule_half = tracked_width(d, "PLACEHOLDER", title, tracking) / 2
        rule_y = title_y + unit * 0.058
        d.line((cx - rule_half, rule_y, cx + rule_half, rule_y), fill=TEAL_DIM,
               width=max(1, int(unit * 0.005)))

    rounded_border(
        ImageDraw.Draw(art), w, h,
        inset=unit * 0.022, radius=unit * 0.075, color=TEAL_DIM, width=max(1, int(unit * 0.008)),
    )
    return art


# --- Gun Arena: two teams, one crosshair ---------------------------------------------------------

ARENA_RED = (232, 86, 76)
ARENA_BLUE = (80, 146, 232)
ARENA_GOLD = (242, 196, 96)
ARENA_GOLD_DIM = (130, 100, 40)


def draw_arena(w, h, wordmark=True):
    """Red on the left, blue on the right, a crosshair where they meet."""
    unit = min(w, h)
    base = Image.new("RGB", (w, h), (0, 0, 0))
    left = vertical_gradient(w, h, (54, 16, 14), (14, 4, 4))
    right = vertical_gradient(w, h, (14, 26, 54), (4, 7, 16))
    arr = np.asarray(left, float).copy()
    arr[:, w // 2:] = np.asarray(right, float)[:, w // 2:]
    lit = radial_falloff(w, h, w / 2, h * 0.44, unit * 0.7)
    arr = np.clip(arr + lit[:, :, None] * np.array([30, 26, 20], float), 0, 255)
    base = Image.fromarray(arr.astype(np.uint8), "RGB")
    emissive = Image.new("RGB", (w, h), (0, 0, 0))
    bd, ed = ImageDraw.Draw(base), ImageDraw.Draw(emissive)

    cx, cy = w / 2, h * 0.44
    # A glowing seam down the middle where the two sides meet.
    seam = unit * 0.006
    ed.rectangle((cx - seam, 0, cx + seam, h), fill=(90, 70, 40))

    # The crosshair: a ring, four ticks and a dot.
    ring = unit * 0.20
    width = max(2, int(unit * 0.018))
    for d in (bd, ed):
        d.ellipse((cx - ring, cy - ring, cx + ring, cy + ring), outline=ARENA_GOLD, width=width)
        gap, reach = unit * 0.07, unit * 0.30
        d.line((cx - reach, cy, cx - gap, cy), fill=ARENA_GOLD, width=width)
        d.line((cx + gap, cy, cx + reach, cy), fill=ARENA_GOLD, width=width)
        d.line((cx, cy - reach, cx, cy - gap), fill=ARENA_GOLD, width=width)
        d.line((cx, cy + gap, cx, cy + reach), fill=ARENA_GOLD, width=width)
        dot = unit * 0.018
        d.ellipse((cx - dot, cy - dot, cx + dot, cy + dot), fill=ARENA_GOLD)

    # A score either side: the thing the match is about.
    score = font(unit * 0.13, FONT_CONDENSED_BLACK)
    for text, x, colour in (("7", cx - unit * 0.36, ARENA_RED), ("9", cx + unit * 0.36, ARENA_BLUE)):
        half = bd.textlength(text, font=score) / 2
        bd.text((x - half, cy - unit * 0.08), text, font=score, fill=colour)
        ed.text((x - half, cy - unit * 0.08), text, font=score, fill=tuple(c // 2 for c in colour))

    art = finish(base, emissive, unit, bloom_strength=0.8, vignette_strength=0.55, seed=79)

    if wordmark:
        d = ImageDraw.Draw(art)
        title = font(unit * 0.100, FONT_CONDENSED_BLACK)
        kicker = font(unit * 0.034)
        tracking = unit * 0.020
        title_y = h * 0.875
        tracked_text(d, cx, h * 0.810, "TWO TEAMS, SIX GUNS", kicker, (186, 170, 150), unit * 0.022)
        tracked_text(d, cx, title_y, "GUN ARENA", title, (250, 244, 232), tracking)
        rule_half = tracked_width(d, "GUN ARENA", title, tracking) / 2
        rule_y = title_y + unit * 0.058
        d.line((cx - rule_half, rule_y, cx + rule_half, rule_y), fill=ARENA_GOLD_DIM,
               width=max(1, int(unit * 0.005)))

    rounded_border(
        ImageDraw.Draw(art), w, h,
        inset=unit * 0.022, radius=unit * 0.075, color=ARENA_GOLD_DIM, width=max(1, int(unit * 0.008)),
    )
    return art


# --- The Backrooms: a yellow room that goes on, and something at the end of it ------------------

BACK_YELLOW = (214, 198, 96)
BACK_YELLOW_DIM = (110, 98, 42)
BACK_CARPET = (108, 96, 50)


def draw_backrooms(w, h, wordmark=True):
    """One-point perspective down a yellow corridor, a hanging light, and a figure in it."""
    unit = min(w, h)
    cx, horizon = w / 2, h * 0.46
    base = Image.new("RGB", (w, h), (0, 0, 0))
    emissive = Image.new("RGB", (w, h), (0, 0, 0))
    bd, ed = ImageDraw.Draw(base), ImageDraw.Draw(emissive)

    # The far wall, small and bright, with everything else running back to it.
    far = unit * 0.13
    bd.rectangle((cx - far, horizon - far * 0.8, cx + far, horizon + far * 0.9), fill=(150, 138, 66))

    # Walls, ceiling and carpet as four wedges meeting at the far wall. The corners go round
    # each shape in order - taken out of order they cross over into a bowtie.
    fl = (cx - far, horizon - far * 0.8)      # far wall, top left
    fr = (cx + far, horizon - far * 0.8)      # top right
    bl = (cx - far, horizon + far * 0.9)      # bottom left
    br = (cx + far, horizon + far * 0.9)      # bottom right
    bd.polygon([(0, 0), (0, h), bl, fl], fill=(176, 162, 78))        # left wall
    bd.polygon([(w, 0), (w, h), br, fr], fill=(150, 138, 66))        # right wall
    bd.polygon([(0, 0), (w, 0), fr, fl], fill=(196, 186, 140))       # ceiling
    bd.polygon([(0, h), (w, h), br, bl], fill=BACK_CARPET)           # carpet

    # Wall trim, and the skirting where the carpet meets the walls.
    trim = max(1, int(unit * 0.006))
    for near_y, far_y in ((h * 0.30, horizon - far * 0.55), (h, horizon + far * 0.9)):
        bd.line((0, near_y, cx - far, far_y), fill=BACK_YELLOW_DIM, width=trim)
        bd.line((w, near_y, cx + far, far_y), fill=BACK_YELLOW_DIM, width=trim)

    # Fluorescent tubes receding down the ceiling: the only light there is.
    for i, depth in enumerate((0.12, 0.42, 0.68, 0.86)):
        half = unit * 0.20 * (1 - depth) + unit * 0.02
        y = horizon - (horizon - h * 0.02) * (1 - depth) * 0.55
        thickness = max(2, int(unit * 0.035 * (1 - depth) + 1))
        box = (cx - half, y, cx + half, y + thickness)
        bd.rectangle(box, fill=(255, 250, 225))
        ed.rectangle(box, fill=(210, 200, 150))

    # The figure, down at the far end and small with it, lit from behind.
    figure_h = unit * 0.115
    fx, fy = cx + unit * 0.055, horizon + far * 0.86
    body = unit * 0.016
    bd.rectangle((fx - body, fy - figure_h * 0.62, fx + body, fy), fill=(14, 12, 12))
    head = unit * 0.015
    bd.ellipse((fx - head, fy - figure_h * 0.92, fx + head, fy - figure_h * 0.62), fill=(14, 12, 12))
    eye = max(1, int(unit * 0.004))
    for side in (-1, 1):
        spot = (fx + side * head * 0.42 - eye, fy - figure_h * 0.80 - eye,
                fx + side * head * 0.42 + eye, fy - figure_h * 0.80 + eye)
        bd.ellipse(spot, fill=(250, 242, 220))
        ed.ellipse(spot, fill=(200, 180, 150))

    art = finish(base, emissive, unit, bloom_strength=1.1, vignette_strength=0.7,
                 grain_amount=0.014, seed=91)

    if wordmark:
        d = ImageDraw.Draw(art)
        title = font(unit * 0.100, FONT_CONDENSED_BLACK)
        kicker = font(unit * 0.034)
        tracking = unit * 0.020
        title_y = h * 0.875
        tracked_text(d, cx, h * 0.810, "YOU NOCLIPPED OUT OF REALITY", kicker, (182, 172, 130),
                     unit * 0.022)
        tracked_text(d, cx, title_y, "THE BACKROOMS", title, (250, 244, 232), tracking)
        rule_half = tracked_width(d, "THE BACKROOMS", title, tracking) / 2
        rule_y = title_y + unit * 0.058
        d.line((cx - rule_half, rule_y, cx + rule_half, rule_y), fill=BACK_YELLOW_DIM,
               width=max(1, int(unit * 0.005)))

    rounded_border(
        ImageDraw.Draw(art), w, h,
        inset=unit * 0.022, radius=unit * 0.075, color=BACK_YELLOW_DIM,
        width=max(1, int(unit * 0.008)),
    )
    return art


# Each mod: the artwork, the branding/ file prefix, and every folder that gets an icon.png.
MODS = [
    (draw_club, "the-club", "THE CLUB", "A ROASTENGINE WORLD", NEON, NEON_DIM, (38, 10, 46), 31,
     ["mods/club"]),
    (draw_shader, "roastengine-shader-api", "SHADER API", "FOR ROASTENGINE", ACCENT, ACCENT_DIM,
     (46, 22, 6), 37, ["packs/roastengine-shader-api", "mods/roastengine-shader-api"]),
    (draw_inputedit, "inputedit-api", "INPUTEDIT API", "FOR ROASTENGINE", tuple(int(c) for c in WIRE),
     WIRE_DIM, (46, 24, 8), 53, ["packs/inputedit-api", "mods/inputedit-api"]),
    (draw_xbox, "xbox-controller", "XBOX CONTROLLER", "RUNS ON THE INPUTEDIT API", GREEN, GREEN_DIM,
     (10, 40, 16), 43, ["packs/xbox-controller", "mods/xbox-controller"]),
    (draw_bypass, "bypass-api", "BYPASS API", "FOR ROASTENGINE", CYAN, CYAN_DIM, (8, 34, 48), 61,
     ["packs/bypass-api", "mods/bypass-api"]),
    (draw_hack, "roast-hack", "ROAST HACK", "NEEDS THE BYPASS API", VIOLET, VIOLET_DIM, (34, 14, 52), 67,
     ["packs/roast-hack", "mods/roast-hack"]),
    (draw_placeholder, "placeholder-api", "PLACEHOLDER API", "FOR ROASTENGINE", TEAL, TEAL_DIM,
     (10, 40, 36), 73, ["packs/placeholder-api", "mods/placeholder-api"]),
    (draw_arena, "gun-arena", "GUN ARENA", "A ROASTENGINE WORLD", ARENA_GOLD, ARENA_GOLD_DIM,
     (40, 30, 14), 79, ["mods/gun-arena"]),
    (draw_backrooms, "backrooms", "THE BACKROOMS", "A ROASTENGINE WORLD", BACK_YELLOW,
     BACK_YELLOW_DIM, (44, 40, 16), 91, ["mods/backrooms"]),
]


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    for scene, prefix, title, kicker, accent, accent_dim, glow, seed, folders in MODS:
        render(f"{prefix}-icon-512x512.png", lambda w, h, f=scene: f(w, h), (512, 512))
        render(f"{prefix}-logo-1280x720.png", wide(scene), (1280, 720))
        render(
            f"{prefix}-header-400x100.png",
            lambda w, h, t=title, k=kicker, a=accent, d=accent_dim, g=glow, sd=seed:
                draw_header(w, h, t, k, a, d, g, sd),
            (400, 100),
        )
        write_mod_icon(lambda w, h, f=scene: f(w, h), folders)


def wide(scene):
    """The 16:9 card. The shader sphere labels its two halves once there is room for them."""
    if scene is draw_shader:
        return lambda w, h: draw_shader(w, h, labels=True)
    return lambda w, h: scene(w, h)


if __name__ == "__main__":
    main()
