"""Straightens the finger bones of a character rig, in Blender.

The Hazmat rig - like most bought rigs - is modelled with the hands closed into a
fist, which makes every hand pose a fight: you are undoing 60 degrees of curl at
each knuckle before you get anywhere. This opens the hands out flat and makes
that the rig's rest pose, so the fingers start straight and you only pose what
you actually want to move.

Nothing is written over: the result is saved next to the original with
" (straight hands)" on the end, plus before/after pictures of the left hand.

    Blender --background "My Rig.blend" --python tools/straighten_fingers.py \
        -- --out "My Rig (straight hands).blend" [--curl 0] [--glb out.glb]

--curl leaves a little bend in, in degrees per joint, if a completely flat hand
looks too much like a mannequin. 0 is dead straight; 8-12 reads as relaxed.
"""

import argparse
import math
import os
import sys

import bpy
from mathutils import Matrix, Vector

FINGERS = ("thumb", "index", "middle", "ring", "pinky")
SEGMENTS = ("01", "02", "03")


def parse_args():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", help="where to save the straightened .blend")
    parser.add_argument("--glb", help="also export the character to this .glb")
    parser.add_argument("--curl", type=float, default=0.0,
                        help="degrees of bend to leave at each joint")
    parser.add_argument("--renders", help="folder for the before/after pictures")
    parser.add_argument("--armature", help="armature to work on (default: the only one)")
    return parser.parse_args(argv)


def find_armature(name=None):
    if name:
        return bpy.data.objects[name]
    armatures = [o for o in bpy.data.objects if o.type == "ARMATURE"]
    if len(armatures) != 1:
        raise SystemExit("expected one armature, found: "
                         + ", ".join(o.name for o in armatures))
    return armatures[0]


def chains(armature):
    """Every finger, as the list of pose bones from knuckle to tip."""
    for side in ("l", "r"):
        for finger in FINGERS:
            bones = [armature.pose.bones.get(f"{finger}_{seg}_{side}") for seg in SEGMENTS]
            bones = [bone for bone in bones if bone]
            if len(bones) > 1:
                yield f"{finger}_{side}", bones


def bend_angles(bones):
    """How far each joint is bent from the bone before it, in degrees."""
    angles = []
    for parent, child in zip(bones, bones[1:]):
        a = (parent.tail - parent.head).normalized()
        b = (child.tail - child.head).normalized()
        angles.append(math.degrees(a.angle(b)))
    return angles


def clear_stale_action(armature):
    """Drops any animation on the rig before the rest pose is touched.

    This rig ships with a near-empty action holding a pose for some of the bones. Its
    rotations are stored against the old rest pose, so left in place they get laid back
    on top of the new one when the file is next opened and the fingers curl up again.
    """
    if armature.animation_data and armature.animation_data.action:
        print("Dropping the pose held in action '"
              + armature.animation_data.action.name + "'")
        armature.animation_data_clear()


def straighten(armature, curl_degrees):
    """Poses every finger straight. The curl is kept as a rotation about the knuckle axis."""
    bpy.context.view_layer.objects.active = armature
    armature.select_set(True)
    bpy.ops.object.mode_set(mode="POSE")
    for bone in armature.pose.bones:
        bone.rotation_mode = "QUATERNION"

    for _, bones in chains(armature):
        for bone in bones[1:]:
            parent = bone.parent
            rotation = parent.matrix.to_quaternion()
            if curl_degrees:
                # Bend back towards the palm about the knuckle's own bending axis,
                # which is the bone's X: the way a finger actually folds.
                axis = parent.matrix.to_3x3() @ Vector((1, 0, 0))
                rotation = Matrix.Rotation(math.radians(curl_degrees), 4,
                                           axis).to_quaternion() @ rotation
            bone.matrix = Matrix.LocRotScale(bone.matrix.to_translation(), rotation,
                                             bone.matrix.to_scale())
            # Each bone carries its children, so the chain has to settle as we go.
            bpy.context.view_layer.update()
    bpy.ops.object.mode_set(mode="OBJECT")
    bpy.context.view_layer.update()


def apply_as_rest_pose(armature):
    """Makes the current pose the rig's rest pose, keeping the meshes where they are.

    The meshes are bound to the old pose, so the deformation is baked into them first
    (as a shape key when they already have some, otherwise straight into the mesh) -
    without that the character springs back to a fist the moment the rest pose moves.
    """
    for mesh in bpy.data.objects:
        if mesh.type != "MESH":
            continue
        modifiers = [m for m in mesh.modifiers
                     if m.type == "ARMATURE" and m.object == armature]
        if not modifiers:
            continue
        bpy.ops.object.select_all(action="DESELECT")
        mesh.select_set(True)
        bpy.context.view_layer.objects.active = mesh
        for modifier in modifiers:
            if mesh.data.shape_keys:
                # Applying a modifier outright is not allowed with shape keys.
                bpy.ops.object.modifier_apply_as_shapekey(keep_modifier=True,
                                                          modifier=modifier.name)
                key = mesh.data.shape_keys.key_blocks[-1]
                key.value = 1.0
            else:
                copied = mesh.modifiers.new(modifier.name + "_bake", "ARMATURE")
                copied.object = armature
                copied.use_deform_preserve_volume = modifier.use_deform_preserve_volume
                # Applied where the original sits, so anything after it - a subdivision,
                # say - still runs on top rather than being baked around.
                index = list(mesh.modifiers).index(modifier)
                bpy.ops.object.modifier_move_to_index(modifier=copied.name, index=index)
                bpy.ops.object.modifier_apply(modifier=copied.name)

    bpy.ops.object.select_all(action="DESELECT")
    armature.select_set(True)
    bpy.context.view_layer.objects.active = armature
    bpy.ops.object.mode_set(mode="POSE")
    bpy.ops.pose.select_all(action="SELECT")
    bpy.ops.pose.armature_apply(selected=False)
    bpy.ops.object.mode_set(mode="OBJECT")


def render_hand(armature, path, side="l"):
    """A picture of one hand, framed on the fingers, for comparing before with after."""
    for widget in bpy.data.objects:
        if widget.name.startswith("WGT-"):
            widget.hide_render = True
    armature.hide_render = True

    points = []
    for bone in armature.pose.bones:
        if bone.name.endswith("_" + side) and bone.name.startswith(FINGERS + ("hand",)):
            points.append(armature.matrix_world @ bone.head)
            points.append(armature.matrix_world @ bone.tail)
    if not points:
        return
    center = sum(points, Vector()) / len(points)
    radius = max((point - center).length for point in points)

    data = bpy.data.cameras.new("HandCam")
    data.type = "ORTHO"
    data.ortho_scale = radius * 2.8
    camera = bpy.data.objects.new("HandCam", data)
    bpy.context.scene.collection.objects.link(camera)
    camera.location = center + Vector((0.6, -1.0, 0.5)).normalized() * radius * 8
    camera.rotation_euler = (center - camera.location).to_track_quat("-Z", "Y").to_euler()

    scene = bpy.context.scene
    scene.camera = camera
    scene.render.engine = "BLENDER_WORKBENCH"
    scene.render.resolution_x = 900
    scene.render.resolution_y = 700
    if hasattr(scene.render.image_settings, "media_type"):
        scene.render.image_settings.media_type = "IMAGE"  # Blender 5 splits stills from video
    scene.render.image_settings.file_format = "PNG"
    scene.render.use_file_extension = False
    scene.render.filepath = path
    bpy.ops.render.render(write_still=True)

    bpy.data.objects.remove(camera)
    bpy.data.cameras.remove(data)


def report(armature, heading):
    print(heading)
    for name, bones in chains(armature):
        angles = ", ".join(f"{angle:5.1f}" for angle in bend_angles(bones))
        print(f"  {name:<10} {angles}")


def main():
    args = parse_args()
    armature = find_armature(args.armature)
    source = bpy.data.filepath
    out = args.out or os.path.splitext(source)[0] + " (straight hands).blend"

    clear_stale_action(armature)
    report(armature, "Bend at each finger joint, before (degrees):")
    if args.renders:
        render_hand(armature, os.path.join(args.renders, "hand-before.png"))

    straighten(armature, args.curl)
    apply_as_rest_pose(armature)
    report(armature, "Bend at each finger joint, after (degrees):")

    if args.renders:
        render_hand(armature, os.path.join(args.renders, "hand-after.png"))

    bpy.ops.wm.save_as_mainfile(filepath=out)
    print("Saved " + out)
    if args.glb:
        bpy.ops.export_scene.gltf(filepath=args.glb, export_format="GLB",
                                  use_visible=True, export_apply=False)
        print("Exported " + args.glb)


if __name__ == "__main__":
    main()
