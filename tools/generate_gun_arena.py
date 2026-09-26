#!/usr/bin/env python3
"""Generates the Gun Arena world mod: a lobby, two maps, six guns and the arena rules.

Everything is one scene. The lobby sits at the middle of the world, and the two maps are walled-in
boxes 200 m off to either side - far enough apart that nobody ever sees one from another - so the
match moves players between them by teleporting rather than by loading a new level.

    python3 tools/generate_gun_arena.py mods/gun-arena

Guns are built with their grip at the model's origin and the barrel pointing down -Z. That is
what the arena expects: the grip is where the hand closes, and -Z is the way the player looks.
"""

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from generate_club import ObjBuilder  # noqa: E402  (the same box-and-cylinder writer)

# --- Materials: name -> (r, g, b) --------------------------------------------------------------
MATERIALS = {
    "lobby_floor":    (0.36, 0.34, 0.38),
    "lobby_wall":     (0.50, 0.48, 0.54),
    "lobby_ceiling":  (0.28, 0.27, 0.31),
    "concrete":       (0.40, 0.40, 0.42),
    "concrete_dark":  (0.30, 0.30, 0.32),
    "roof":           (0.22, 0.22, 0.25),
    "crate":          (0.56, 0.41, 0.22),
    "crate_dark":     (0.40, 0.28, 0.15),
    "metal":          (0.34, 0.36, 0.40),
    "shelf":          (0.46, 0.40, 0.30),
    "container_red":  (0.62, 0.20, 0.15),
    "container_blue": (0.16, 0.32, 0.58),
    "stone":          (0.64, 0.60, 0.52),
    "stone_dark":     (0.47, 0.44, 0.38),
    "grass":          (0.30, 0.48, 0.24),
    "water":          (0.30, 0.52, 0.72),
    "rack":           (0.28, 0.21, 0.14),
    "neon_red":       (0.95, 0.28, 0.22),
    "neon_blue":      (0.25, 0.50, 1.00),
    "neon_white":     (0.95, 0.93, 0.88),
    "gun_metal":      (0.20, 0.20, 0.22),
    "gun_steel":      (0.42, 0.43, 0.45),
    "gun_wood":       (0.46, 0.28, 0.14),
    "gun_polymer":    (0.27, 0.28, 0.26),
    "gun_sight":      (1.00, 0.35, 0.20),
    "target_white":   (0.90, 0.90, 0.88),
    "target_red":     (0.86, 0.16, 0.12),
    "dummy":          (0.72, 0.58, 0.38),
}

TRANSPARENT = {"water": 0.7}

EMISSIVE = {
    "neon_red": (1.8, 0.35, 0.25),
    "neon_blue": (0.30, 0.65, 2.0),
    "neon_white": (0.8, 0.76, 0.7),
    "gun_sight": (1.6, 0.35, 0.15),
}

# Where the two maps sit, and which way round.
WAREHOUSE_X = 200.0
COURTYARD_X = -200.0
EYE = 1.7


def write_mtl(path):
    with open(path, "w") as f:
        f.write("# RoastEngine Gun Arena materials\n")
        for name, (r, g, b) in MATERIALS.items():
            alpha = TRANSPARENT.get(name, 1.0)
            er, eg, eb = EMISSIVE.get(name, (0.0, 0.0, 0.0))
            f.write(f"newmtl {name}\nKd {r:.3f} {g:.3f} {b:.3f}\nKa 0 0 0\nKs 0 0 0\n"
                    f"Ke {er:.3f} {eg:.3f} {eb:.3f}\nd {alpha:.2f}\n\n")


def room(b, x0, x1, z0, z1, height, floor, wall, ceiling=None, thickness=0.4):
    """A floor, four walls and (optionally) a ceiling, enclosing x0..x1 by z0..z1."""
    w, d = x1 - x0, z1 - z0
    cx, cz = (x0 + x1) / 2, (z0 + z1) / 2
    b.box(floor, (cx, -0.1, cz), (w + thickness * 2, 0.2, d + thickness * 2))
    b.box(wall, (cx, height / 2, z0 - thickness / 2), (w + thickness * 2, height, thickness))
    b.box(wall, (cx, height / 2, z1 + thickness / 2), (w + thickness * 2, height, thickness))
    b.box(wall, (x0 - thickness / 2, height / 2, cz), (thickness, height, d))
    b.box(wall, (x1 + thickness / 2, height / 2, cz), (thickness, height, d))
    if ceiling:
        b.box(ceiling, (cx, height + 0.1, cz), (w + thickness * 2, 0.2, d + thickness * 2))


# --- The lobby -------------------------------------------------------------------------------

def build_lobby(b):
    """A 20 x 14 room: the rack of guns along the north wall, a fenced range on the east side."""
    room(b, -10, 10, -7, 7, 4.2, "lobby_floor", "lobby_wall", "lobby_ceiling")
    # Team colours down each side, and a light strip overhead.
    b.box("neon_red", (-9.78, 2.6, 0), (0.06, 0.12, 12))
    b.box("neon_blue", (9.78, 2.6, 0), (0.06, 0.12, 12))
    b.box("neon_white", (0, 4.05, 0), (14, 0.06, 0.3))
    # The gun rack: a back board and two rails the guns rest on.
    b.box("rack", (-2.0, 1.35, -6.72), (9.0, 1.6, 0.1))
    b.box("metal", (-2.0, 0.95, -6.55), (9.0, 0.05, 0.3))
    b.box("neon_white", (-2.0, 2.2, -6.66), (9.0, 0.05, 0.05))
    # The range, down the east side: a waist-high fence to shoot over, and a back stop on the
    # far wall behind the targets.
    b.box("metal", (4.0, 0.55, 1.5), (0.1, 1.1, 10.8))       # fence along the lane
    b.box("metal", (7.0, 0.55, -3.9), (6.0, 1.1, 0.1))       # its short end
    b.box("concrete_dark", (9.75, 1.5, 1.5), (0.3, 3.0, 10.8))  # back stop
    b.box("neon_red", (9.58, 3.05, 1.5), (0.06, 0.06, 10.8))


def build_dummy(b):
    """A practice target: a post, a torso with a bullseye, and a head. Base at y=0.

    The bullseye is on the -X face - towards the lobby, where the player shoots from - so the
    model faces the right way without being turned.
    """
    b.box("metal", (0, 0.45, 0), (0.08, 0.9, 0.08))
    b.box("dummy", (0, 1.25, 0), (0.22, 0.7, 0.5))
    b.box("dummy", (0, 1.75, 0), (0.22, 0.26, 0.24))
    b.box("target_white", (-0.115, 1.25, 0), (0.01, 0.34, 0.34))
    b.box("target_red", (-0.121, 1.25, 0), (0.01, 0.22, 0.22))
    b.box("target_white", (-0.127, 1.25, 0), (0.01, 0.1, 0.1))
    b.box("metal", (0, 0.03, 0), (0.5, 0.06, 0.5))


# --- Map 1: the warehouse --------------------------------------------------------------------

def mirrored(x0, positions):
    """Each (dx, ...) on both sides of the map's middle, so neither team has the better half."""
    out = []
    for p in positions:
        out.append((x0 + p[0],) + tuple(p[1:]))
        out.append((x0 - p[0],) + tuple(p[1:]))
    return out


def build_warehouse(b):
    """Indoors, 40 x 30: crates to duck behind, two rows of shelving, a container in the middle."""
    x0 = WAREHOUSE_X
    room(b, x0 - 20, x0 + 20, -15, 15, 7.0, "concrete", "concrete_dark", "roof")
    # Lights along the roof.
    for z in (-9, 0, 9):
        b.box("neon_white", (x0, 6.9, z), (26, 0.05, 0.16))
    # Team colours at each end.
    b.box("neon_red", (x0 - 19.78, 3.0, 0), (0.06, 0.2, 26))
    b.box("neon_blue", (x0 + 19.78, 3.0, 0), (0.06, 0.2, 26))

    # The container in the middle: cover from both ends, open to go round.
    b.box("container_red", (x0, 1.3, 0), (6.0, 2.6, 2.4))
    b.box("metal", (x0, 2.62, 0), (6.1, 0.04, 2.5))

    # Shelving rows, with gaps to run through.
    for z in (-8.5, 8.5):
        for dx in (-9.0, -3.2, 3.2, 9.0):
            x = x0 + dx
            b.box("shelf", (x, 1.6, z), (4.0, 0.08, 1.2))
            b.box("shelf", (x, 0.3, z), (4.0, 0.08, 1.2))
            b.box("crate_dark", (x, 0.95, z), (3.6, 1.2, 1.0))
            for post in (-1.95, 1.95):
                b.box("metal", (x + post, 1.25, z), (0.08, 2.5, 1.2))

    # Crates: low ones to crouch behind, stacks to peek round. Mirrored left to right.
    for x, z, size in mirrored(x0, [(6.0, 4.0, 1.2), (6.0, -4.5, 1.2), (12.5, 2.5, 1.0),
                                    (12.5, -1.0, 1.0), (15.5, 7.0, 1.4), (15.5, -7.5, 1.4),
                                    (9.5, 12.0, 1.2), (9.5, -12.0, 1.2)]):
        b.box("crate", (x, size / 2, z), (size, size, size))
    for x, z, below in mirrored(x0, [(12.5, 2.5, 1.0), (15.5, 7.0, 1.4)]):
        b.box("crate_dark", (x, below + 0.45, z), (0.9, 0.9, 0.9))   # a second crate on top

    # A low wall in front of each spawn, so nobody is shot the moment they arrive.
    for side in (-1, 1):
        b.box("concrete_dark", (x0 + side * 16.5, 0.6, 0), (0.5, 1.2, 6.0))


def warehouse_spawns(team):
    side = -1 if team == "red" else 1
    x = WAREHOUSE_X + side * 18.2
    yaw = 90.0 if team == "red" else 270.0   # facing across the map
    return [[x, EYE, z, yaw] for z in (-5.0, 0.0, 5.0)]


# --- Map 2: the courtyard --------------------------------------------------------------------

def build_courtyard(b):
    """Open to the sky, 36 x 28: a fountain in the middle, pillars and planters for cover."""
    x0 = COURTYARD_X
    room(b, x0 - 18, x0 + 18, -14, 14, 7.5, "stone", "stone_dark")
    # Grass strips along the long walls.
    for z in (-12.5, 12.5):
        b.box("grass", (x0, 0.02, z), (32, 0.04, 2.6))
    # Team colours along the top of each end wall.
    b.box("neon_red", (x0 - 17.78, 6.8, 0), (0.06, 0.2, 24))
    b.box("neon_blue", (x0 + 17.78, 6.8, 0), (0.06, 0.2, 24))

    # The fountain: a wide basin, water, and a column in the middle.
    b.cylinder("stone_dark", (x0, 0.0, 0), 3.2, 0.9, segments=20)
    b.cylinder("water", (x0, 0.85, 0), 2.9, 0.06, segments=20)
    b.cylinder("stone", (x0, 0.9, 0), 0.45, 2.2, segments=12)
    b.cylinder("stone", (x0, 3.1, 0), 1.0, 0.25, segments=16)

    # Pillars in two rings, mirrored.
    for x, z in mirrored(x0, [(7.0, 5.0), (7.0, -5.0), (12.0, 9.0), (12.0, -9.0), (4.0, 10.5),
                              (4.0, -10.5)]):
        b.box("stone", (x, 2.5, z), (1.0, 5.0, 1.0))
        b.box("stone_dark", (x, 0.15, z), (1.3, 0.3, 1.3))
        b.box("stone_dark", (x, 5.0, z), (1.3, 0.3, 1.3))

    # Planters: low cover, chest high on a crouching player.
    for x, z in mirrored(x0, [(9.5, 0.0), (14.5, 4.0), (14.5, -4.0)]):
        b.box("stone_dark", (x, 0.5, z), (1.2, 1.0, 3.2))
        b.box("grass", (x, 1.02, z), (1.0, 0.04, 3.0))

    # An arch over each spawn.
    for side in (-1, 1):
        x = x0 + side * 15.5
        b.box("stone", (x, 1.75, -3.0), (0.8, 3.5, 0.8))
        b.box("stone", (x, 1.75, 3.0), (0.8, 3.5, 0.8))
        b.box("stone", (x, 3.7, 0.0), (0.8, 0.5, 6.8))


def courtyard_spawns(team):
    side = -1 if team == "red" else 1
    x = COURTYARD_X + side * 16.6
    yaw = 90.0 if team == "red" else 270.0
    return [[x, EYE, z, yaw] for z in (-6.0, 0.0, 6.0)]


# --- The guns --------------------------------------------------------------------------------
# Grip at the origin, barrel down -Z. Sizes are real-world-ish, in metres.

def grip(b, material="gun_polymer", rake=0.0):
    b.box(material, (0, 0.06, 0.01 + rake), (0.032, 0.12, 0.045))


def build_pistol(b):
    grip(b)
    b.box("gun_metal", (0, 0.14, -0.055), (0.034, 0.048, 0.20))    # slide
    b.box("gun_polymer", (0, 0.105, -0.04), (0.032, 0.03, 0.14))    # frame
    b.box("gun_metal", (0, 0.095, -0.03), (0.01, 0.025, 0.05))      # trigger guard
    b.box("gun_sight", (0, 0.168, -0.14), (0.008, 0.01, 0.01))


def build_revolver(b):
    grip(b, "gun_wood", rake=0.01)
    b.box("gun_steel", (0, 0.13, -0.03), (0.034, 0.06, 0.10))       # frame
    b.box("gun_steel", (0, 0.13, -0.045), (0.05, 0.05, 0.05))       # cylinder
    b.box("gun_steel", (0, 0.145, -0.17), (0.022, 0.024, 0.18))     # barrel
    b.box("gun_metal", (0, 0.16, -0.25), (0.006, 0.012, 0.012))


def build_smg(b):
    grip(b)
    b.box("gun_metal", (0, 0.14, -0.10), (0.05, 0.075, 0.32))       # body
    b.box("gun_metal", (0, 0.03, -0.12), (0.03, 0.16, 0.04))        # magazine
    b.box("gun_steel", (0, 0.145, -0.30), (0.02, 0.02, 0.10))       # barrel
    b.box("gun_polymer", (0, 0.13, 0.13), (0.03, 0.04, 0.14))       # stock
    b.box("gun_sight", (0, 0.185, -0.02), (0.01, 0.012, 0.012))


def build_rifle(b):
    grip(b)
    b.box("gun_metal", (0, 0.145, -0.12), (0.055, 0.085, 0.46))     # receiver
    b.box("gun_steel", (0, 0.15, -0.46), (0.02, 0.02, 0.24))        # barrel
    b.box("gun_polymer", (0, 0.145, -0.33), (0.05, 0.06, 0.20))     # handguard
    b.box("gun_metal", (0, 0.03, -0.14), (0.03, 0.17, 0.06))        # magazine
    b.box("gun_polymer", (0, 0.12, 0.22), (0.04, 0.09, 0.24))       # stock
    b.box("gun_metal", (0, 0.2, -0.1), (0.035, 0.03, 0.12))         # sight
    b.box("gun_sight", (0, 0.22, -0.1), (0.01, 0.012, 0.012))


def build_shotgun(b):
    grip(b, "gun_wood")
    b.box("gun_metal", (0, 0.145, -0.10), (0.05, 0.075, 0.28))      # receiver
    b.box("gun_steel", (0, 0.16, -0.46), (0.028, 0.028, 0.46))      # barrel
    b.box("gun_wood", (0, 0.12, -0.36), (0.045, 0.04, 0.2))         # pump
    b.box("gun_steel", (0, 0.125, -0.52), (0.02, 0.02, 0.3))        # magazine tube
    b.box("gun_wood", (0, 0.12, 0.2), (0.042, 0.1, 0.28))           # stock


def build_sniper(b):
    grip(b)
    b.box("gun_polymer", (0, 0.14, -0.12), (0.06, 0.09, 0.56))      # body
    b.box("gun_steel", (0, 0.155, -0.62), (0.022, 0.022, 0.5))      # long barrel
    b.box("gun_metal", (0, 0.155, -0.88), (0.036, 0.036, 0.06))     # muzzle brake
    b.box("gun_metal", (0, 0.225, -0.12), (0.045, 0.045, 0.30))     # scope
    b.box("gun_metal", (0, 0.225, -0.28), (0.055, 0.055, 0.04))     # scope bell
    b.box("gun_sight", (0, 0.225, -0.30), (0.03, 0.03, 0.005))      # lens glint
    b.box("gun_metal", (0, 0.04, -0.13), (0.03, 0.12, 0.07))        # magazine
    b.box("gun_polymer", (0, 0.12, 0.25), (0.045, 0.11, 0.3))       # stock


GUNS = [
    # id, name, builder, stats
    ("pistol", "Pistol", build_pistol, dict(
        description="Reliable backup. Quick to draw, quick to fire.",
        damage=25, fireRate=5.0, automatic=False, magazine=12, reloadSeconds=1.1, range=60,
        spread=0.8, pellets=1, recoil=1.2, headshot=1.6, pitch=1.25)),
    ("revolver", "Revolver", build_revolver, dict(
        description="Six shots. Make them count.",
        damage=55, fireRate=1.6, automatic=False, magazine=6, reloadSeconds=2.2, range=80,
        spread=0.4, pellets=1, recoil=3.5, headshot=1.6, pitch=0.95)),
    ("smg", "SMG", build_smg, dict(
        description="Sprays fast up close, drifts at range.",
        damage=14, fireRate=13.0, automatic=True, magazine=32, reloadSeconds=1.6, range=45,
        spread=2.4, pellets=1, recoil=0.5, headshot=1.4, pitch=1.35)),
    ("rifle", "Assault Rifle", build_rifle, dict(
        description="Good at everything, best at nothing.",
        damage=22, fireRate=9.0, automatic=True, magazine=30, reloadSeconds=2.0, range=90,
        spread=1.2, pellets=1, recoil=0.8, headshot=1.5, pitch=1.05)),
    ("shotgun", "Shotgun", build_shotgun, dict(
        description="Eight pellets. Devastating across a room, useless across a map.",
        damage=14, fireRate=1.2, automatic=False, magazine=6, reloadSeconds=2.6, range=30,
        spread=6.0, pellets=8, recoil=5.0, headshot=1.25, pitch=0.8)),
    ("sniper", "Sniper Rifle", build_sniper, dict(
        description="A headshot is a kill. So is patience.",
        damage=90, fireRate=0.7, automatic=False, magazine=5, reloadSeconds=2.8, range=200,
        spread=0.05, pellets=1, recoil=6.0, headshot=2.0, pitch=0.7)),
]


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "mods/gun-arena"
    assets = os.path.join(out_dir, "assets")
    os.makedirs(assets, exist_ok=True)
    os.makedirs(os.path.join(out_dir, "arena"), exist_ok=True)
    write_mtl(os.path.join(assets, "arena.mtl"))

    for name, builder in (("lobby.obj", build_lobby), ("warehouse.obj", build_warehouse),
                          ("courtyard.obj", build_courtyard), ("dummy.obj", build_dummy)):
        b = ObjBuilder()
        builder(b)
        b.write(os.path.join(assets, name), "arena.mtl")
    for gun_id, _, builder, _ in GUNS:
        b = ObjBuilder()
        builder(b)
        b.write(os.path.join(assets, f"gun_{gun_id}.obj"), "arena.mtl")

    def obj(name, asset, x, y, z, yaw=0.0, collision=True, entity=False):
        entry = {
            "id": name.lower().replace(" ", "-"),
            "name": name,
            "asset": asset,
            "position": {"x": x, "y": y, "z": z},
            "rotation": {"x": 0.0, "y": yaw, "z": 0.0},
            "scale": 1.0,
            "collision": collision,
            "kills": False,
            "slippery": False,
            "script": "",
        }
        if entity:
            entry["entity"] = True
        return entry

    objects = [
        obj("Lobby", "lobby.obj", 0, 0, 0),
        obj("Warehouse", "warehouse.obj", 0, 0, 0),
        obj("Courtyard", "courtyard.obj", 0, 0, 0),
    ]
    # The guns hang on the rack, side-on, one every 1.4 m. The loadout takes them from here.
    for i, (_, name, _, _) in enumerate(GUNS):
        objects.append(obj(name, f"gun_{GUNS[i][0]}.obj", -5.5 + i * 1.4, 0.98, -6.5,
                           yaw=90.0, collision=False))
    # Practice targets down the range. They are entities, so they wander their lane and fall
    # over when shot - and the fence keeps them in.
    for i, z in enumerate((-2.0, 1.5, 5.0)):
        objects.append(obj(f"Target {i + 1}", "dummy.obj", 7.0, 0, z,
                           collision=False, entity=True))

    scene = {
        "name": "gun-arena",
        "skyColor": [0.42, 0.55, 0.72],
        "music": "",
        "groundPlatform": False,
        "spawn": [0.0, EYE, 3.0],
        "objects": objects,
    }
    with open(os.path.join(out_dir, "scene.json"), "w") as f:
        json.dump(scene, f, indent=2)

    arena = {
        "name": "Gun Arena",
        "scoreLimit": 10,
        "maxHealth": 100,
        "respawnSeconds": 3,
        "matchSeconds": 300,
        "minPlayers": 2,
        "lobby": {"spawn": [0.0, EYE, 3.0], "yaw": 0.0},
        "maps": [
            {"name": "Warehouse",
             "description": "Crates, shelving and a container. Close quarters.",
             "red": warehouse_spawns("red"), "blue": warehouse_spawns("blue")},
            {"name": "Courtyard",
             "description": "Open sky, a fountain and long sightlines.",
             "red": courtyard_spawns("red"), "blue": courtyard_spawns("blue")},
        ],
        "guns": [dict(id=gun_id, name=name, object=name, grip=[0, 0, 0], **stats)
                 for gun_id, name, _, stats in GUNS],
    }
    with open(os.path.join(out_dir, "arena", "arena.json"), "w") as f:
        json.dump(arena, f, indent=2)

    with open(os.path.join(out_dir, "mod.json"), "w") as f:
        json.dump({
            "name": "Gun Arena",
            "nameId": "gun-arena",
            "version": "1.0.0",
            "type": "world",
            "engine": "RoastEngine 0.3",
            "description": "Two teams, two maps, six guns. Vote for a map, pick your loadout, "
                           "ready up and fight - first to 10 wins. Online for two or more; "
                           "offline it is a practice range. Needs the PlaceHolder API to carry "
                           "more than one gun.",
        }, f, indent=2)

    print(f"Wrote {out_dir}: {len(objects)} objects, {len(GUNS)} guns, 2 maps")


if __name__ == "__main__":
    main()
