#!/usr/bin/env python3
"""Builds rigged, animated versions of the Backrooms entities with Blender.

    /Applications/Blender.app/Contents/MacOS/Blender --background --python tools/make_entity_rigs.py

Each entity gets two files:

  * ``rigs/backrooms/<name>.blend`` - the one to open and animate. Mesh, armature, materials and
    the starter actions are all in there.
  * ``mods/backrooms/assets/<name>.glb`` - what the game loads, exported from the same data.

**Re-exporting after you animate.** In the .blend: File > Export > glTF 2.0, format glB, and
under Animation set Mode to *Actions* so every action comes across as its own clip. Save it over
the .glb in the mod's assets folder. The engine looks for clips by name - ``idle``, ``walk``,
``chase``, ``attack`` and ``death`` - and falls back to the first clip in the file for anything
missing, so extra actions are harmless and renaming one is all it takes to swap one in.
``attack`` is what plays when it reaches you; ``death`` is for the creature itself dying, which
nothing in the game does to it yet - it is there for when something can.

**Facing.** Blender's +Y becomes -Z once glTF converts to Y-up, and -Z is the way the engine
points a character, so everything here is built looking down +Y. Everything stands on Z=0, which
becomes the floor.

**Weights.** Each part of the body is bound rigidly to one bone, weight 1. On blocky characters
that beats automatic weights: joints stay crisp, nothing bleeds between limbs, and an animation
does exactly what the pose says.
"""

import math
import os
import sys

import bpy
from mathutils import Vector

PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BLEND_DIR = os.path.join(PROJECT, "rigs", "backrooms")
GLB_DIR = os.path.join(PROJECT, "mods", "backrooms", "assets")

#: name -> (rgb, emission strength). Matched to the materials the .obj versions use.
MATERIALS = {
    "faceling_skin": ((0.80, 0.74, 0.67), 0.0),
    "faceling_suit": ((0.13, 0.12, 0.14), 0.0),
    "hound_hide": ((0.30, 0.26, 0.23), 0.0),
    "hound_hair": ((0.05, 0.04, 0.04), 0.0),
    "hound_teeth": ((0.86, 0.83, 0.74), 0.0),
    "hound_eye": ((1.00, 0.86, 0.45), 4.0),
    "smiler_body": ((0.02, 0.02, 0.02), 0.0),
    "smiler_teeth": ((1.00, 0.98, 0.92), 5.0),
    "smiler_eye": ((1.00, 0.95, 0.85), 5.0),
    "scrawl_body": ((0.03, 0.03, 0.04), 0.0),
    "scrawl_wound": ((0.42, 0.09, 0.06), 0.0),
    "scrawl_eye": ((0.95, 0.40, 0.22), 4.0),
}


# ---------------------------------------------------------------------------
# Blender odds and ends
# ---------------------------------------------------------------------------

def clear_scene():
    bpy.ops.wm.read_factory_settings(use_empty=True)


def material(name):
    if name in bpy.data.materials:
        return bpy.data.materials[name]
    (r, g, b), emission = MATERIALS[name]
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    bsdf = mat.node_tree.nodes["Principled BSDF"]
    bsdf.inputs["Base Color"].default_value = (r, g, b, 1.0)
    bsdf.inputs["Roughness"].default_value = 0.85
    if emission:
        bsdf.inputs["Emission Color"].default_value = (r, g, b, 1.0)
        bsdf.inputs["Emission Strength"].default_value = emission
    return mat


def box(parts, material_name, bone, centre, size):
    """Records one box of the body: where it is, and which bone carries it."""
    parts.append({"material": material_name, "bone": bone, "centre": centre, "size": size})


def build_mesh(name, parts):
    """Turns the recorded boxes into one mesh, with a vertex group per bone."""
    vertices, faces, face_materials, groups = [], [], [], {}
    corners = [(-1, -1, -1), (1, -1, -1), (1, 1, -1), (-1, 1, -1),
               (-1, -1, 1), (1, -1, 1), (1, 1, 1), (-1, 1, 1)]
    quads = [(0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4), (2, 3, 7, 6), (1, 2, 6, 5), (3, 0, 4, 7)]
    for part in parts:
        base = len(vertices)
        cx, cy, cz = part["centre"]
        sx, sy, sz = (s / 2 for s in part["size"])
        for dx, dy, dz in corners:
            vertices.append((cx + dx * sx, cy + dy * sy, cz + dz * sz))
        for quad in quads:
            faces.append(tuple(base + i for i in quad))
            face_materials.append(part["material"])
        groups.setdefault(part["bone"], []).extend(range(base, base + 8))

    mesh = bpy.data.meshes.new(name + "_mesh")
    mesh.from_pydata(vertices, [], faces)
    mesh.update()
    obj = bpy.data.objects.new(name, mesh)
    bpy.context.collection.objects.link(obj)

    slots = []
    for material_name in dict.fromkeys(part["material"] for part in parts):
        obj.data.materials.append(material(material_name))
        slots.append(material_name)
    for index, face in enumerate(mesh.polygons):
        face.material_index = slots.index(face_materials[index])
        face.use_smooth = False

    for bone, indices in groups.items():
        obj.vertex_groups.new(name=bone).add(indices, 1.0, "REPLACE")
    return obj


def build_armature(name, bones):
    """Creates the skeleton. Bones are (name, head, tail, parent)."""
    armature = bpy.data.armatures.new(name + "_armature")
    rig = bpy.data.objects.new(name + "_rig", armature)
    bpy.context.collection.objects.link(rig)
    bpy.context.view_layer.objects.active = rig
    bpy.ops.object.mode_set(mode="EDIT")
    for bone_name, head, tail, parent in bones:
        bone = armature.edit_bones.new(bone_name)
        bone.head = Vector(head)
        bone.tail = Vector(tail)
        if parent:
            bone.parent = armature.edit_bones[parent]
            bone.use_connect = False
    bpy.ops.object.mode_set(mode="OBJECT")
    return rig


def bind(mesh_obj, rig):
    """Rigid binding: the vertex groups made above already say which bone owns what."""
    modifier = mesh_obj.modifiers.new("Armature", "ARMATURE")
    modifier.object = rig
    mesh_obj.parent = rig


def key(rig, action, bone, frame, rotation=None, location=None):
    """One keyframe on one bone. Rotation is (x, y, z) in degrees."""
    rig.animation_data.action = action
    pose_bone = rig.pose.bones[bone]
    pose_bone.rotation_mode = "XYZ"
    if rotation is not None:
        pose_bone.rotation_euler = [math.radians(a) for a in rotation]
        pose_bone.keyframe_insert("rotation_euler", frame=frame)
    if location is not None:
        pose_bone.location = Vector(location)
        pose_bone.keyframe_insert("location", frame=frame)


def new_action(rig, name):
    if rig.animation_data is None:
        rig.animation_data_create()
    action = bpy.data.actions.new(name)
    action.use_fake_user = True          # kept in the .blend even while unused
    return action


# ---------------------------------------------------------------------------
# The Faceling: a person's shape, and no face
# ---------------------------------------------------------------------------

def faceling():
    parts = []
    box(parts, "faceling_suit", "thigh.L", (-0.11, 0, 0.62), (0.17, 0.18, 0.50))
    box(parts, "faceling_suit", "thigh.R", (0.11, 0, 0.62), (0.17, 0.18, 0.50))
    box(parts, "faceling_suit", "shin.L", (-0.11, 0, 0.20), (0.15, 0.16, 0.42))
    box(parts, "faceling_suit", "shin.R", (0.11, 0, 0.20), (0.15, 0.16, 0.42))
    box(parts, "faceling_suit", "hips", (0, 0, 0.95), (0.34, 0.22, 0.20))
    box(parts, "faceling_suit", "chest", (0, 0, 1.28), (0.40, 0.23, 0.50))
    box(parts, "faceling_suit", "chest", (0, 0, 1.54), (0.50, 0.24, 0.10))
    box(parts, "faceling_suit", "upperarm.L", (-0.26, 0, 1.28), (0.13, 0.14, 0.44))
    box(parts, "faceling_suit", "upperarm.R", (0.26, 0, 1.28), (0.13, 0.14, 0.44))
    box(parts, "faceling_suit", "forearm.L", (-0.26, 0, 0.88), (0.12, 0.13, 0.40))
    box(parts, "faceling_suit", "forearm.R", (0.26, 0, 0.88), (0.12, 0.13, 0.40))
    box(parts, "faceling_skin", "hand.L", (-0.26, 0, 0.60), (0.11, 0.12, 0.22))
    box(parts, "faceling_skin", "hand.R", (0.26, 0, 0.60), (0.11, 0.12, 0.22))
    box(parts, "faceling_skin", "neck", (0, 0, 1.64), (0.11, 0.11, 0.10))
    box(parts, "faceling_skin", "head", (0, 0, 1.85), (0.21, 0.21, 0.29))

    bones = [
        ("root", (0, 0, 0), (0, 0, 0.12), None),
        ("hips", (0, 0, 0.92), (0, 0, 1.06), "root"),
        ("chest", (0, 0, 1.06), (0, 0, 1.56), "hips"),
        ("neck", (0, 0, 1.56), (0, 0, 1.70), "chest"),
        ("head", (0, 0, 1.70), (0, 0, 2.00), "neck"),
        ("upperarm.L", (-0.26, 0, 1.50), (-0.26, 0, 1.08), "chest"),
        ("forearm.L", (-0.26, 0, 1.08), (-0.26, 0, 0.70), "upperarm.L"),
        ("hand.L", (-0.26, 0, 0.70), (-0.26, 0, 0.50), "forearm.L"),
        ("upperarm.R", (0.26, 0, 1.50), (0.26, 0, 1.08), "chest"),
        ("forearm.R", (0.26, 0, 1.08), (0.26, 0, 0.70), "upperarm.R"),
        ("hand.R", (0.26, 0, 0.70), (0.26, 0, 0.50), "forearm.R"),
        ("thigh.L", (-0.11, 0, 0.88), (-0.11, 0, 0.42), "hips"),
        ("shin.L", (-0.11, 0, 0.42), (-0.11, 0, 0.02), "thigh.L"),
        ("thigh.R", (0.11, 0, 0.88), (0.11, 0, 0.42), "hips"),
        ("shin.R", (0.11, 0, 0.42), (0.11, 0, 0.02), "thigh.R"),
    ]
    return parts, bones, "humanoid"


# ---------------------------------------------------------------------------
# The Hound: four legs and a head that is mostly jaw
# ---------------------------------------------------------------------------

def hound():
    """**Hound** - Level 1 and 3. Not a dog: a human shape gone wrong, crawling on all fours,
    with shaggy black hair and a mouth far too big for the head it is in.

    The build follows the description on the wikis - bony limbs, long arms it walks on, claws,
    and jagged teeth - rather than the dog shape the name suggests.
    """
    parts = []
    # A human torso held horizontal, shoulders higher than hips.
    box(parts, "hound_hide", "spine", (0, -0.10, 0.62), (0.34, 0.62, 0.30))
    box(parts, "hound_hide", "chest", (0, 0.34, 0.70), (0.38, 0.40, 0.34))
    box(parts, "hound_hair", "chest", (0, 0.30, 0.86), (0.34, 0.34, 0.10))      # hair down its back
    box(parts, "hound_hide", "neck", (0, 0.58, 0.68), (0.16, 0.16, 0.16))
    box(parts, "hound_hide", "head", (0, 0.76, 0.66), (0.22, 0.26, 0.24))
    box(parts, "hound_hair", "head", (0, 0.74, 0.78), (0.24, 0.22, 0.10))       # shaggy on top
    box(parts, "hound_hide", "jaw", (0, 0.86, 0.52), (0.20, 0.22, 0.14))        # the oversized mouth
    for i in range(5):                                                           # jagged teeth
        box(parts, "hound_teeth", "jaw", ((i - 2) * 0.037, 0.95, 0.575), (0.022, 0.05, 0.045))
    box(parts, "hound_eye", "head", (-0.06, 0.88, 0.74), (0.045, 0.02, 0.03))
    box(parts, "hound_eye", "head", (0.06, 0.88, 0.74), (0.045, 0.02, 0.03))
    # Long arms it walks on, and legs folded up behind.
    for side, x in (("L", -0.16), ("R", 0.16)):
        box(parts, "hound_hide", f"upperarm.{side}", (x, 0.30, 0.46), (0.10, 0.12, 0.34))
        box(parts, "hound_hide", f"forearm.{side}", (x, 0.30, 0.16), (0.09, 0.11, 0.30))
        box(parts, "hound_hide", f"hand.{side}", (x, 0.34, 0.05), (0.12, 0.18, 0.06))
        for finger in range(3):                                                  # claws
            box(parts, "hound_teeth", f"hand.{side}",
                (x + (finger - 1) * 0.04, 0.44, 0.04), (0.018, 0.10, 0.018))
        box(parts, "hound_hide", f"thigh.{side}", (x, -0.34, 0.50), (0.12, 0.34, 0.30))
        box(parts, "hound_hide", f"shin.{side}", (x, -0.40, 0.20), (0.10, 0.12, 0.34))
        box(parts, "hound_hide", f"foot.{side}", (x, -0.32, 0.05), (0.11, 0.22, 0.06))

    bones = [
        ("root", (0, 0, 0), (0, 0.2, 0), None),
        ("spine", (0, -0.42, 0.62), (0, 0.16, 0.68), "root"),
        ("chest", (0, 0.16, 0.68), (0, 0.50, 0.70), "spine"),
        ("neck", (0, 0.50, 0.70), (0, 0.64, 0.68), "chest"),
        ("head", (0, 0.64, 0.68), (0, 0.92, 0.66), "neck"),
        ("jaw", (0, 0.74, 0.60), (0, 0.98, 0.52), "head"),
        ("upperarm.L", (-0.16, 0.40, 0.62), (-0.16, 0.30, 0.30), "chest"),
        ("forearm.L", (-0.16, 0.30, 0.30), (-0.16, 0.32, 0.10), "upperarm.L"),
        ("hand.L", (-0.16, 0.32, 0.10), (-0.16, 0.42, 0.02), "forearm.L"),
        ("upperarm.R", (0.16, 0.40, 0.62), (0.16, 0.30, 0.30), "chest"),
        ("forearm.R", (0.16, 0.30, 0.30), (0.16, 0.32, 0.10), "upperarm.R"),
        ("hand.R", (0.16, 0.32, 0.10), (0.16, 0.42, 0.02), "forearm.R"),
        ("thigh.L", (-0.16, -0.40, 0.62), (-0.16, -0.38, 0.32), "spine"),
        ("shin.L", (-0.16, -0.38, 0.32), (-0.16, -0.34, 0.08), "thigh.L"),
        ("foot.L", (-0.16, -0.34, 0.08), (-0.16, -0.24, 0.02), "shin.L"),
        ("thigh.R", (0.16, -0.40, 0.62), (0.16, -0.38, 0.32), "spine"),
        ("shin.R", (0.16, -0.38, 0.32), (0.16, -0.34, 0.08), "thigh.R"),
        ("foot.R", (0.16, -0.34, 0.08), (0.16, -0.24, 0.02), "shin.R"),
    ]
    return parts, bones, "crawler"


# ---------------------------------------------------------------------------
# The Smiler: a grin, two eyes, and as little else as possible
# ---------------------------------------------------------------------------

def smiler():
    parts = []
    box(parts, "smiler_body", "thigh.L", (-0.10, 0, 0.72), (0.13, 0.14, 0.62))
    box(parts, "smiler_body", "thigh.R", (0.10, 0, 0.72), (0.13, 0.14, 0.62))
    box(parts, "smiler_body", "shin.L", (-0.10, 0, 0.22), (0.12, 0.13, 0.46))
    box(parts, "smiler_body", "shin.R", (0.10, 0, 0.22), (0.12, 0.13, 0.46))
    box(parts, "smiler_body", "hips", (0, 0, 1.12), (0.30, 0.19, 0.22))
    box(parts, "smiler_body", "chest", (0, 0, 1.56), (0.34, 0.20, 0.72))
    box(parts, "smiler_body", "upperarm.L", (-0.22, 0, 1.46), (0.11, 0.12, 0.54))
    box(parts, "smiler_body", "upperarm.R", (0.22, 0, 1.46), (0.11, 0.12, 0.54))
    box(parts, "smiler_body", "forearm.L", (-0.22, 0, 1.00), (0.10, 0.11, 0.50))
    box(parts, "smiler_body", "forearm.R", (0.22, 0, 1.00), (0.10, 0.11, 0.50))
    box(parts, "smiler_body", "neck", (0, 0, 1.94), (0.10, 0.10, 0.06))
    box(parts, "smiler_body", "head", (0, 0, 2.10), (0.30, 0.22, 0.32))
    # The face: a row of teeth curving up at the corners, and two narrow eyes above it. On the
    # +Y side, which is the front here - the .obj version builds the same face on -Z.
    for i in range(9):
        across = (i - 4) / 4.0
        box(parts, "smiler_teeth", "head",
            (across * 0.115, 0.112, 2.02 + across * across * 0.035), (0.021, 0.012, 0.052))
    for side in (-1, 1):
        box(parts, "smiler_eye", "head", (side * 0.075, 0.112, 2.18), (0.055, 0.012, 0.022))

    bones = [
        ("root", (0, 0, 0), (0, 0, 0.12), None),
        ("hips", (0, 0, 1.03), (0, 0, 1.20), "root"),
        ("chest", (0, 0, 1.20), (0, 0, 1.92), "hips"),
        ("neck", (0, 0, 1.92), (0, 0, 1.98), "chest"),
        ("head", (0, 0, 1.98), (0, 0, 2.30), "neck"),
        ("upperarm.L", (-0.22, 0, 1.73), (-0.22, 0, 1.25), "chest"),
        ("forearm.L", (-0.22, 0, 1.25), (-0.22, 0, 0.75), "upperarm.L"),
        ("upperarm.R", (0.22, 0, 1.73), (0.22, 0, 1.25), "chest"),
        ("forearm.R", (0.22, 0, 1.25), (0.22, 0, 0.75), "upperarm.R"),
        ("thigh.L", (-0.10, 0, 1.03), (-0.10, 0, 0.45), "hips"),
        ("shin.L", (-0.10, 0, 0.45), (-0.10, 0, 0.02), "thigh.L"),
        ("thigh.R", (0.10, 0, 1.03), (0.10, 0, 0.45), "hips"),
        ("shin.R", (0.10, 0, 0.45), (0.10, 0, 0.02), "thigh.R"),
    ]
    return parts, bones, "humanoid"


# ---------------------------------------------------------------------------
# The Scrawler: a person scribbled onto the world and never rubbed out
# ---------------------------------------------------------------------------

def scrawler():
    """Tall, black and ragged, with a torn red mouth and arms that reach its shins."""
    rng = __import__("random").Random(5)
    parts = []
    box(parts, "scrawl_body", "thigh.L", (-0.10, 0, 0.72), (0.15, 0.15, 0.60))
    box(parts, "scrawl_body", "thigh.R", (0.10, 0, 0.72), (0.15, 0.15, 0.60))
    box(parts, "scrawl_body", "shin.L", (-0.10, 0, 0.22), (0.13, 0.14, 0.46))
    box(parts, "scrawl_body", "shin.R", (0.10, 0, 0.22), (0.13, 0.14, 0.46))
    box(parts, "scrawl_body", "hips", (0, 0, 1.12), (0.30, 0.19, 0.26))
    box(parts, "scrawl_body", "chest", (0, 0, 1.58), (0.36, 0.21, 0.66))
    box(parts, "scrawl_wound", "chest", (0, 0.10, 1.52), (0.10, 0.03, 0.34))
    box(parts, "scrawl_body", "upperarm.L", (-0.24, 0, 1.46), (0.11, 0.12, 0.56))
    box(parts, "scrawl_body", "upperarm.R", (0.24, 0, 1.46), (0.11, 0.12, 0.56))
    box(parts, "scrawl_body", "forearm.L", (-0.24, 0, 0.94), (0.10, 0.11, 0.52))
    box(parts, "scrawl_body", "forearm.R", (0.24, 0, 0.94), (0.10, 0.11, 0.52))
    for side, x in (("L", -0.24), ("R", 0.24)):
        for finger in range(3):
            box(parts, "scrawl_body", f"forearm.{side}",
                (x + (finger - 1) * 0.035, 0.02, 0.58), (0.022, 0.022, 0.22))
    box(parts, "scrawl_body", "neck", (0, 0, 1.93), (0.10, 0.10, 0.07))
    box(parts, "scrawl_body", "head", (0, 0, 2.08), (0.26, 0.22, 0.30))
    box(parts, "scrawl_wound", "head", (0, 0.112, 2.00), (0.17, 0.02, 0.09))     # the mouth
    for side in (-1, 1):
        box(parts, "scrawl_eye", "head", (side * 0.06, 0.112, 2.15), (0.045, 0.012, 0.020))

    # The scribble: slivers along the silhouette so the outline is never clean. Each is pinned
    # to whichever bone is nearest, so they move with the limb they stand out from.
    for _ in range(40):
        height = rng.uniform(0.30, 2.20)
        bone = ("shin.L" if height < 0.5 else "thigh.L" if height < 1.05
                else "hips" if height < 1.25 else "chest" if height < 1.90 else "head")
        if rng.random() < 0.5:
            bone = bone.replace(".L", ".R")
        side = -1 if rng.random() < 0.5 else 1
        reach = rng.uniform(0.04, 0.20)
        box(parts, "scrawl_body", bone,
            (side * (0.17 + reach / 2), rng.uniform(-0.09, 0.09), height),
            (reach, rng.uniform(0.015, 0.05), rng.uniform(0.02, 0.09)))

    bones = [
        ("root", (0, 0, 0), (0, 0, 0.12), None),
        ("hips", (0, 0, 1.02), (0, 0, 1.22), "root"),
        ("chest", (0, 0, 1.22), (0, 0, 1.92), "hips"),
        ("neck", (0, 0, 1.92), (0, 0, 1.97), "chest"),
        ("head", (0, 0, 1.97), (0, 0, 2.28), "neck"),
        ("upperarm.L", (-0.24, 0, 1.74), (-0.24, 0, 1.20), "chest"),
        ("forearm.L", (-0.24, 0, 1.20), (-0.24, 0, 0.66), "upperarm.L"),
        ("upperarm.R", (0.24, 0, 1.74), (0.24, 0, 1.20), "chest"),
        ("forearm.R", (0.24, 0, 1.20), (0.24, 0, 0.66), "upperarm.R"),
        ("thigh.L", (-0.10, 0, 1.02), (-0.10, 0, 0.44), "hips"),
        ("shin.L", (-0.10, 0, 0.44), (-0.10, 0, 0.02), "thigh.L"),
        ("thigh.R", (0.10, 0, 1.02), (0.10, 0, 0.44), "hips"),
        ("shin.R", (0.10, 0, 0.44), (0.10, 0, 0.02), "thigh.R"),
    ]
    return parts, bones, "humanoid"


# ---------------------------------------------------------------------------
# Starter animations
# ---------------------------------------------------------------------------

def humanoid_clips(rig):
    """idle, walk, chase and death for a two-legged thing. Something to build on."""
    # Idle: barely anything. It stands there, breathing, and that is worse.
    idle = new_action(rig, "idle")
    for frame, lean in ((1, 0), (30, 1.4), (60, 0)):
        key(rig, idle, "chest", frame, rotation=(lean, 0, 0))
        key(rig, idle, "head", frame, rotation=(-lean * 0.6, lean * 0.5, 0))

    # Walk: a slow, even stride, arms swinging opposite the legs.
    walk = new_action(rig, "walk")
    for frame, swing in ((1, 22), (15, 0), (30, -22), (45, 0), (60, 22)):
        key(rig, walk, "thigh.L", frame, rotation=(swing, 0, 0))
        key(rig, walk, "thigh.R", frame, rotation=(-swing, 0, 0))
        key(rig, walk, "shin.L", frame, rotation=(-abs(swing) * 0.5, 0, 0))
        key(rig, walk, "shin.R", frame, rotation=(-abs(swing) * 0.5, 0, 0))
        key(rig, walk, "upperarm.L", frame, rotation=(-swing * 0.7, 0, 0))
        key(rig, walk, "upperarm.R", frame, rotation=(swing * 0.7, 0, 0))
        key(rig, walk, "chest", frame, rotation=(2, swing * 0.1, 0))

    # Chase: the same stride, faster and further, leaning into it.
    chase = new_action(rig, "chase")
    for frame, swing in ((1, 42), (8, 0), (16, -42), (24, 0), (32, 42)):
        key(rig, chase, "thigh.L", frame, rotation=(swing, 0, 0))
        key(rig, chase, "thigh.R", frame, rotation=(-swing, 0, 0))
        key(rig, chase, "shin.L", frame, rotation=(-abs(swing) * 0.7, 0, 0))
        key(rig, chase, "shin.R", frame, rotation=(-abs(swing) * 0.7, 0, 0))
        key(rig, chase, "upperarm.L", frame, rotation=(-swing, 0, 0))
        key(rig, chase, "upperarm.R", frame, rotation=(swing, 0, 0))
        key(rig, chase, "chest", frame, rotation=(14, 0, 0))
        key(rig, chase, "head", frame, rotation=(-10, 0, 0))

    # Attack: the lunge it makes when it reaches you - the last thing you see. Played once.
    attack = new_action(rig, "attack")
    key(rig, attack, "root", 1, rotation=(0, 0, 0), location=(0, 0, 0))
    key(rig, attack, "chest", 1, rotation=(0, 0, 0))
    key(rig, attack, "upperarm.L", 1, rotation=(0, 0, 0))
    key(rig, attack, "upperarm.R", 1, rotation=(0, 0, 0))
    key(rig, attack, "chest", 5, rotation=(-16, 0, 0))          # rears back
    key(rig, attack, "upperarm.L", 5, rotation=(34, 0, -22))
    key(rig, attack, "upperarm.R", 5, rotation=(34, 0, 22))
    key(rig, attack, "root", 12, location=(0, 0.30, 0.06))      # and throws itself at you
    key(rig, attack, "chest", 12, rotation=(26, 0, 0))
    key(rig, attack, "head", 12, rotation=(-18, 0, 0))
    key(rig, attack, "upperarm.L", 12, rotation=(-116, 0, -30))
    key(rig, attack, "upperarm.R", 12, rotation=(-116, 0, 30))
    key(rig, attack, "forearm.L", 12, rotation=(-28, 0, 0))
    key(rig, attack, "forearm.R", 12, rotation=(-28, 0, 0))
    key(rig, attack, "root", 20, location=(0, 0.34, 0.04))
    key(rig, attack, "upperarm.L", 20, rotation=(-124, 0, -34))
    key(rig, attack, "upperarm.R", 20, rotation=(-124, 0, 34))

    # Death: it drops. Knees first, then the whole thing folds forward and stays down.
    death = new_action(rig, "death")
    key(rig, death, "root", 1, rotation=(0, 0, 0), location=(0, 0, 0))
    key(rig, death, "chest", 1, rotation=(0, 0, 0))
    key(rig, death, "head", 1, rotation=(0, 0, 0))
    key(rig, death, "thigh.L", 1, rotation=(0, 0, 0))
    key(rig, death, "thigh.R", 1, rotation=(0, 0, 0))
    key(rig, death, "root", 14, rotation=(-18, 0, 0), location=(0, 0, -0.22))
    key(rig, death, "thigh.L", 14, rotation=(58, 0, 0))
    key(rig, death, "thigh.R", 14, rotation=(52, 0, 0))
    key(rig, death, "shin.L", 14, rotation=(-72, 0, 0))
    key(rig, death, "shin.R", 14, rotation=(-68, 0, 0))
    key(rig, death, "chest", 14, rotation=(24, 0, 0))
    key(rig, death, "root", 34, rotation=(-88, 0, 0), location=(0, 0.22, -0.72))
    key(rig, death, "chest", 34, rotation=(10, 0, 0))
    key(rig, death, "head", 34, rotation=(-24, 0, 0))
    key(rig, death, "upperarm.L", 34, rotation=(-42, 0, 0))
    key(rig, death, "upperarm.R", 34, rotation=(-38, 0, 0))
    key(rig, death, "root", 48, rotation=(-90, 0, 0), location=(0, 0.24, -0.74))
    key(rig, death, "head", 48, rotation=(-26, 0, 0))
    return {"idle": 60, "walk": 60, "chase": 32, "attack": 20, "death": 48}


def crawler_clips(rig):
    """The same four, for something that goes about on its hands and feet."""
    idle = new_action(rig, "idle")
    for frame, breath in ((1, 0), (30, 2.2), (60, 0)):
        key(rig, idle, "chest", frame, rotation=(breath * 0.5, 0, 0))
        key(rig, idle, "head", frame, rotation=(-breath, 0, 0))
        key(rig, idle, "jaw", frame, rotation=(breath * 2, 0, 0))

    # Walk: arms and legs opposite, the way anything on four limbs moves.
    walk = new_action(rig, "walk")
    for frame, swing in ((1, 20), (15, 0), (30, -20), (45, 0), (60, 20)):
        key(rig, walk, "upperarm.L", frame, rotation=(swing, 0, 0))
        key(rig, walk, "upperarm.R", frame, rotation=(-swing, 0, 0))
        key(rig, walk, "thigh.L", frame, rotation=(-swing, 0, 0))
        key(rig, walk, "thigh.R", frame, rotation=(swing, 0, 0))
        key(rig, walk, "spine", frame, rotation=(0, 0, swing * 0.15))
        key(rig, walk, "head", frame, rotation=(swing * 0.15, 0, 0))

    # Chase: a scramble - both arms reach together, then both legs kick through.
    chase = new_action(rig, "chase")
    for frame, phase in ((1, 1), (8, -1), (16, 1)):
        key(rig, chase, "upperarm.L", frame, rotation=(44 * phase, 0, 0))
        key(rig, chase, "upperarm.R", frame, rotation=(40 * phase, 0, 0))
        key(rig, chase, "forearm.L", frame, rotation=(-18 * phase, 0, 0))
        key(rig, chase, "forearm.R", frame, rotation=(-16 * phase, 0, 0))
        key(rig, chase, "thigh.L", frame, rotation=(-40 * phase, 0, 0))
        key(rig, chase, "thigh.R", frame, rotation=(-44 * phase, 0, 0))
        key(rig, chase, "spine", frame, rotation=(-10 * phase, 0, 0))
        key(rig, chase, "chest", frame, rotation=(8 * phase, 0, 0))
        key(rig, chase, "jaw", frame, rotation=(18 + 12 * phase, 0, 0))

    # Attack: rears up on its legs, mouth open, and comes down on you.
    attack = new_action(rig, "attack")
    key(rig, attack, "root", 1, rotation=(0, 0, 0), location=(0, 0, 0))
    key(rig, attack, "jaw", 1, rotation=(0, 0, 0))
    key(rig, attack, "root", 6, rotation=(-34, 0, 0), location=(0, -0.06, 0.18))
    key(rig, attack, "jaw", 6, rotation=(38, 0, 0))
    key(rig, attack, "upperarm.L", 6, rotation=(-74, 0, 0))
    key(rig, attack, "upperarm.R", 6, rotation=(-70, 0, 0))
    key(rig, attack, "root", 14, rotation=(12, 0, 0), location=(0, 0.36, 0.04))
    key(rig, attack, "jaw", 14, rotation=(46, 0, 0))
    key(rig, attack, "head", 14, rotation=(18, 0, 0))
    key(rig, attack, "upperarm.L", 14, rotation=(52, 0, 0))
    key(rig, attack, "upperarm.R", 14, rotation=(48, 0, 0))
    key(rig, attack, "root", 20, rotation=(6, 0, 0), location=(0, 0.38, 0))
    key(rig, attack, "jaw", 20, rotation=(34, 0, 0))

    death = new_action(rig, "death")
    key(rig, death, "root", 1, rotation=(0, 0, 0), location=(0, 0, 0))
    key(rig, death, "chest", 1, rotation=(0, 0, 0))
    key(rig, death, "root", 12, rotation=(0, 0, -26), location=(0, 0, -0.10))
    key(rig, death, "head", 12, rotation=(-22, 0, 0))
    key(rig, death, "upperarm.L", 12, rotation=(38, 0, 0))
    key(rig, death, "upperarm.R", 12, rotation=(30, 0, 0))
    key(rig, death, "root", 30, rotation=(0, 0, -84), location=(0, 0, -0.26))
    key(rig, death, "head", 30, rotation=(-30, 0, 0))
    key(rig, death, "jaw", 30, rotation=(12, 0, 0))
    key(rig, death, "root", 44, rotation=(0, 0, -90), location=(0, 0, -0.28))
    return {"idle": 60, "walk": 60, "chase": 16, "attack": 20, "death": 44}


# ---------------------------------------------------------------------------
# Putting one together
# ---------------------------------------------------------------------------

def make(name, builder):
    clear_scene()
    parts, bones, shape = builder()
    mesh_obj = build_mesh(name, parts)
    rig = build_armature(name, bones)
    bind(mesh_obj, rig)
    lengths = humanoid_clips(rig) if shape == "humanoid" else crawler_clips(rig)

    # Leave the file on the idle pose rather than on whichever action was keyed last.
    rig.animation_data.action = bpy.data.actions["idle"]
    bpy.context.scene.frame_end = max(lengths.values())

    os.makedirs(BLEND_DIR, exist_ok=True)
    os.makedirs(GLB_DIR, exist_ok=True)
    blend = os.path.join(BLEND_DIR, name + ".blend")
    glb = os.path.join(GLB_DIR, name + ".glb")
    bpy.ops.wm.save_as_mainfile(filepath=blend)
    bpy.ops.export_scene.gltf(
        filepath=glb,
        export_format="GLB",
        export_animation_mode="ACTIONS",   # one clip per action, named after it
        export_apply=True,
        export_yup=True,
    )
    clips = ", ".join(f"{clip} ({frames}f)" for clip, frames in lengths.items())
    print(f"[rig] {name}: {len(parts)} parts, {len(bones)} bones, clips: {clips}")
    print(f"[rig]   {blend}")
    print(f"[rig]   {glb}")


def main():
    for name, builder in (("faceling", faceling), ("hound", hound), ("smiler", smiler),
                          ("scrawler", scrawler)):
        make(name, builder)


if __name__ == "__main__":
    main()
    sys.exit(0)
