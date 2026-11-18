#!/usr/bin/env python3
"""
The image recorder's item and block models, from Jonah's Blockbench project.

    scripts/imaging/image_recorder_models.py <path to image_recorder.bbmodel>

Writes `models/item/image_recorder.json`, and `models/block/image_recorder_turn_<0..3>.json` with the blockstate that
picks among them, under the companion's assets. Run it again whenever the project changes. Two things are ours rather than the project's:

- **UVs** go onto Minecraft's 0-16 scale, whatever the texture's size.
- **The head pose** is where the recorder is held before the face through a viewfinder (`AvatarRendererMixin`), so it
  is set here, not taken from the project.

A recorder set down faces one of sixteen ways, four facings each turned 0, 22.5, 45 or 67.5 degrees clockwise: the four
block models are the item's geometry turned about the block's centre, and the blockstate turns them for the facing.
An element the project already turns about one axis takes both turns as one rotation about its own origin (26.3's three-axis element rotation applies x, then y, then z), with its
box moved by whatever the two pivots differ by.
"""
import json
import math
import sys
from pathlib import Path

ASSETS = Path(__file__).resolve().parents[2] / "imaging/common/src/main/resources/assets/agesandtheart_imaging"
TEXTURE = "agesandtheart_imaging:block/image_recorder"
HELD_BEFORE_THE_FACE = {"translation": [0, 6.5, -11.2], "scale": [1.6, 1.6, 1.6]}
# A set-down recorder faces one of sixteen ways: four facings, each in four turns of 22.5 degrees clockwise from above.
# The blockstate turns a model for the facing; the turns are four models, a model's turn about y being anticlockwise.
TURN = 22.5
TURNS = 4
BLOCK_CENTRE = (8.0, 0.0, 8.0)


def rotation_x(degrees):
    c, s = math.cos(math.radians(degrees)), math.sin(math.radians(degrees))
    return [[1, 0, 0], [0, c, -s], [0, s, c]]


def rotation_y(degrees):
    c, s = math.cos(math.radians(degrees)), math.sin(math.radians(degrees))
    return [[c, 0, s], [0, 1, 0], [-s, 0, c]]


def rotation_z(degrees):
    c, s = math.cos(math.radians(degrees)), math.sin(math.radians(degrees))
    return [[c, -s, 0], [s, c, 0], [0, 0, 1]]


def times(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def applied(m, v):
    return [sum(m[i][k] * v[k] for k in range(3)) for i in range(3)]


def transposed(m):
    return [[m[j][i] for j in range(3)] for i in range(3)]


def minus(a, b):
    return [x - y for x, y in zip(a, b)]


def rounded(v):
    return [round(x, 4) for x in v]


def elements_of(project):
    width, height = project["resolution"]["width"], project["resolution"]["height"]
    elements = []
    for e in project["elements"]:
        out = {"from": e["from"], "to": e["to"]}
        rotation = e.get("rotation")
        if rotation and any(rotation):
            axes = [i for i, angle in enumerate(rotation) if angle]
            assert len(axes) == 1, f"{e.get('name')} turns about more than one axis"
            out["rotation"] = {"angle": rotation[axes[0]], "axis": "xyz"[axes[0]], "origin": e["origin"]}
        faces = {}
        for name, face in e["faces"].items():
            if face.get("texture") is None:
                continue
            u1, v1, u2, v2 = face["uv"]
            f = {"uv": [u1 * 16 / width, v1 * 16 / height, u2 * 16 / width, v2 * 16 / height], "texture": "#0"}
            if face.get("rotation"):
                f["rotation"] = face["rotation"]
            faces[name] = f
        out["faces"] = faces
        elements.append(out)
    return elements


def turned_on_the_ground(element, degrees):
    """The element turned about the block's centre, as one rotation the model format can say."""
    turned = dict(element)
    own = element.get("rotation")
    if own is None:
        turned["rotation"] = {"angle": degrees, "axis": "y", "origin": list(BLOCK_CENTRE)}
        return turned
    if own["axis"] == "y":
        assert own["origin"][0] == BLOCK_CENTRE[0] and own["origin"][2] == BLOCK_CENTRE[2], "a turn about another y axis"
        turned["rotation"] = dict(own, angle=own["angle"] + degrees)
        return turned
    own_turn = {"x": rotation_x, "z": rotation_z}[own["axis"]](own["angle"])
    ground_turn = rotation_y(degrees)
    both = times(ground_turn, own_turn)
    origin = own["origin"]
    # Turning about the element's origin and then the block's centre is turning by both about the origin, once the box
    # is moved by (both)^-1 (I - ground)(centre - origin).
    difference = minus(BLOCK_CENTRE, origin)
    shift = applied(transposed(both), minus(difference, applied(ground_turn, difference)))
    turned["from"] = rounded([a + b for a, b in zip(element["from"], shift)])
    turned["to"] = rounded([a + b for a, b in zip(element["to"], shift)])
    euler = {"x": 0.0, "y": degrees, "z": 0.0}
    assert own["axis"] == "x", "only x then y is one three-axis rotation in x-y-z order"
    euler["x"] = own["angle"]
    turned["rotation"] = {"origin": origin, **euler}
    return turned


def main():
    project = json.loads(Path(sys.argv[1]).read_text())
    assert project["meta"]["model_format"] == "java_block" and not project["meta"]["box_uv"]
    elements = elements_of(project)
    display = dict(project["display"], head=HELD_BEFORE_THE_FACE)
    item = {
        "_comment": "Generated by scripts/imaging/image_recorder_models.py from Jonah's Blockbench project; edit the "
                    "project and run it again. The head pose is where the recorder is held before the face through a "
                    "viewfinder, scaled by 1.6 as a spyglass's is, undoing the head's 0.625 to match its size in hand.",
        "textures": {"0": TEXTURE, "particle": TEXTURE},
        "elements": elements,
        "display": display,
    }
    (ASSETS / "models/item/image_recorder.json").write_text(json.dumps(item, indent=2) + "\n")
    (ASSETS / "models/block").mkdir(parents=True, exist_ok=True)
    for turn in range(TURNS):
        degrees = -turn * TURN
        block = {
            "_comment": f"Generated by scripts/imaging/image_recorder_models.py: the item's geometry turned {turn * TURN} "
                        "degrees clockwise about the block's centre, one of four turns within each way it faces.",
            "textures": {"0": TEXTURE, "particle": TEXTURE},
            "elements": [e if turn == 0 else turned_on_the_ground(e, degrees) for e in elements],
        }
        for e in block["elements"]:
            for corner in (e["from"], e["to"]):
                assert all(-16 <= c <= 32 for c in corner), e
        (ASSETS / f"models/block/image_recorder_turn_{turn}.json").write_text(json.dumps(block, indent=2) + "\n")
    facings = {"north": 0, "east": 90, "south": 180, "west": 270}
    variants = {}
    for facing, y in facings.items():
        for turn in range(TURNS):
            variant = {"model": f"agesandtheart_imaging:block/image_recorder_turn_{turn}"}
            if y:
                variant["y"] = y
            variants[f"facing={facing},turn={turn}"] = variant
    (ASSETS / "blockstates/image_recorder.json").write_text(json.dumps({"variants": variants}, indent=2) + "\n")
    old = ASSETS / "models/block/image_recorder.json"
    if old.exists():
        old.unlink()
    print(f"{len(elements)} elements written to the item model and {TURNS} turned block models")


main()
