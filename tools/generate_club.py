#!/usr/bin/env python3
"""Generates the geometry and scene for the Club level mod.

The engine loads .obj/.glb with materials, so everything here is built from boxes and simple
cylinders written straight to OBJ + MTL. Run it to regenerate the mod:

    python3 tools/generate_club.py mods/club
"""

import json
import math
import os
import sys

# --- materials: name -> (r, g, b) -------------------------------------------------
MATERIALS = {
    "floor":      (0.30, 0.27, 0.34),
    "carpet":     (0.52, 0.16, 0.22),
    "wall":       (0.42, 0.37, 0.46),
    "wall_neon":  (0.95, 0.35, 1.00),
    "ceiling":    (0.22, 0.20, 0.26),
    "trim":       (0.95, 0.72, 0.35),
    "door":       (0.58, 0.38, 0.22),
    "door_metal": (0.72, 0.70, 0.68),
    "bar_top":    (0.55, 0.36, 0.20),
    "bar_front":  (0.36, 0.24, 0.18),
    "shelf":      (0.45, 0.30, 0.20),
    "bottle_a":   (0.20, 0.55, 0.30),
    "bottle_b":   (0.65, 0.45, 0.15),
    "stool":      (0.55, 0.20, 0.24),
    "table":      (0.40, 0.34, 0.32),
    "glass":      (0.80, 0.86, 0.90),
    "beer":       (0.85, 0.60, 0.12),
    "wine":       (0.55, 0.10, 0.18),
    "cola":       (0.25, 0.12, 0.06),
    "skin_a":     (0.85, 0.68, 0.52),
    "skin_b":     (0.55, 0.38, 0.26),
    "skin_c":     (0.70, 0.52, 0.38),
    "shirt_a":    (0.20, 0.35, 0.65),
    "shirt_b":    (0.55, 0.15, 0.35),
    "shirt_c":    (0.15, 0.50, 0.45),
    "shirt_d":    (0.60, 0.50, 0.15),
    "shirt_e":    (0.35, 0.25, 0.55),
    "trousers":   (0.28, 0.28, 0.34),
    "hair":       (0.20, 0.16, 0.14),
    "overalls":   (0.32, 0.40, 0.50),
    "cap":        (0.15, 0.30, 0.70),
    "mop":        (0.80, 0.78, 0.60),
}


# Materials you can see through, name -> opacity.
TRANSPARENT = {"glass": 0.35}

# Materials that give off light (MTL "Ke"), name -> emissive colour. Bloom makes these glow.
EMISSIVE = {
    "wall_neon": (1.6, 0.45, 1.9),
    "trim": (0.10, 0.06, 0.01),
    "bottle_a": (0.05, 0.25, 0.10),
    "bottle_b": (0.30, 0.18, 0.02),
}


class ObjBuilder:
    """Accumulates boxes and cylinders, then writes an OBJ with a matching MTL."""

    def __init__(self):
        self.vertices = []
        self.faces = []          # (material, [vertex indices])

    def box(self, material, center, size):
        cx, cy, cz = center
        sx, sy, sz = (s / 2 for s in size)
        base = len(self.vertices) + 1
        for dx, dy, dz in [(-1, -1, -1), (1, -1, -1), (1, -1, 1), (-1, -1, 1),
                           (-1, 1, -1), (1, 1, -1), (1, 1, 1), (-1, 1, 1)]:
            self.vertices.append((cx + dx * sx, cy + dy * sy, cz + dz * sz))
        # Counter-clockwise when seen from outside.
        quads = [(0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4),
                 (2, 3, 7, 6), (1, 2, 6, 5), (3, 0, 4, 7)]
        for quad in quads:
            self.faces.append((material, [base + i for i in quad]))

    def cylinder(self, material, center, radius, height, segments=12):
        cx, cy, cz = center
        base = len(self.vertices) + 1
        for i in range(segments):
            angle = 2 * math.pi * i / segments
            x = cx + math.cos(angle) * radius
            z = cz + math.sin(angle) * radius
            self.vertices.append((x, cy, z))
            self.vertices.append((x, cy + height, z))
        for i in range(segments):
            a = base + i * 2
            b = base + ((i + 1) % segments) * 2
            self.faces.append((material, [a, b, b + 1, a + 1]))
        # Caps
        top_centre = len(self.vertices) + 1
        self.vertices.append((cx, cy + height, cz))
        bottom_centre = len(self.vertices) + 1
        self.vertices.append((cx, cy, cz))
        for i in range(segments):
            a = base + i * 2
            b = base + ((i + 1) % segments) * 2
            self.faces.append((material, [top_centre, a + 1, b + 1]))
            self.faces.append((material, [bottom_centre, b, a]))

    def write(self, obj_path, mtl_name):
        with open(obj_path, "w") as f:
            f.write(f"# RoastEngine club level\nmtllib {mtl_name}\n")
            for x, y, z in self.vertices:
                f.write(f"v {x:.4f} {y:.4f} {z:.4f}\n")
            current = None
            for material, indices in self.faces:
                if material != current:
                    f.write(f"usemtl {material}\n")
                    current = material
                f.write("f " + " ".join(str(i) for i in indices) + "\n")


def write_mtl(path):
    with open(path, "w") as f:
        f.write("# RoastEngine club level materials\n")
        for name, (r, g, b) in MATERIALS.items():
            alpha = TRANSPARENT.get(name, 1.0)
            er, eg, eb = EMISSIVE.get(name, (0.0, 0.0, 0.0))
            f.write(f"newmtl {name}\nKd {r:.3f} {g:.3f} {b:.3f}\nKa 0 0 0\nKs 0 0 0\n"
                    f"Ke {er:.3f} {eg:.3f} {eb:.3f}\nd {alpha}\nillum 1\n\n")


def build_hallway(b):
    """Entrance corridor running from z=+14 (spawn) to the door at z=+2."""
    b.box("carpet", (0, -0.1, 8), (4.0, 0.2, 14.0))
    b.box("ceiling", (0, 3.0, 8), (4.0, 0.2, 14.0))
    b.box("wall", (-2.1, 1.5, 8), (0.2, 3.0, 14.0))
    b.box("wall", (2.1, 1.5, 8), (0.2, 3.0, 14.0))
    # Neon strips along the corridor walls.
    for z in range(3, 15, 3):
        b.box("wall_neon", (-1.98, 2.2, z), (0.06, 0.25, 1.6))
        b.box("wall_neon", (1.98, 2.2, z), (0.06, 0.25, 1.6))
    # Door frame at the end.
    b.box("trim", (-1.35, 1.5, 2.0), (0.7, 3.0, 0.3))
    b.box("trim", (1.35, 1.5, 2.0), (0.7, 3.0, 0.3))
    b.box("trim", (0, 2.85, 2.0), (2.0, 0.3, 0.3))


def build_club_room(b):
    """The room beyond the door: 16 x 16, bar along the left wall."""
    b.box("floor", (0, -0.1, -8), (16.0, 0.2, 20.0))
    b.box("ceiling", (0, 4.0, -8), (16.0, 0.2, 20.0))
    b.box("wall", (-8.1, 2.0, -8), (0.2, 4.0, 20.0))
    b.box("wall", (8.1, 2.0, -8), (0.2, 4.0, 20.0))
    b.box("wall", (0, 2.0, -18.1), (16.0, 4.0, 0.2))
    # Front wall with the doorway gap.
    b.box("wall", (-5.0, 2.0, 1.9), (6.0, 4.0, 0.2))
    b.box("wall", (5.0, 2.0, 1.9), (6.0, 4.0, 0.2))
    b.box("wall", (0, 3.5, 1.9), (4.0, 1.0, 0.2))

    # Neon wall accents.
    for x in (-7.9, 7.9):
        for z in (-14, -10, -6, -2):
            b.box("wall_neon", (x, 2.6, z), (0.06, 0.4, 2.2))

    # Bar counter along the left wall.
    b.box("bar_front", (-6.0, 0.55, -8.0), (1.4, 1.1, 10.0))
    b.box("bar_top", (-6.0, 1.15, -8.0), (1.8, 0.1, 10.4))
    # Back shelves with bottles.
    b.box("shelf", (-7.7, 1.4, -8.0), (0.5, 0.1, 9.0))
    b.box("shelf", (-7.7, 2.0, -8.0), (0.5, 0.1, 9.0))
    for i in range(12):
        z = -12.0 + i * 0.8
        material = "bottle_a" if i % 2 == 0 else "bottle_b"
        b.cylinder(material, (-7.7, 1.45, z), 0.07, 0.35)
        b.cylinder(material, (-7.7, 2.05, z), 0.07, 0.30)

    # Stools at the bar.
    for i in range(5):
        z = -11.5 + i * 2.0
        b.cylinder("stool", (-4.6, 0.0, z), 0.16, 0.7)
        b.cylinder("stool", (-4.6, 0.7, z), 0.28, 0.08)

    # Tables on the right side.
    for (x, z) in [(3.0, -4.0), (5.5, -9.0), (2.5, -13.0)]:
        b.cylinder("table", (x, 0.0, z), 0.12, 0.95)
        b.cylinder("table", (x, 0.95, z), 0.6, 0.08)


def build_door(b):
    """A door slab whose pivot sits on its hinge edge, so it swings when rotated."""
    b.box("door", (1.0, 1.35, 0), (2.0, 2.7, 0.12))
    b.box("door_metal", (1.8, 1.25, 0.1), (0.1, 0.1, 0.12))


def build_person(b, shirt, skin):
    """A simple blocky figure, 1.75m tall, standing at the origin."""
    b.box("trousers", (0, 0.4, 0), (0.42, 0.8, 0.26))
    b.box(shirt, (0, 1.15, 0), (0.5, 0.75, 0.3))
    b.box(skin, (-0.31, 1.15, 0), (0.14, 0.62, 0.16))
    b.box(skin, (0.31, 1.15, 0), (0.14, 0.62, 0.16))
    b.box(skin, (0, 1.68, 0), (0.24, 0.28, 0.24))
    b.box("hair", (0, 1.84, 0), (0.26, 0.08, 0.26))


def build_janitor(b):
    """Grey overalls and a blue cap, arms held out in front to carry something."""
    b.box("overalls", (0, 0.45, 0), (0.46, 0.9, 0.3))
    b.box("overalls", (0, 1.2, 0), (0.54, 0.7, 0.32))
    b.box("skin_a", (-0.3, 1.25, 0.28), (0.14, 0.14, 0.62))
    b.box("skin_a", (0.3, 1.25, 0.28), (0.14, 0.14, 0.62))
    b.box("skin_a", (0, 1.72, 0), (0.26, 0.3, 0.26))
    b.box("cap", (0, 1.9, 0.03), (0.3, 0.08, 0.34))


def build_glass(b):
    b.cylinder("glass", (0, 0, 0), 0.05, 0.16)


def build_liquid(b):
    """Unit-height liquid: the engine scales it down as the drink is drunk."""
    b.cylinder("beer", (0, 0, 0), 0.045, 1.0)


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "mods/club"
    assets = os.path.join(out_dir, "assets")
    os.makedirs(assets, exist_ok=True)
    write_mtl(os.path.join(assets, "club.mtl"))

    pieces = {
        "hallway.obj": build_hallway,
        "club_room.obj": build_club_room,
        "door.obj": build_door,
        "glass.obj": build_glass,
        "liquid.obj": build_liquid,
        "janitor.obj": build_janitor,
    }
    for name, builder in pieces.items():
        b = ObjBuilder()
        builder(b)
        b.write(os.path.join(assets, name), "club.mtl")

    people = [("shirt_a", "skin_a"), ("shirt_b", "skin_b"), ("shirt_c", "skin_c"),
              ("shirt_d", "skin_a"), ("shirt_e", "skin_b")]
    for i, (shirt, skin) in enumerate(people):
        b = ObjBuilder()
        build_person(b, shirt, skin)
        b.write(os.path.join(assets, f"person_{i}.obj"), "club.mtl")

    def obj(name, asset, x, y, z, yaw=0.0, scale=1.0, collision=True,
            interaction="", fill=0.0, label="", npc=""):
        entry = {
            "id": name.lower().replace(" ", "-"),
            "name": name,
            "asset": asset,
            "position": {"x": x, "y": y, "z": z},
            "rotation": {"x": 0.0, "y": yaw, "z": 0.0},
            "scale": scale,
            "collision": collision,
            "kills": False,
            "slippery": False,
            "script": "",
        }
        if interaction:
            entry["interaction"] = interaction
            entry["fill"] = fill
            entry["label"] = label
        if npc:
            entry["npc"] = npc
        return entry

    objects = [
        obj("Hallway", "hallway.obj", 0, 0, 0),
        obj("Club Room", "club_room.obj", 0, 0, 0),
        obj("Door", "door.obj", -1.0, 0, 2.0, interaction="door", label="open the door"),
    ]

    # The doorman: the animated player rig, standing by the door watching the hall.
    rig = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "player_idle.glb")
    if os.path.isfile(rig):
        import shutil
        shutil.copyfile(rig, os.path.join(assets, "doorman.glb"))
        objects.append(obj("Doorman", "doorman.glb", 1.45, 0, 3.2, yaw=-90.0,
                           collision=False, npc="doorman"))
    else:
        print("warning: player_idle.glb not found - the level will have no doorman")

    # The janitor only appears after you pass out, wherever you fell.
    objects.append(obj("Janitor", "janitor.obj", 0, 0, -8, collision=False, npc="janitor"))

    # People around the room.
    placements = [(-3.2, -6.0, 200), (-3.4, -10.5, 160), (2.0, -5.0, 20),
                  (4.8, -9.5, 300), (1.6, -13.5, 90)]
    for i, (x, z, yaw) in enumerate(placements):
        objects.append(obj(f"Guest {i + 1}", f"person_{i}.obj", x, 0, z, yaw=yaw, collision=True))

    # Drinks on the bar: each is a glass plus the liquid inside it.
    drinks = [("Beer", -12.0), ("Cocktail", -9.5), ("Cola", -7.0), ("Wine", -4.5)]
    for name, z in drinks:
        objects.append(obj(name, "glass.obj", -5.6, 1.2, z, collision=False,
                           interaction="drink", fill=1.0, label=f"sip the {name.lower()}"))

    scene = {
        "name": "club",
        "skyColor": [0.09, 0.07, 0.13],
        "music": "music_club",
        "groundPlatform": False,
        "spawn": [0.0, 1.7, 13.0],
        "objects": objects,
    }
    with open(os.path.join(out_dir, "scene.json"), "w") as f:
        json.dump(scene, f, indent=2)

    with open(os.path.join(out_dir, "mod.json"), "w") as f:
        json.dump({
            "name": "The Club",
            "nameId": "the-club",
            "version": "1.0.0",
            "type": "world",
            "engine": "RoastEngine 0.1",
            "description": "A hallway, a door to open, a club full of people, and drinks you can sip.",
        }, f, indent=2)

    print(f"Wrote {out_dir}: {len(objects)} objects, {len(pieces) + len(people)} meshes")


if __name__ == "__main__":
    main()
