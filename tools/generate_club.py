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
    "mirror":     (0.88, 0.90, 0.96),
    "sign":       (0.12, 0.10, 0.16),
    "sign_neon":  (0.35, 1.00, 0.85),
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


def build_disco_ball(b):
    """A faceted ball on a short chain: eight mirrored slabs around a core."""
    b.box("trim", (0, 0.55, 0), (0.04, 1.1, 0.04))          # the chain up to the ceiling
    b.box("mirror", (0, 0, 0), (0.34, 0.34, 0.34))          # the core
    for i in range(8):
        angle = math.tau * i / 8
        x, z = math.cos(angle) * 0.2, math.sin(angle) * 0.2
        b.box("mirror", (x, 0, z), (0.16, 0.26, 0.16))      # the facets that catch the light


def build_sign(b):
    """A small board on a post, lit along the top."""
    b.box("trim", (0, 0.6, 0), (0.06, 1.2, 0.06))
    b.box("sign", (0, 1.35, 0), (0.9, 0.5, 0.05))
    b.box("sign_neon", (0, 1.62, 0), (0.94, 0.05, 0.07))


def build_bottle(b):
    """A bottle standing on its base, so it sits in the fist the right way up."""
    b.cylinder("bottle_a", (0, 0.11, 0), 0.043, 0.22)
    b.cylinder("bottle_a", (0, 0.24, 0), 0.030, 0.06)
    b.cylinder("bottle_a", (0, 0.31, 0), 0.018, 0.09)
    b.cylinder("trim", (0, 0.36, 0), 0.020, 0.02)


def build_glass(b):
    b.cylinder("glass", (0, 0, 0), 0.05, 0.16)


def build_liquid(b):
    """Unit-height liquid: the engine scales it down as the drink is drunk."""
    b.cylinder("beer", (0, 0, 0), 0.045, 1.0)


DISCO_BALL_SCRIPT = """\
# The disco ball over the dance floor.
#
# turn_speed and bob are script values on the object, so a second ball hung somewhere
# else can turn at its own speed without this script being copied.


def on_start():
    self.spin(param("turn_speed", 45))
    every(4, dip)


def dip():
    self.move_to(self.x, self.y - param("bob", 0.25), self.z, 2)
    after(2, lift)


def lift():
    self.move_to(self.x, self.y + param("bob", 0.25), self.z, 2)
"""

CARRY_SCRIPT = """\
# What the player can pick up.
#
# The name is the one the level calls it - the same name the Creator shows in its list of
# objects down the side. Say it here and the player can pick that object up with E, carry it
# around and put it down again with Q.

item = "Bottle"
hold_item(item)
"""

HOUSE_RULES_SCRIPT = """\
# The sign by the door. It has opinions about how you treat the guests.


def on_start():
    self.near_distance = 4


def on_player_near():
    notice(pick([
        "House rules: mind the guests.",
        "House rules: the guests do not mind you.",
        "House rules: what goes on the floor stays on the floor.",
    ]))


def on_interact():
    say("The sign is bolted down. The guests are not.")
"""


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
        "disco_ball.obj": build_disco_ball,
        "sign.obj": build_sign,
        "bottle.obj": build_bottle,
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
            interaction="", fill=0.0, label="", npc="", entity=False, script="",
            script_params=None):
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
            "script": script,
        }
        if entity:
            entry["entity"] = True
        if script_params:
            entry["scriptParams"] = script_params
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

    # People around the room. They are entities: they wander off on their own, and a punch
    # (E, or the trigger in VR) sends them tumbling.
    placements = [(-3.2, -6.0, 200), (-3.4, -10.5, 160), (2.0, -5.0, 20),
                  (4.8, -9.5, 300), (1.6, -13.5, 90)]
    for i, (x, z, yaw) in enumerate(placements):
        objects.append(obj(f"Guest {i + 1}", f"person_{i}.obj", x, 0, z, yaw=yaw,
                           collision=True, entity=True))

    # A bottle on the end of the bar that you can pick up and carry off. What makes it holdable
    # is the script, not the scene - which is the shape a mod's own items are written in.
    objects.append(obj("Bottle", "bottle.obj", -5.6, 1.2, -2.0, collision=False,
                       script="carry_the_bottle.py"))

    # The disco ball turns above the floor, and the sign by the door warns you about the guests.
    objects.append(obj("Disco Ball", "disco_ball.obj", 0, 3.4, -9.0, collision=False,
                       script="disco_ball.py", script_params={"turn_speed": "45", "bob": "0.25"}))
    objects.append(obj("House Rules", "sign.obj", 2.6, 0, -2.4, yaw=-150.0, collision=False,
                       script="house_rules.py"))

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
    scripts = os.path.join(out_dir, "scripts")
    os.makedirs(scripts, exist_ok=True)
    for name, source in (("disco_ball.py", DISCO_BALL_SCRIPT),
                         ("house_rules.py", HOUSE_RULES_SCRIPT),
                         ("carry_the_bottle.py", CARRY_SCRIPT)):
        with open(os.path.join(scripts, name), "w") as f:
            f.write(source)

    with open(os.path.join(out_dir, "scene.json"), "w") as f:
        json.dump(scene, f, indent=2)

    with open(os.path.join(out_dir, "mod.json"), "w") as f:
        json.dump({
            "name": "The Club",
            "nameId": "the-club",
            "version": "1.2.0",
            "type": "world",
            "engine": "RoastEngine 0.1",
            "description": "A hallway, a door to open, a club full of people you can punch, "
                           "drinks you can sip, a bottle you can carry off, and a disco ball "
                           "that will not stop turning.",
        }, f, indent=2)

    print(f"Wrote {out_dir}: {len(objects)} objects, {len(pieces) + len(people)} meshes, "
          f"{len(os.listdir(scripts))} scripts")


if __name__ == "__main__":
    main()
