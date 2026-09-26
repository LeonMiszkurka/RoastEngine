#!/usr/bin/env python3
"""Generates the Backrooms world mod: three levels, a home screen and something that hunts you.

    python3 tools/generate_backrooms.py mods/backrooms [--seed 7]

Like the Gun Arena, everything lives in one scene: the three levels are walled-in boxes 400 m
apart, so picking a level moves the player by teleporting rather than by loading another world.

Each level is a grid carved into rooms and corridors. The carving is written out twice:

  * as geometry, in chunks of cells, so collision (which is per triangle) only ever has to look
    at the chunk the player is standing in;
  * as a character map in backrooms/level.json, which is what the monster walks on. A wall in
    that map is a wall in the world, so the two can never disagree.

The map is the doubled kind: a cell at (cx, cz) is at (2*cx+1, 2*cz+1), and the square between
two cells says whether they are joined. '#' is solid, '.' is open.

Surfaces are textured. The yellow wallpaper is a real sample (tools/textures/), cropped to a
whole number of its own repeats so it tiles without a seam; everything else is generated here
from periodic noise, which tiles for the same reason. UVs are worked out from world position
rather than per object, so the pattern runs unbroken from one wall block into the next.
"""

import argparse
import json
import math
import os
import random
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from generate_club import ObjBuilder  # noqa: E402  (the box-and-cylinder writer, for the cylinders)


class Builder(ObjBuilder):
    """The box writer, with texture coordinates.

    UVs come from where a face sits in the world, not from the box it belongs to, so the
    wallpaper runs straight through the join between one wall block and the next instead of
    restarting at every object. Each face gets its own four corners (24 a box rather than 8),
    which is what lets the two faces meeting at a corner carry different parts of the pattern.
    """

    #: The six faces as corner offsets, wound counter-clockwise seen from outside, with the
    #: pair of world axes that the texture is laid out along (0 = x, 1 = y, 2 = z).
    FACES = [
        ([(-1, -1, -1), (-1, -1, 1), (1, -1, 1), (1, -1, -1)], 0, 2),    # bottom
        ([(-1, 1, -1), (1, 1, -1), (1, 1, 1), (-1, 1, 1)], 0, 2),        # top
        ([(-1, -1, -1), (1, -1, -1), (1, 1, -1), (-1, 1, -1)], 0, 1),    # -Z
        ([(1, -1, 1), (-1, -1, 1), (-1, 1, 1), (1, 1, 1)], 0, 1),        # +Z
        ([(1, -1, -1), (1, -1, 1), (1, 1, 1), (1, 1, -1)], 2, 1),        # +X
        ([(-1, -1, 1), (-1, -1, -1), (-1, 1, -1), (-1, 1, 1)], 2, 1),    # -X
    ]

    def __init__(self):
        super().__init__()
        self.uvs = []
        self.textured_faces = []     # (material, [(vertex, uv), ...])

    def box(self, material, center, size):
        scale = TEXTURED.get(material, (None, 0))[1]
        if not scale:
            super().box(material, center, size)     # flat colour: no UVs needed
            return
        cx, cy, cz = center
        sx, sy, sz = (s / 2 for s in size)
        for corners, u_axis, v_axis in Builder.FACES:
            face = []
            for dx, dy, dz in corners:
                point = (cx + dx * sx, cy + dy * sy, cz + dz * sz)
                self.vertices.append(point)
                self.uvs.append((point[u_axis] / scale, point[v_axis] / scale))
                face.append((len(self.vertices), len(self.uvs)))
            self.textured_faces.append((material, face))

    def write(self, obj_path, mtl_name):
        with open(obj_path, "w") as f:
            f.write(f"# RoastEngine Backrooms level\nmtllib {mtl_name}\n")
            for x, y, z in self.vertices:
                f.write(f"v {x:.4f} {y:.4f} {z:.4f}\n")
            for u, v in self.uvs:
                f.write(f"vt {u:.4f} {v:.4f}\n")
            current = None
            # Untextured faces first, then the textured ones, so each material appears once.
            for material, indices in self.faces:
                if material != current:
                    f.write(f"usemtl {material}\n")
                    current = material
                f.write("f " + " ".join(str(i) for i in indices) + "\n")
            for material, face in self.textured_faces:
                if material != current:
                    f.write(f"usemtl {material}\n")
                    current = material
                f.write("f " + " ".join(f"{v}/{t}" for v, t in face) + "\n")

# --- Materials: name -> (r, g, b) --------------------------------------------------------------
MATERIALS = {
    # Level 0: the yellow rooms. Wallpaper, damp carpet, stained ceiling tiles.
    "wallpaper":      (0.84, 0.78, 0.45),
    "wallpaper_damp": (0.66, 0.60, 0.33),
    "carpet":         (0.58, 0.52, 0.28),
    "carpet_stain":   (0.44, 0.39, 0.21),
    "ceiling_tile":   (0.78, 0.74, 0.58),
    "fluorescent":    (1.00, 0.97, 0.80),
    # Level 1: concrete and steel.
    "concrete":       (0.52, 0.51, 0.49),
    "concrete_dark":  (0.34, 0.34, 0.33),
    "concrete_floor": (0.42, 0.41, 0.40),
    "steel":          (0.38, 0.40, 0.43),
    "lamp_cold":      (0.80, 0.88, 1.00),
    # Level 2: pipes in the dark.
    "grime":          (0.24, 0.23, 0.20),
    "grime_wall":     (0.20, 0.19, 0.17),
    "pipe":           (0.33, 0.30, 0.26),
    "rust":           (0.40, 0.24, 0.14),
    "lamp_sick":      (0.55, 0.85, 0.45),
    # Level 3: brick and switchgear.
    "brick":          (0.44, 0.31, 0.26),
    "brick_dark":     (0.32, 0.22, 0.19),
    "switchgear":     (0.36, 0.38, 0.36),
    "warning":        (0.86, 0.72, 0.18),
    "lamp_caged":     (1.00, 0.90, 0.62),
    # Level 4: the office. Cubicle felt, dead monitors, stained ceiling tiles.
    "office_carpet":  (0.30, 0.33, 0.36),
    "office_wall":    (0.72, 0.70, 0.64),
    "cubicle":        (0.44, 0.47, 0.44),
    "desk":           (0.54, 0.44, 0.32),
    "monitor":        (0.10, 0.10, 0.11),
    # Level 5: the hotel. Faded paper, patterned carpet, endless doors.
    "hotel_paper":    (0.56, 0.40, 0.36),
    "hotel_paper_alt": (0.44, 0.31, 0.28),
    "hotel_carpet":   (0.34, 0.16, 0.16),
    "hotel_trim":     (0.30, 0.21, 0.14),
    "door_wood":      (0.34, 0.22, 0.14),
    "door_brass":     (0.72, 0.60, 0.28),
    "lamp_warm":      (1.00, 0.84, 0.56),
    # Level 6: lights out. Nothing works down here but the exit signs.
    "black_wall":     (0.11, 0.11, 0.12),
    "black_floor":    (0.09, 0.09, 0.10),
    "exit_sign":      (0.35, 1.00, 0.45),
    # Level 37: the Poolrooms. White tile, still water, light from nowhere.
    "pool_tile":      (0.92, 0.94, 0.94),
    "pool_tile_alt":  (0.82, 0.86, 0.88),
    "pool_floor":     (0.86, 0.90, 0.91),
    "pool_water":     (0.35, 0.72, 0.72),
    "pool_glow":      (0.98, 1.00, 1.00),
    # The things that hunt you, one to a level.
    "faceling_skin":  (0.80, 0.74, 0.67),
    "faceling_suit":  (0.13, 0.12, 0.14),
    "hound_hide":     (0.26, 0.21, 0.17),
    "hound_teeth":    (0.86, 0.83, 0.74),
    "hound_eye":      (1.00, 0.86, 0.45),
    "smiler_body":    (0.02, 0.02, 0.02),
    "smiler_teeth":   (1.00, 0.98, 0.92),
    "smiler_eye":     (1.00, 0.95, 0.85),
    "scrawl_body":    (0.03, 0.03, 0.04),
    "scrawl_wound":   (0.42, 0.09, 0.06),
    "scrawl_eye":     (0.95, 0.40, 0.22),
}

EMISSIVE = {
    "fluorescent": (1.60, 1.54, 1.20),
    "lamp_cold":   (0.70, 0.85, 1.30),
    "lamp_sick":   (0.45, 1.10, 0.40),
    "lamp_caged":  (1.50, 1.30, 0.80),
    "lamp_warm":   (1.40, 1.05, 0.60),
    "exit_sign":   (0.25, 1.30, 0.40),
    "pool_glow":   (0.85, 0.95, 1.00),
    "scrawl_eye":  (1.50, 0.55, 0.25),
    "hound_eye":    (1.30, 1.00, 0.35),
    "smiler_teeth": (1.70, 1.66, 1.52),
    "smiler_eye":   (1.90, 1.75, 1.40),
}

#: material -> (texture file, metres one tile covers). Anything not here renders as flat colour.
TEXTURED = {
    "wallpaper":      ("wallpaper.png", 1.5),
    "wallpaper_damp": ("wallpaper_damp.png", 1.5),
    "carpet":         ("carpet.png", 2.0),
    "carpet_stain":   ("carpet.png", 2.0),
    "ceiling_tile":   ("ceiling.png", 1.22),
    "concrete":       ("concrete.png", 2.5),
    "concrete_dark":  ("concrete_dark.png", 2.5),
    "concrete_floor": ("concrete_floor.png", 2.5),
    "grime":          ("grime.png", 2.0),
    "grime_wall":     ("grime_wall.png", 2.0),
    "brick":          ("brick.png", 2.4),
    "brick_dark":     ("brick_dark.png", 2.4),
    "office_carpet":  ("office_carpet.png", 1.2),
    "office_wall":    ("office_wall.png", 2.2),
    "hotel_paper":    ("hotel_paper.png", 1.6),
    "hotel_carpet":   ("hotel_carpet.png", 1.8),
    "pool_tile":      ("pool_tile.png", 1.2),
    "pool_tile_alt":  ("pool_tile.png", 1.2),
    "pool_floor":     ("pool_floor.png", 1.2),
}

#: material -> opacity. Anything not here is solid.
TRANSPARENT = {"pool_water": 0.62}

EYE = 1.7
#: How far apart the levels sit. Far enough that nothing of one is ever visible from another.
LEVEL_SPACING = 400.0
#: Cells per chunk, per side. Each chunk becomes its own object, so collision can skip it.
CHUNK = 6


# --- Level definitions -------------------------------------------------------------------------

class Level:
    """One level: how big it is, how it looks, and how tightly it is packed."""

    def __init__(self, ident, name, description, cells, cell_size, height,
                 wall, wall_alt, floor, floor_alt, ceiling, lamp,
                 openness, lamp_chance, pillars=False, pipes=False,
                 machines=False, desks=False, partitions=0.0, doors=False,
                 water=0.0, exit_signs=False, ceiling_glow=False, lamp_style="strip"):
        self.id = ident
        self.name = name
        self.description = description
        self.cells = cells                # grid is cells x cells
        self.cell_size = cell_size        # metres across one cell
        self.height = height
        self.wall = wall
        self.wall_alt = wall_alt
        self.floor = floor
        self.floor_alt = floor_alt
        self.ceiling = ceiling
        self.lamp = lamp
        self.openness = openness          # 0 = a strict maze, 1 = wide open
        self.lamp_chance = lamp_chance
        self.pillars = pillars
        self.pipes = pipes
        self.machines = machines          # switchgear against the walls (Level 3)
        self.desks = desks                # desks and dead monitors (Level 4)
        self.partitions = partitions      # how many walls are waist-high cubicle dividers
        self.doors = doors                # a door in the wall, over and over (Level 5)
        self.water = water                # how deep the water lies, in metres (Level 37)
        self.exit_signs = exit_signs      # the only light in the dark (Level 6)
        self.ceiling_glow = ceiling_glow  # light from nowhere, as the Poolrooms have
        self.lamp_style = lamp_style      # strip | caged | sconce | none
        self.grid = None                  # filled in by carve()
        self.origin_x = 0.0

    @property
    def span(self):
        """How far the level reaches across, in metres."""
        return self.cells * self.cell_size


LEVELS = [
    Level("level0", "Level 0 - The Lobby",
          "Yellow wallpaper, damp carpet, and the hum. Where everyone starts.",
          cells=13, cell_size=6.0, height=3.2,
          wall="wallpaper", wall_alt="wallpaper_damp", floor="carpet", floor_alt="carpet_stain",
          ceiling="ceiling_tile", lamp="fluorescent", openness=0.55, lamp_chance=0.5),
    Level("level1", "Level 1 - Habitable Zone",
          "Concrete and steel. Wider halls, colder light, more room to run.",
          cells=17, cell_size=7.0, height=4.0,
          wall="concrete", wall_alt="concrete_dark", floor="concrete_floor",
          floor_alt="concrete_dark", ceiling="concrete_dark", lamp="lamp_cold",
          openness=0.7, lamp_chance=0.35, pillars=True),
    Level("level2", "Level 2 - Pipe Dreams",
          "Tight, dark and loud with pipes. A bad place to be found.",
          cells=21, cell_size=5.0, height=3.0,
          wall="grime_wall", wall_alt="rust", floor="grime", floor_alt="grime",
          ceiling="grime", lamp="lamp_sick", openness=0.3, lamp_chance=0.34, pipes=True),
    Level("level3", "Level 3 - Electrical Station",
          "Thin brick hallways and switchgear that still hums. Mind the cabinets.",
          cells=19, cell_size=4.5, height=3.0,
          wall="brick", wall_alt="brick_dark", floor="concrete_floor", floor_alt="concrete_dark",
          ceiling="concrete_dark", lamp="lamp_caged", openness=0.25, lamp_chance=0.4,
          machines=True, pipes=True, lamp_style="caged"),
    Level("level4", "Level 4 - Abandoned Office",
          "Cubicles to the horizon, dead monitors, and carpet that swallows every footstep.",
          cells=17, cell_size=6.5, height=3.1,
          wall="office_wall", wall_alt="office_wall", floor="office_carpet",
          floor_alt="office_carpet", ceiling="ceiling_tile", lamp="fluorescent",
          openness=0.75, lamp_chance=0.55, desks=True, partitions=0.55),
    Level("level5", "Level 5 - Terror Hotel",
          "Faded paper, red carpet, and a door every few paces. None of them are yours.",
          cells=15, cell_size=5.5, height=3.4,
          wall="hotel_paper", wall_alt="hotel_paper_alt", floor="hotel_carpet",
          floor_alt="hotel_trim", ceiling="ceiling_tile", lamp="lamp_warm",
          openness=0.35, lamp_chance=0.45, doors=True, lamp_style="sconce"),
    Level("level6", "Level 6 - Lights Out",
          "No light works here. Only the exit signs, and they lead nowhere.",
          cells=19, cell_size=5.0, height=3.0,
          wall="black_wall", wall_alt="black_wall", floor="black_floor", floor_alt="black_floor",
          ceiling="black_floor", lamp="exit_sign", openness=0.4, lamp_chance=0.0,
          exit_signs=True, lamp_style="none"),
    Level("level37", "Level 37 - The Poolrooms",
          "White tile, warm water to the ankle, and light from nowhere. Nothing hunts here.",
          cells=15, cell_size=7.0, height=3.6,
          wall="pool_tile", wall_alt="pool_tile_alt", floor="pool_floor", floor_alt="pool_tile_alt",
          ceiling="pool_glow", lamp="pool_glow", openness=0.8, lamp_chance=0.0,
          water=0.22, ceiling_glow=True, lamp_style="none"),
]


# --- Carving the grid ----------------------------------------------------------------------------

def carve(level, rng):
    """Carves a level into a doubled character map: '#' solid, '.' open.

    A depth-first carve gives corridors that always connect. Knocking extra walls out afterwards
    ("braiding") turns some of those corridors into loops and open rooms, which is what makes a
    place feel like the Backrooms rather than a puzzle - and means you can be cut off, but never
    trapped in a dead end with one way out.
    """
    n = level.cells
    width = height = n * 2 + 1
    grid = [["#"] * width for _ in range(height)]

    def cell(cx, cz):
        return 2 * cz + 1, 2 * cx + 1

    start = (rng.randrange(n), rng.randrange(n))
    stack = [start]
    seen = {start}
    row, col = cell(*start)
    grid[row][col] = "."
    while stack:
        cx, cz = stack[-1]
        neighbours = []
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, nz = cx + dx, cz + dz
            if 0 <= nx < n and 0 <= nz < n and (nx, nz) not in seen:
                neighbours.append((nx, nz, dx, dz))
        if not neighbours:
            stack.pop()
            continue
        nx, nz, dx, dz = rng.choice(neighbours)
        row, col = cell(nx, nz)
        grid[row][col] = "."
        # The square between the two cells is the wall that comes out.
        grid[2 * cz + 1 + dz][2 * cx + 1 + dx] = "."
        seen.add((nx, nz))
        stack.append((nx, nz))

    # Braiding: open extra walls, which merges cells into rooms and makes loops.
    for cz in range(n):
        for cx in range(n):
            for dx, dz in ((1, 0), (0, 1)):
                nx, nz = cx + dx, cz + dz
                if nx >= n or nz >= n:
                    continue
                if rng.random() < level.openness:
                    grid[2 * cz + 1 + dz][2 * cx + 1 + dx] = "."

    # Corner posts between four open squares look like floating pillars; in the open levels they
    # are left in on purpose (that is the pillar hall), elsewhere they come out.
    if not level.pillars:
        for row in range(2, height - 1, 2):
            for col in range(2, width - 1, 2):
                if (grid[row - 1][col] == "." and grid[row + 1][col] == "."
                        and grid[row][col - 1] == "." and grid[row][col + 1] == "."):
                    if rng.random() < 0.75:
                        grid[row][col] = "."

    level.grid = ["".join(row) for row in grid]
    return level.grid


def open_cells(level):
    """Every open square of the map, as (col, row) pairs."""
    return [(col, row)
            for row, line in enumerate(level.grid)
            for col, square in enumerate(line)
            if square == "."]


def square_to_world(level, col, row):
    """The centre of a map square, in world metres. A square is half a cell across."""
    half = level.cell_size / 2
    x = level.origin_x + (col - level.cells) * half
    z = (row - level.cells) * half
    return x, z


def best_view_yaw(level, square):
    """Which way to face from a square: down the longest clear run from it.

    Spawning nose-to-wall is a poor way to arrive somewhere, and on a tight level it is the
    likely one - three of the four ways out of a square are often solid.

    Yaw follows the engine: 0 looks down -Z, and turning positive swings towards +X.
    """
    col, row = square
    ways = [(0, -1, 0.0), (1, 0, 90.0), (0, 1, 180.0), (-1, 0, 270.0)]
    best_yaw, best_run = 0.0, -1
    for dcol, drow, yaw in ways:
        run = 0
        while level.grid[row + drow * (run + 1)][col + dcol * (run + 1)] == ".":
            run += 1
            if run > 40:
                break
        if run > best_run:
            best_run, best_yaw = run, yaw
    return best_yaw


def farthest_pair(level):
    """Two open squares as far apart as the map allows: where the player and the monster start.

    Walking distance, not straight-line - two squares can be metres apart through a wall.
    """
    cells = open_cells(level)
    start = cells[0]
    far = _breadth_first(level, start)[0]
    other = _breadth_first(level, far)[0]
    return far, other


def _breadth_first(level, start):
    """Open squares ordered by how far they are to walk from `start`, farthest first."""
    grid = level.grid
    seen = {start: 0}
    queue = [start]
    order = []
    while queue:
        col, row = queue.pop(0)
        order.append(((col, row), seen[(col, row)]))
        for dcol, drow in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            ncol, nrow = col + dcol, row + drow
            if (0 <= nrow < len(grid) and 0 <= ncol < len(grid[0])
                    and grid[nrow][ncol] == "." and (ncol, nrow) not in seen):
                seen[(ncol, nrow)] = seen[(col, row)] + 1
                queue.append((ncol, nrow))
    order.sort(key=lambda item: -item[1])
    return [square for square, _ in order]


# --- Building the geometry ----------------------------------------------------------------------

def build_shell(level, rng):
    """Floor, ceiling and the wall around the outside - the parts that are one big box each."""
    b = Builder()
    span = level.span
    cx = level.origin_x
    thickness = 0.5
    b.box(level.floor, (cx, -0.25, 0), (span + thickness * 2, 0.5, span + thickness * 2))
    b.box(level.ceiling, (cx, level.height + 0.25, 0),
          (span + thickness * 2, 0.5, span + thickness * 2))
    half = span / 2
    for sign in (-1, 1):
        b.box(level.wall, (cx + sign * (half + thickness / 2), level.height / 2, 0),
              (thickness, level.height, span + thickness * 2))
        b.box(level.wall, (cx, level.height / 2, sign * (half + thickness / 2)),
              (span + thickness * 2, level.height, thickness))

    # Stains and worn patches on the floor: flat, just for something to look at.
    for _ in range(int(level.cells * 2.5)):
        x = cx + rng.uniform(-half, half)
        z = rng.uniform(-half, half)
        size = rng.uniform(1.0, 3.5)
        b.box(level.floor_alt, (x, 0.005, z), (size, 0.01, size * rng.uniform(0.6, 1.4)))

    return b


def build_water(level):
    """The Poolrooms' water, as its own object so it can have collision switched off.

    Left on the shell it would be a solid slab and you would walk about on top of the pool,
    which is a very different level.
    """
    b = Builder()
    b.box("pool_water", (level.origin_x, level.water / 2, 0), (level.span, level.water, level.span))
    return b


def build_walls(level, rng, chunk_col, chunk_row):
    """The inner walls of one chunk of cells, plus its lights and props.

    Returns None when the chunk holds nothing, so empty corners of the map cost nothing.
    """
    b = Builder()
    grid = level.grid
    width = len(grid[0])
    half_cell = level.cell_size / 2
    thickness = 0.35
    empty = True

    col_from, col_to = chunk_col * CHUNK * 2, min((chunk_col + 1) * CHUNK * 2, width)
    row_from, row_to = chunk_row * CHUNK * 2, min((chunk_row + 1) * CHUNK * 2, width)

    for row in range(row_from, row_to):
        for col in range(col_from, col_to):
            if grid[row][col] != "#":
                continue
            # Skip the outer ring: the shell already walls the level in.
            if row == 0 or col == 0 or row == len(grid) - 1 or col == width - 1:
                continue
            x, z = square_to_world(level, col, row)
            material = level.wall_alt if rng.random() < 0.18 else level.wall
            if level.partitions and rng.random() < level.partitions:
                # A cubicle divider: you can see over it, which is worse, not better.
                b.box("cubicle", (x, 0.80, z), (half_cell + thickness, 1.60, half_cell + thickness))
            else:
                b.box(material, (x, level.height / 2, z),
                      (half_cell + thickness, level.height, half_cell + thickness))
            empty = False

    # Ceiling lights, over open squares.
    for row in range(row_from, row_to):
        for col in range(col_from, col_to):
            if grid[row][col] != "." or row % 2 == 0 or col % 2 == 0:
                continue
            if rng.random() > level.lamp_chance:
                continue
            x, z = square_to_world(level, col, row)
            if level.lamp_style == "caged":
                b.box(level.lamp, (x, level.height - 0.22, z), (0.30, 0.16, 0.30))
                b.box("steel", (x, level.height - 0.10, z), (0.38, 0.08, 0.38))
            elif level.lamp_style == "sconce":
                # On the wall rather than the ceiling, which is what a hotel corridor has.
                b.box(level.lamp, (x, 2.05, z), (0.26, 0.30, 0.16))
                b.box("hotel_trim", (x, 2.24, z), (0.34, 0.06, 0.22))
            elif level.pipes:
                b.box(level.lamp, (x, level.height - 0.18, z), (0.35, 0.12, 0.35))
            else:
                b.box(level.lamp, (x, level.height - 0.12, z), (1.6, 0.1, 0.35))
                b.box(level.ceiling, (x, level.height - 0.05, z), (1.9, 0.08, 0.6))
            empty = False

    # Level 3: switchgear cabinets shoved against a wall, with a warning stripe.
    if level.machines:
        for row in range(row_from, row_to):
            for col in range(col_from, col_to):
                if grid[row][col] != "." or rng.random() > 0.10:
                    continue
                if not any(grid[row + dr][col + dc] == "#"
                           for dc, dr in ((1, 0), (-1, 0), (0, 1), (0, -1))
                           if 0 <= row + dr < len(grid) and 0 <= col + dc < width):
                    continue
                x, z = square_to_world(level, col, row)
                b.box("switchgear", (x, 0.85, z), (1.0, 1.70, 0.55))
                b.box("warning", (x, 1.55, z - 0.30), (0.9, 0.12, 0.03))
                empty = False

    # Level 4: a desk with a dead monitor on it, here and there in the open.
    if level.desks:
        for row in range(row_from, row_to):
            for col in range(col_from, col_to):
                if grid[row][col] != "." or row % 2 == 0 or col % 2 == 0:
                    continue
                if rng.random() > 0.35:
                    continue
                x, z = square_to_world(level, col, row)
                b.box("desk", (x, 0.36, z), (1.5, 0.06, 0.75))
                for dx in (-0.65, 0.65):
                    b.box("desk", (x + dx, 0.18, z), (0.08, 0.36, 0.70))
                b.box("monitor", (x, 0.58, z + 0.12), (0.46, 0.38, 0.16))
                empty = False

    # Level 5: a door in the wall, over and over, none of which open.
    if level.doors:
        for row in range(row_from, row_to):
            for col in range(col_from, col_to):
                if grid[row][col] != "#" or rng.random() > 0.30:
                    continue
                # A door needs an open square to face onto.
                facing = [(dc, dr) for dc, dr in ((1, 0), (-1, 0), (0, 1), (0, -1))
                          if 0 <= row + dr < len(grid) and 0 <= col + dc < width
                          and grid[row + dr][col + dc] == "."]
                if not facing:
                    continue
                dcol, drow = facing[rng.randrange(len(facing))]
                x, z = square_to_world(level, col, row)
                edge = (half_cell + thickness) / 2
                dx, dz = dcol * edge, drow * edge
                deep = 0.06
                size = (0.95, 2.05, deep) if dcol == 0 else (deep, 2.05, 0.95)
                b.box("door_wood", (x + dx, 1.03, z + dz), size)
                knob = 0.07
                b.box("door_brass", (x + dx + dcol * 0.04 - dz * 0.34,
                                     0.98, z + dz + drow * 0.04 + dx * 0.34),
                      (knob, knob, knob))
                empty = False

    # Level 6: the exit signs, which are the only thing still lit.
    if level.exit_signs:
        for row in range(row_from, row_to):
            for col in range(col_from, col_to):
                if grid[row][col] != "." or row % 2 == 0 or col % 2 == 0:
                    continue
                if rng.random() > 0.16:
                    continue
                x, z = square_to_world(level, col, row)
                b.box("exit_sign", (x, level.height - 0.45, z), (0.52, 0.22, 0.05))
                empty = False

    # Pipes along the ceiling of the deepest level.
    if level.pipes:
        for row in range(row_from, row_to, 2):
            if rng.random() < 0.45:
                continue
            z = square_to_world(level, 0, row)[1]
            x0, _ = square_to_world(level, col_from, row)
            x1, _ = square_to_world(level, max(col_from, col_to - 1), row)
            if x1 <= x0:
                continue
            material = "rust" if rng.random() < 0.3 else "pipe"
            b.cylinder(material, (x0, level.height - 0.4, z), 0.12, 0.0, segments=8)
            b.box(material, ((x0 + x1) / 2, level.height - 0.4, z), (x1 - x0, 0.24, 0.24))
            empty = False

    return None if empty else b


def build_scrawler(rng=None):
    """**Scrawler** - Level 0. A tall black shape that looks like someone scribbled a person
    onto the world and never rubbed it out: ragged edges, limbs too long, and a torn red mouth.

    The ragged look is half geometry and half texture - slivers stuck out at the silhouette so
    the outline is never clean, over a body texture of black scratch marks.
    """
    rng = rng or random.Random(5)
    b = Builder()
    b.cylinder("scrawl_body", (-0.10, 0.0, 0), 0.075, 1.02, segments=7)          # legs
    b.cylinder("scrawl_body", (0.10, 0.0, 0), 0.075, 1.02, segments=7)
    b.box("scrawl_body", (0, 1.18, 0), (0.30, 0.26, 0.19))                       # hips
    b.box("scrawl_body", (0, 1.62, 0), (0.36, 0.66, 0.21))                       # chest
    b.box("scrawl_wound", (0, 1.55, -0.10), (0.10, 0.34, 0.03))                  # a split down it
    b.cylinder("scrawl_body", (-0.24, 0.92, 0), 0.055, 1.02, segments=7)         # arms to the shin
    b.cylinder("scrawl_body", (0.24, 0.92, 0), 0.055, 1.02, segments=7)
    for side in (-1, 1):                                                          # long fingers
        for finger in range(3):
            b.box("scrawl_body", (side * 0.24 + (finger - 1) * 0.035, 0.80, 0.02),
                  (0.022, 0.24, 0.022))
    b.box("scrawl_body", (0, 2.05, 0), (0.26, 0.30, 0.22))                       # head
    b.box("scrawl_wound", (0, 1.97, -0.11), (0.17, 0.09, 0.02))                  # the mouth
    for side in (-1, 1):
        b.box("scrawl_eye", (side * 0.06, 2.12, -0.112), (0.045, 0.020, 0.012))

    # The scribble: slivers standing out of the silhouette, never the same twice.
    for _ in range(34):
        height = rng.uniform(0.35, 2.15)
        side = -1 if rng.random() < 0.5 else 1
        reach = rng.uniform(0.04, 0.20)
        b.box("scrawl_body",
              (side * (0.16 + reach / 2), height, rng.uniform(-0.10, 0.10)),
              (reach, rng.uniform(0.02, 0.09), rng.uniform(0.015, 0.05)))
    return b


def build_faceling():
    """**Faceling** - Level 0. A person, near enough: the right height, the right clothes, and
    no face at all. Pale skin, a dark suit, and it stands too still.

    Every entity is built standing on the origin facing -Z, which is the way the engine points
    a character, and each is one model with its own materials.
    """
    b = Builder()
    b.cylinder("faceling_suit", (-0.11, 0.0, 0), 0.085, 0.86, segments=10)      # legs
    b.cylinder("faceling_suit", (0.11, 0.0, 0), 0.085, 0.86, segments=10)
    b.box("faceling_suit", (0, 1.22, 0), (0.40, 0.76, 0.23))                    # torso
    b.box("faceling_suit", (0, 1.56, 0), (0.50, 0.10, 0.24))                    # shoulders
    b.cylinder("faceling_suit", (-0.26, 0.72, 0), 0.062, 0.84, segments=8)      # arms, too long
    b.cylinder("faceling_suit", (0.26, 0.72, 0), 0.062, 0.84, segments=8)
    b.cylinder("faceling_skin", (-0.26, 0.48, 0), 0.055, 0.25, segments=8)      # hands, past the hip
    b.cylinder("faceling_skin", (0.26, 0.48, 0), 0.055, 0.25, segments=8)
    b.cylinder("faceling_skin", (0, 1.61, 0), 0.055, 0.10, segments=8)          # neck
    b.box("faceling_skin", (0, 1.85, 0), (0.21, 0.29, 0.21))                    # head - blank
    return b


def build_hound():
    """**Hound** - Level 1. Four legs, no meat on it, and a head that is mostly jaw. Low and
    quick, which is the whole problem with the wider halls down there."""
    b = Builder()
    b.box("hound_hide", (0, 0.72, 0.05), (0.38, 0.40, 1.05))                    # body
    b.box("hound_hide", (0, 0.66, 0.10), (0.42, 0.26, 0.70))                    # ribs, sagging
    b.box("hound_hide", (0, 0.80, -0.58), (0.26, 0.26, 0.30))                   # neck
    b.box("hound_hide", (0, 0.74, -0.88), (0.22, 0.24, 0.40))                   # skull
    b.box("hound_hide", (0, 0.64, -1.02), (0.19, 0.12, 0.26))                   # snout
    b.box("hound_teeth", (0, 0.60, -1.13), (0.17, 0.05, 0.05))                  # teeth
    b.box("hound_eye", (-0.07, 0.80, -1.10), (0.07, 0.05, 0.02))                # eyes, on its face
    b.box("hound_eye", (0.07, 0.80, -1.10), (0.07, 0.05, 0.02))
    for x in (-0.15, 0.15):                                                     # four thin legs
        b.cylinder("hound_hide", (x, 0.0, -0.36), 0.05, 0.56, segments=8)
        b.cylinder("hound_hide", (x, 0.0, 0.42), 0.055, 0.54, segments=8)
    b.box("hound_hide", (0, 0.80, 0.62), (0.08, 0.08, 0.36))                    # tail
    return b


def build_smiler():
    """**Smiler** - Level 2. In the dark it is nothing but the grin and two eyes, which is the
    entire idea: the body is as close to black as a material gets, and only the face gives off
    light. Tall, and far too thin."""
    b = Builder()
    b.cylinder("smiler_body", (-0.10, 0.0, 0), 0.07, 1.05, segments=8)          # legs
    b.cylinder("smiler_body", (0.10, 0.0, 0), 0.07, 1.05, segments=8)
    b.box("smiler_body", (0, 1.50, 0), (0.34, 0.90, 0.20))                      # torso
    b.cylinder("smiler_body", (-0.22, 0.95, 0), 0.05, 1.00, segments=8)         # arms to the knee
    b.cylinder("smiler_body", (0.22, 0.95, 0), 0.05, 1.00, segments=8)
    b.box("smiler_body", (0, 2.10, 0), (0.30, 0.32, 0.22))                      # head

    # The grin: a row of teeth curving up at the corners, and two narrow eyes above it.
    for i in range(9):
        across = (i - 4) / 4.0
        x = across * 0.115
        lift = across * across * 0.035          # the corners of the mouth turn up
        b.box("smiler_teeth", (x, 2.02 + lift, -0.112), (0.021, 0.052, 0.012))
    for side in (-1, 1):
        b.box("smiler_eye", (side * 0.075, 2.18, -0.112), (0.055, 0.022, 0.012))
    return b


#: Which entity walks which level, and how it behaves there. The three are the ones that turn
#: up again and again in the Backrooms: a Faceling in the yellow rooms, a Hound loose in the
#: wider concrete halls, and a Smiler in the dark where nothing but its face is visible.
#: object name -> how to build its static model. Every creature any level names gets one of
#: these written out, wherever it is used, so a world without the rigged .glb files still has
#: something to show.
CREATURES = {
    "Scrawler": build_scrawler,
    "Faceling": build_faceling,
    "Hound": build_hound,
    "Smiler": build_smiler,
}

ENTITIES = {
    "level0": {
        "build": build_scrawler,
        # The thing everyone means when they say they saw something in the yellow rooms.
        "monster": {"name": "Scrawler", "object": "Scrawler", "model": "assets/scrawler.glb",
                    "speed": 3.0, "chaseSpeed": 4.4, "sightRange": 24.0, "sightAngle": 80.0,
                    "hearingRange": 23.0, "killRange": 1.15, "giveUpSeconds": 8.0},
    },
    "level1": {
        "build": build_hound,
        # Fast, sharp-eared, but short-sighted: it finds you by sound and then runs you down.
        "monster": {"name": "Hound", "object": "Hound", "model": "assets/hound.glb",
                    "speed": 3.4, "chaseSpeed": 5.1, "sightRange": 19.0, "sightAngle": 100.0,
                    "hearingRange": 27.0, "killRange": 1.2, "giveUpSeconds": 9.0},
    },
    "level2": {
        "build": build_smiler,
        # Slow, narrow-eyed, and it does not lose interest: it sees a long way in the dark.
        "monster": {"name": "Smiler", "object": "Smiler", "model": "assets/smiler.glb",
                    "speed": 2.6, "chaseSpeed": 4.6, "sightRange": 30.0, "sightAngle": 60.0,
                    "hearingRange": 18.0, "killRange": 1.15, "giveUpSeconds": 13.0},
    },
    "level3": {
        "build": None,      # the Hound again, and the tight brick halls suit it
        "monster": {"name": "Hound", "object": "Hound", "model": "assets/hound.glb",
                    "speed": 3.6, "chaseSpeed": 5.3, "sightRange": 17.0, "sightAngle": 110.0,
                    "hearingRange": 28.0, "killRange": 1.2, "giveUpSeconds": 10.0},
    },
    "level4": {
        "build": None,      # Facelings wander an office the way people used to
        "monster": {"name": "Faceling", "object": "Faceling", "model": "assets/faceling.glb",
                    "speed": 3.1, "chaseSpeed": 4.3, "sightRange": 30.0, "sightAngle": 85.0,
                    "hearingRange": 20.0, "killRange": 1.1, "giveUpSeconds": 7.0},
    },
    "level5": {
        "build": None,      # something in the corridor, and every door is locked
        "monster": {"name": "Smiler", "object": "Smiler", "model": "assets/smiler.glb",
                    "speed": 2.9, "chaseSpeed": 4.8, "sightRange": 26.0, "sightAngle": 70.0,
                    "hearingRange": 22.0, "killRange": 1.15, "giveUpSeconds": 12.0},
    },
    "level6": {
        "build": None,      # in the pitch dark, all you ever see of it is the grin
        "monster": {"name": "Smiler", "object": "Smiler", "model": "assets/smiler.glb",
                    "speed": 2.7, "chaseSpeed": 4.9, "sightRange": 34.0, "sightAngle": 65.0,
                    "hearingRange": 30.0, "killRange": 1.15, "giveUpSeconds": 16.0},
    },
    # Level 37 has no entry at all: the Poolrooms are empty, and that is the point of them.
}


def write_mtl(path):
    with open(path, "w") as f:
        f.write("# RoastEngine Backrooms materials\n")
        for name, (r, g, b) in MATERIALS.items():
            er, eg, eb = EMISSIVE.get(name, (0.0, 0.0, 0.0))
            f.write(f"newmtl {name}\nKd {r:.3f} {g:.3f} {b:.3f}\nKa 0 0 0\nKs 0 0 0\n"
                    f"Ke {er:.3f} {eg:.3f} {eb:.3f}\nd {TRANSPARENT.get(name, 1.0):.2f}\n")
            # A textured material keeps Kd as the colour to fall back on if the image goes
            # missing; the engine uses the image itself and bakes only the shading.
            if name in TEXTURED:
                f.write(f"map_Kd {TEXTURED[name][0]}\n")
            f.write("\n")


# --- Textures -------------------------------------------------------------------------------

def periodic_noise(size, roughness, seed):
    """Noise that tiles, and that has no direction to it.

    Built by filtering white noise in the frequency domain: a Fourier transform treats an image
    as though it repeats for ever, so anything built this way meets itself exactly at the edges
    and can be tiled without a seam. Damping the high frequencies (the 1/f^roughness curve) is
    what turns static into the soft blotching of concrete or damp - and because the filter only
    depends on how fine a detail is, never on which way it runs, the result has no grain or
    streaks running across it.

    @param roughness bigger = broader, softer blotches; smaller = finer, grittier
    """
    rng = np.random.default_rng(seed)
    spectrum = np.fft.fft2(rng.normal(size=(size, size)))
    frequency_y = np.fft.fftfreq(size)[:, None]
    frequency_x = np.fft.fftfreq(size)[None, :]
    radius = np.sqrt(frequency_x ** 2 + frequency_y ** 2)
    radius[0, 0] = 1                      # the flat average, damped out below
    falloff = radius ** -roughness
    falloff[0, 0] = 0
    out = np.real(np.fft.ifft2(spectrum * falloff))
    span = np.abs(out).max()
    return out / span if span else out


def tint(size, colour, noise, strength):
    """A flat colour lifted and dropped by noise, as an RGB image array."""
    base = np.zeros((size, size, 3))
    for channel in range(3):
        base[:, :, channel] = colour[channel] * (1 + noise * strength)
    return base


def save(array, path):
    Image.fromarray(np.clip(array, 0, 255).astype(np.uint8), "RGB").save(path)


def seamless_wallpaper(source, size=512):
    """Crops the wallpaper sample to a whole number of its own repeats, so it tiles.

    The sample is a photograph of a pattern, not a tile: its edges nearly line up but not
    quite. Rather than blur the join, this finds the distance at which the pattern repeats
    and cuts a square of exactly that many repeats out of the middle.
    """
    image = np.asarray(Image.open(source).convert("RGB"), dtype=float)
    height, width, _ = image.shape

    def repeat_of(axis):
        limit = (width if axis == 1 else height) - 20
        best, at = None, 20
        for shift in range(20, limit):
            if axis == 1:
                difference = np.abs(image[:, :width - shift] - image[:, shift:]).mean()
            else:
                difference = np.abs(image[:height - shift, :] - image[shift:, :]).mean()
            if best is None or difference < best:
                best, at = difference, shift
        return at

    # The smallest repeat, then as many of them as fit in the shorter side.
    step = min(repeat_of(0), repeat_of(1))
    tiles = max(1, min(height, width) // step)
    side = step * tiles
    top = (height - side) // 2
    left = (width - side) // 2
    cropped = Image.fromarray(image[top:top + side, left:left + side].astype(np.uint8), "RGB")
    return cropped.resize((size, size), Image.LANCZOS), step, tiles


def write_textures(assets, wallpaper_source, size=512):
    """Writes every image the materials point at. Returns a note about the wallpaper."""
    note = "no wallpaper sample found - the yellow rooms fall back to flat colour"
    if os.path.isfile(wallpaper_source):
        paper, step, tiles = seamless_wallpaper(wallpaper_source, size)
        paper.save(os.path.join(assets, "wallpaper.png"))
        # The damp version is the same paper, darker and greener, so stained walls match.
        damp = np.asarray(paper, dtype=float) * np.array([0.78, 0.80, 0.62])
        save(damp, os.path.join(assets, "wallpaper_damp.png"))
        note = f"wallpaper: {tiles} repeats of {step}px from {os.path.basename(wallpaper_source)}"

    # Carpet: a dark olive weave, mottled by damp.
    weave = periodic_noise(size, roughness=0.9, seed=11)
    save(tint(size, (150, 134, 72), weave, 0.16), os.path.join(assets, "carpet.png"))

    # Ceiling: grubby tiles on a 2 x 2 grid, with the grid lines darker.
    speckle = periodic_noise(size, roughness=0.8, seed=17)
    ceiling = tint(size, (198, 192, 168), speckle, 0.10)
    line = max(1, size // 128)
    for edge in (0, size // 2):
        ceiling[edge:edge + line, :, :] *= 0.72
        ceiling[:, edge:edge + line, :] *= 0.72
    save(ceiling, os.path.join(assets, "ceiling.png"))

    # Concrete, in three shades: walls, the darker trim, and the floor.
    grain = periodic_noise(size, roughness=1.3, seed=23)
    save(tint(size, (134, 132, 126), grain, 0.13), os.path.join(assets, "concrete.png"))
    save(tint(size, (88, 88, 85), grain, 0.15), os.path.join(assets, "concrete_dark.png"))
    save(tint(size, (108, 106, 102), grain, 0.11), os.path.join(assets, "concrete_floor.png"))

    # Level 3: brick, courses offset row by row.
    brick = periodic_noise(size, roughness=1.1, seed=31)
    bricks = tint(size, (112, 79, 66), brick, 0.16)
    course, gap = size // 8, max(1, size // 128)
    for row in range(0, size, course):
        bricks[row:row + gap, :, :] *= 0.55                          # mortar between courses
        offset = (row // course % 2) * (course // 2)
        for column in range(offset, size + offset, course):
            bricks[row:row + course, column % size:(column % size) + gap, :] *= 0.6
    save(bricks, os.path.join(assets, "brick.png"))
    save(tint(size, (78, 55, 46), brick, 0.18), os.path.join(assets, "brick_dark.png"))

    # Level 4: office carpet tiles, flecked.
    fleck = periodic_noise(size, roughness=0.7, seed=37)
    office = tint(size, (74, 82, 90), fleck, 0.13)
    for edge in (0, size // 2):
        office[edge:edge + max(1, size // 256), :, :] *= 0.9
        office[:, edge:edge + max(1, size // 256), :] *= 0.9
    save(office, os.path.join(assets, "office_carpet.png"))
    save(tint(size, (182, 178, 162), periodic_noise(size, roughness=0.9, seed=41), 0.07),
         os.path.join(assets, "office_wall.png"))

    # Level 5: hotel paper with a faded vertical stripe, and a dark red carpet.
    paper = tint(size, (142, 102, 92), periodic_noise(size, roughness=1.0, seed=43), 0.10)
    for column in range(0, size, size // 8):
        paper[:, column:column + max(1, size // 96), :] *= 0.86
    save(paper, os.path.join(assets, "hotel_paper.png"))
    save(tint(size, (86, 42, 42), periodic_noise(size, roughness=0.8, seed=47), 0.16),
         os.path.join(assets, "hotel_carpet.png"))

    # Level 37: pristine white tile, the only thing in the Poolrooms that is not water.
    tiles = tint(size, (236, 240, 240), periodic_noise(size, roughness=0.6, seed=53), 0.02)
    grout = max(1, size // 128)
    for edge in range(0, size, size // 4):
        tiles[edge:edge + grout, :, :] *= 0.82
        tiles[:, edge:edge + grout, :] *= 0.82
    save(tiles, os.path.join(assets, "pool_tile.png"))
    save(tiles * 0.93, os.path.join(assets, "pool_floor.png"))

    # The deep level: wet, filthy, and barely lit.
    filth = periodic_noise(size, roughness=1.5, seed=29)
    save(tint(size, (86, 82, 70), filth, 0.22), os.path.join(assets, "grime.png"))
    save(tint(size, (74, 70, 61), filth, 0.26), os.path.join(assets, "grime_wall.png"))
    return note


# --- Writing the mod ------------------------------------------------------------------------------

def scene_object(ident, name, asset, collision=True, entity=False, position=(0, 0, 0)):
    x, y, z = position
    obj = {
        "id": ident,
        "name": name,
        "asset": asset,
        "position": {"x": x, "y": y, "z": z},
        "rotation": {"x": 0.0, "y": 0.0, "z": 0.0},
        "scale": 1.0,
        "collision": collision,
        "kills": False,
        "slippery": False,
        "script": "",
    }
    if entity:
        obj["entity"] = True
    return obj


def main():
    parser = argparse.ArgumentParser(description="Generate the Backrooms world mod.")
    parser.add_argument("out", nargs="?", default="mods/backrooms")
    parser.add_argument("--seed", type=int, default=20260922,
                        help="same seed, same levels - change it for a different place")
    parser.add_argument("--wallpaper",
                        default=os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                             "textures", "backrooms_wallpaper.png"),
                        help="the yellow wallpaper sample to tile the first level with")
    args = parser.parse_args()

    out = os.path.abspath(args.out)
    assets = os.path.join(out, "assets")
    os.makedirs(assets, exist_ok=True)
    os.makedirs(os.path.join(out, "backrooms"), exist_ok=True)
    os.makedirs(os.path.join(out, "video"), exist_ok=True)

    rng = random.Random(args.seed)
    write_mtl(os.path.join(assets, "backrooms.mtl"))
    wallpaper_note = write_textures(assets, args.wallpaper)

    objects = []
    level_configs = []
    placed_creatures = set()
    for index, level in enumerate(LEVELS):
        level.origin_x = (index - 1) * LEVEL_SPACING
        carve(level, rng)

        shell = build_shell(level, rng)
        shell_asset = f"{level.id}_shell.obj"
        shell.write(os.path.join(assets, shell_asset), "backrooms.mtl")
        objects.append(scene_object(f"{level.id}_shell", f"{level.name} Shell", shell_asset))

        if level.water:
            water_asset = f"{level.id}_water.obj"
            build_water(level).write(os.path.join(assets, water_asset), "backrooms.mtl")
            objects.append(scene_object(f"{level.id}_water", f"{level.name} Water", water_asset,
                                        collision=False))

        chunks = math.ceil(level.cells / CHUNK)
        for chunk_row in range(chunks):
            for chunk_col in range(chunks):
                walls = build_walls(level, rng, chunk_col, chunk_row)
                if walls is None:
                    continue
                asset = f"{level.id}_walls_{chunk_col}_{chunk_row}.obj"
                walls.write(os.path.join(assets, asset), "backrooms.mtl")
                objects.append(scene_object(
                    f"{level.id}_walls_{chunk_col}_{chunk_row}",
                    f"{level.name} Walls {chunk_col},{chunk_row}", asset))

        spawn_square, monster_square = farthest_pair(level)
        spawn_x, spawn_z = square_to_world(level, *spawn_square)
        monster_x, monster_z = square_to_world(level, *monster_square)
        # Somewhere to look at from the home screen: standing in the level, facing down a hall.
        view_square = open_cells(level)[len(open_cells(level)) // 2]
        view_x, view_z = square_to_world(level, *view_square)
        entity = ENTITIES.get(level.id)
        creature = entity["monster"]["object"] if entity else None
        if creature and creature not in placed_creatures:
            placed_creatures.add(creature)
            asset = f"{creature.lower()}.obj"
            CREATURES[creature]().write(os.path.join(assets, asset), "backrooms.mtl")
            # Not an "entity" in the engine's sense: that flag hands an object to the entity
            # system, which would wander it about and ragdoll it. The monster drives this one.
            objects.append(scene_object(creature.lower() + "_entity", creature, asset,
                                        collision=False, position=(level.origin_x, 0, 0)))

        level_configs.append({
            "id": level.id,
            # A level with no entry has nothing in it. An empty object says so outright, so it
            # cannot quietly inherit the world's default creature.
            "monster": entity["monster"] if entity else {"name": "", "object": ""},
            "name": level.name,
            "description": level.description,
            "originX": level.origin_x,
            "cellSize": level.cell_size / 2,      # the map is the doubled kind: squares, not cells
            "height": level.height,
            "spawn": [round(spawn_x, 2), EYE, round(spawn_z, 2)],
            "spawnYaw": best_view_yaw(level, spawn_square),
            "monsterSpawn": [round(monster_x, 2), 0.0, round(monster_z, 2)],
            "menuView": {"eye": [round(view_x, 2), EYE, round(view_z, 2)],
                         "yaw": best_view_yaw(level, view_square)},
            "grid": level.grid,
        })

    scene = {
        "name": "backrooms",
        "skyColor": [0.02, 0.02, 0.02],
        "music": "",
        "groundPlatform": False,
        "spawn": list(level_configs[0]["menuView"]["eye"]),
        "objects": objects,
    }
    with open(os.path.join(out, "scene.json"), "w") as f:
        json.dump(scene, f, indent=2)

    config = {
        "name": "The Backrooms",
        "menu": {
            "title": "THE BACKROOMS",
            "subtitle": "You noclipped out of reality.",
            "playLabel": "Enter",
            "intro": "video/BK_INTRO.mp4",
            "music": "audio/theme.mp3",
        },
        # What a level gets if it names no monster of its own; each of ours does.
        "monster": dict(ENTITIES["level0"]["monster"]),
        "levels": level_configs,
    }
    with open(os.path.join(out, "backrooms", "level.json"), "w") as f:
        json.dump(config, f, indent=2)

    # The intro is the one thing this script cannot make: leave a note where it goes.
    with open(os.path.join(out, "video", "README.txt"), "w") as f:
        f.write("Put BK_INTRO.mp4 in this folder.\n\n"
                "It plays full screen when you press Enter on the home screen, and any key\n"
                "skips it. H.264 in an .mp4 is what the engine can decode (that is what most\n"
                "editors and phones produce); HEVC/H.265 is not, and is simply skipped.\n\n"
                "Sound: an mp4's audio is usually AAC, which the engine cannot decode, so the\n"
                "intro plays silent unless you drop BK_INTRO.ogg in beside it - that is played\n"
                "alongside the video.\n\n"
                "With no video here, pressing Enter just starts the level.\n")

    mod = {
        "name": "The Backrooms",
        "nameId": "backrooms",
        "version": "1.0.0",
        "type": "world",
        "engine": "RoastEngine 0.3",
        "description": ("Three levels of the Backrooms, a home screen of its own, and something "
                        "down there with you. Drop BK_INTRO.mp4 into video/ and it plays when "
                        "you press Enter."),
    }
    with open(os.path.join(out, "mod.json"), "w") as f:
        json.dump(mod, f, indent=2)

    print(f"wrote {out}")
    for level, config in zip(LEVELS, level_configs):
        open_count = sum(line.count(".") for line in level.grid)
        print(f"  {level.name}: {level.cells}x{level.cells} cells, {level.span:.0f}m across, "
              f"{open_count} open squares")
    print(f"  {len(objects)} objects")
    print(f"  {wallpaper_note}")


if __name__ == "__main__":
    main()
