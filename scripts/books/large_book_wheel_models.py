#!/usr/bin/env python3
"""
The large book wheel's models, in every wood, from Jonah's Blockbench project.

    scripts/books/large_book_wheel_models.py <path to large_bookwheel.bbmodel>

The project, facing north and built round the middle block of the wheel's right-hand column, has: `base`, the plinth
and two support arms, which are left out, since players build their own supports (Jonah); `wheel`, its two faces (each
a disk of rims), the axle, and `shelves`, holding the front shelf (a lectern's top, `lectern`) and the pin it hangs on
(`axle`); and `reference`, a book for scale, also left out. Its resolution is 48, which is a lectern texture's 16 at
three units a texel.

Writes, under the mod's assets:

- `models/block/large_book_wheel_wheel.json`: the two disks, the axle reaching a pixel further each way, to the sides
  of the wheel's blocks, where a player's supports meet it (`AXLE_REACH`), and eight shelf pins, the front one's every
  eighth of a turn round the axle, thinned to `PIN_SIDE`.
- `models/block/large_book_wheel_shelf.json`: one shelf, the front one's lectern top cut to `SHELF_HEIGHT` by
  `SHELF_DEPTH`, centred in the block, which the renderer hangs at each pin.
- `models/block/large_book_wheel_shelves_at_rest.json`: the eight shelves where they rest, the front one at the
  lectern's slope, for the item.
- `models/block/large_book_wheel_part.json`: nothing, for each of the wheel's blocks, but their wood's particles.
- Each of those but the shelf at rest in each wood, `_<wood>`, with `_tinted` parents for the palm, whose planks are
  birch's under a tint until its own are drawn.
- `blockstates/large_book_wheel.json` and `items/large_book_wheel.json`.

Wood is cut at every block line and textured from where it lies, as vanilla textures a block with no UVs given, so
the planks show at their own size across a model three blocks big; the project's own wood UVs, laid at resolution 48,
would stretch them threefold. The shelves keep Jonah's lectern UVs. Run it again whenever the project changes.
"""
import json
import math
import sys
from itertools import product
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from book_wheel_models import PALM_TINT, WOODS, planks_of, write  # noqa: E402

ASSETS = Path(__file__).resolve().parents[2] / "common/src/main/resources/assets/agesandtheart"
NAME = "large_book_wheel"

# The project's UV units to a texture's pixels.
UV_SCALE = 16 / 48

SHELVES = 8

# Where the axle runs, up its block: through the middle, as the supports a player builds would hold it (Jonah). The
# project has it a pixel higher, so the whole wheel is lowered to put it there.
AXLE_HEIGHT = 8
SLOPE = -22.5

# How much further each end of the axle reaches than the project has it: to the sides of the wheel's blocks (Jonah).
AXLE_REACH = 1

# How thick and how deep a shelf is, thinner and shorter than the lectern's top it is, so that the shelves pass each
# other (Jonah): two neighbours level with each other at the top or bottom of the turn have their pins 10.33 apart,
# so no deeper than 10; and how thick the pin it hangs on, thinner than the shelf, so their faces never lie together.
SHELF_HEIGHT = 2
SHELF_DEPTH = 10
PIN_SIDE = 1

# The rows of the lectern's top a shorter shelf leaves off, at the start of its UVs; the rest come off the end.
DEPTH_TRIMMED_AT_THE_START = 1

GRID = 16
FACE_AXES = {"north": 2, "south": 2, "west": 0, "east": 0, "down": 1, "up": 1}
FACE_AT_THE_TOP = {"north": False, "south": True, "west": False, "east": True, "down": False, "up": True}

# How each place it is shown holds the item: Jonah's turns where he set them, vanilla's elsewhere, all shrunk to fit a
# model three blocks big, and moved so its middle stays where a block's would be.
DISPLAY = {
    "gui": {"rotation": [30, -135, 0], "translation": [0, 0, 0], "scale": 0.2},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": 0.1},
    "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": 0.18},
    "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": 0.12},
    "firstperson_righthand": {"rotation": [0, 135, 0], "translation": [0, 0, 0], "scale": 0.14},
}


def groups_of(project):
    """Every element under each group, by the group's name, through its subgroups."""
    by_uuid = {element["uuid"]: element for element in project["elements"]}
    names = {group["uuid"]: group["name"] for group in project["groups"]}
    found = {}

    def walk(entry, above):
        if isinstance(entry, str):
            for name in above:
                found.setdefault(name, []).append(by_uuid[entry])
            return
        name = names[entry["uuid"]]
        for child in entry["children"]:
            walk(child, above + [name])

    for entry in project["outliner"]:
        walk(entry, [])
    return found


def named(elements, name):
    matches = [element for element in elements if element["name"] == name]
    if len(matches) != 1:
        sys.exit(f"expected one element named {name}, found {len(matches)}")
    return matches[0]


def box_of(element):
    return tuple(element["from"]), tuple(element["to"])


def cuts(low, high):
    inside = {line for line in range(math.ceil(low / GRID) * GRID, math.floor(high / GRID) * GRID + 1, GRID) if low < line < high}
    return [low, *sorted(inside), high]


def pieces(box):
    """[box] cut at every block line, put back together within each block."""
    low, high = box
    splits = [cuts(low[axis], high[axis]) for axis in range(3)]
    cut = []
    for cell in product(*(range(len(split) - 1) for split in splits)):
        piece_low = tuple(splits[axis][cell[axis]] for axis in range(3))
        piece_high = tuple(splits[axis][cell[axis] + 1] for axis in range(3))
        cut.append((piece_low, piece_high))
    return merged(cut)


def block_of(piece):
    return tuple(math.floor(((piece[0][axis] + piece[1][axis]) / 2) / GRID) for axis in range(3))


def merged(cut):
    """[cut] with every two pieces that make one box within one block put back together."""
    pieces_left = list(cut)
    joined = True
    while joined:
        joined = False
        for first, second in product(pieces_left, repeat=2):
            if first is second or block_of(first) != block_of(second):
                continue
            for axis in range(3):
                others = [a for a in range(3) if a != axis]
                same_span = all(first[0][a] == second[0][a] and first[1][a] == second[1][a] for a in others)
                if same_span and first[1][axis] == second[0][axis]:
                    pieces_left.remove(first)
                    pieces_left.remove(second)
                    pieces_left.append((first[0], tuple(second[1][a] if a == axis else first[1][a] for a in range(3))))
                    joined = True
                    break
            if joined:
                break
    return pieces_left


def is_covered(piece, face, others):
    """Whether another piece cut from the same box lies against [face] of [piece], hiding it."""
    axis = FACE_AXES[face]
    at = piece[1][axis] if FACE_AT_THE_TOP[face] else piece[0][axis]
    for other in others:
        if other is piece:
            continue
        touching = other[0][axis] == at if FACE_AT_THE_TOP[face] else other[1][axis] == at
        same_span = all(other[0][a] == piece[0][a] and other[1][a] == piece[1][a] for a in range(3) if a != axis)
        if touching and same_span:
            return True
    return False


def placed_uv(face, low, high):
    """Vanilla's UV for a face given none, from where it lies within its block."""
    origin = [GRID * math.floor(((low[axis] + high[axis]) / 2) / GRID) for axis in range(3)]
    fx, fy, fz = (low[axis] - origin[axis] for axis in range(3))
    tx, ty, tz = (high[axis] - origin[axis] for axis in range(3))
    return {
        "down": [fx, 16 - tz, tx, 16 - fz],
        "up": [fx, fz, tx, tz],
        "north": [16 - tx, 16 - ty, 16 - fx, 16 - fy],
        "south": [fx, 16 - ty, tx, 16 - fy],
        "west": [fz, 16 - ty, tz, 16 - fy],
        "east": [16 - tz, 16 - ty, 16 - fz, 16 - fy],
    }[face]


def wooden(box, tinted, rotation=None):
    """[box] in wood, cut at block lines, each face textured from where it lies."""
    cut = pieces(box)
    elements = []
    for piece in cut:
        faces = {}
        for face in FACE_AXES:
            if is_covered(piece, face, cut):
                continue
            written = {"uv": placed_uv(face, *piece), "texture": "#planks"}
            if tinted:
                written["tintindex"] = 0
            faces[face] = written
        element = {"from": list(piece[0]), "to": list(piece[1]), "faces": faces}
        if rotation:
            element["rotation"] = rotation
        elements.append(element)
    return elements


def quarter_turned(box, quarters, axle):
    """[box] turned [quarters] quarter turns round the axle, the front toward the top."""
    (low_x, low_y, low_z), (high_x, high_y, high_z) = box
    corners = [(y, z) for y in (low_y, high_y) for z in (low_z, high_z)]
    for _ in range(quarters % 4):
        corners = [(axle[1] - (z - axle[2]), axle[2] + (y - axle[1])) for y, z in corners]
    ys = [y for y, _ in corners]
    zs = [z for _, z in corners]
    return (low_x, min(ys), min(zs)), (high_x, max(ys), max(zs))


def shelf_faces(lectern, textures, tinted):
    """
    Jonah's lectern top, its UVs in a texture's pixels: the lectern's boards, and the wood beneath; its edges the top
    rows of theirs, as thin as the shelf.
    """
    faces = {}
    thinned = SHELF_HEIGHT / (lectern["to"][1] - lectern["from"][1])
    for name, face in lectern["faces"].items():
        texture = textures[face["texture"]]["name"].removesuffix(".png")
        uv = [value * UV_SCALE for value in face["uv"]]
        if name not in ("up", "down"):
            uv[3] = uv[1] + (uv[3] - uv[1]) * thinned
        # The UVs running along the shelf's depth: down the top and underside, across the ends.
        along = {"up": 1, "down": 1, "east": 0, "west": 0}.get(name)
        if along is not None:
            uv[along] += DEPTH_TRIMMED_AT_THE_START
            uv[along + 2] = uv[along] + SHELF_DEPTH
        written = {"uv": [round(value, 4) for value in uv]}
        if texture.startswith("lectern_"):
            written["texture"] = f"#{texture.removeprefix('lectern_')}"
        else:
            written["texture"] = "#planks"
            if tinted:
                written["tintindex"] = 0
        if face.get("rotation"):
            written["rotation"] = face["rotation"]
        faces[name] = written
    return faces


def shelf_box(middle, lectern):
    size = [high - low for low, high in zip(lectern["from"], lectern["to"])]
    size[1] = SHELF_HEIGHT
    size[2] = SHELF_DEPTH
    return [m - s / 2 for m, s in zip(middle, size)], [m + s / 2 for m, s in zip(middle, size)]


def matrix_xyz(degrees):
    """JOML's `rotationXYZ`, which an item's display turn is: about x, then y, then z, as a matrix."""
    x, y, z = (math.radians(angle) for angle in degrees)
    rx = [[1, 0, 0], [0, math.cos(x), -math.sin(x)], [0, math.sin(x), math.cos(x)]]
    ry = [[math.cos(y), 0, math.sin(y)], [0, 1, 0], [-math.sin(y), 0, math.cos(y)]]
    rz = [[math.cos(z), -math.sin(z), 0], [math.sin(z), math.cos(z), 0], [0, 0, 1]]

    def times(a, b):
        return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]

    return times(times(rx, ry), rz)


def display_of(bounds, table=DISPLAY):
    """Each place's display, moved so the model's middle, not its block's, is where the block's middle would be."""
    low, high = bounds
    off_the_middle = [(low[axis] + high[axis]) / 2 - 8 for axis in range(3)]
    shown = {}
    for place, held in table.items():
        turn = matrix_xyz(held["rotation"])
        scaled = [held["scale"] * value for value in off_the_middle]
        moved = [sum(turn[row][column] * scaled[column] for column in range(3)) for row in range(3)]
        shown[place] = {
            "rotation": held["rotation"],
            "translation": [round(held["translation"][axis] - moved[axis], 3) for axis in range(3)],
            "scale": [held["scale"]] * 3,
        }
    return shown


def lowered(element, drop):
    """[element] [drop] pixels lower, its pivot with it."""
    def down(point):
        return [value - drop if axis == 1 else value for axis, value in enumerate(point)]

    moved = {**element, "from": down(element["from"]), "to": down(element["to"])}
    if "rotation" in element:
        moved["rotation"] = {**element["rotation"], "origin": down(element["rotation"]["origin"])}
    return moved


def bounds_of(elements):
    lows = [min(element["from"][axis] for element in elements) for axis in range(3)]
    highs = [max(element["to"][axis] for element in elements) for axis in range(3)]
    return lows, highs


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    project = json.loads(Path(sys.argv[1]).read_text())
    groups = groups_of(project)
    lectern = named(groups["front shelf"], "lectern")
    pin = named(groups["front shelf"], "axle")
    shelf_parts = {element["uuid"] for element in groups["shelves"]}
    axle = named([element for element in groups["wheel"] if element["uuid"] not in shelf_parts], "axle")
    faces_of_the_wheel = groups["left face"] + groups["right face"]

    axle_low, axle_high = box_of(axle)
    axle_middle = [(low + high) / 2 for low, high in zip(axle_low, axle_high)]
    axle_box = (axle_low[0] - AXLE_REACH, *axle_low[1:]), (axle_high[0] + AXLE_REACH, *axle_high[1:])
    pin_low, pin_high = box_of(pin)
    pin_middle = [(low + high) / 2 for low, high in zip(pin_low, pin_high)]
    pin_box = (
        (pin_low[0], pin_middle[1] - PIN_SIDE / 2, pin_middle[2] - PIN_SIDE / 2),
        (pin_high[0], pin_middle[1] + PIN_SIDE / 2, pin_middle[2] + PIN_SIDE / 2),
    )
    lectern_middle = [(low + high) / 2 for low, high in zip(lectern["from"], lectern["to"])]
    radius = math.hypot(lectern_middle[1] - axle_middle[1], lectern_middle[2] - axle_middle[2])

    drop = axle_middle[1] - AXLE_HEIGHT

    def wheel(tinted):
        return [lowered(element, drop) for element in turning(tinted)]

    def turning(tinted):
        elements = []
        for element in faces_of_the_wheel:
            elements += wooden(box_of(element), tinted)
        elements += wooden(axle_box, tinted)
        for position in range(SHELVES):
            turned = quarter_turned(pin_box, position // 2, axle_middle)
            rotation = {"angle": 45, "axis": "x", "origin": axle_middle} if position % 2 else None
            elements += wooden(turned, tinted, rotation=rotation)
        return elements

    def shelves_at_rest(tinted):
        elements = []
        for position in range(SHELVES):
            round_ = position * 2 * math.pi / SHELVES
            middle = [
                lectern_middle[0],
                AXLE_HEIGHT + radius * math.sin(round_),
                axle_middle[2] - radius * math.cos(round_),
            ]
            low, high = shelf_box(middle, lectern)
            element = {
                "from": [round(value, 4) for value in low],
                "to": [round(value, 4) for value in high],
                "faces": shelf_faces(lectern, project["textures"], tinted),
            }
            if position == 0:
                element["rotation"] = {"angle": SLOPE, "axis": "x", "origin": middle}
            elements.append(element)
        return elements

    whole = wheel(False) + shelves_at_rest(False)
    display = display_of(bounds_of(whole))
    lectern_textures = {"top": "minecraft:block/lectern_top", "sides": "minecraft:block/lectern_sides"}
    credit = "Made with Blockbench; cut and textured by scripts/books/large_book_wheel_models.py"

    for suffix, tinted in (("", False), ("_tinted", True)):
        for part, elements in (("wheel", wheel(tinted)), ("shelves_at_rest", shelves_at_rest(tinted))):
            textures = {"particle": "#planks", **(lectern_textures if part == "shelves_at_rest" else {})}
            write(ASSETS / f"models/block/{NAME}_{part}{suffix}.json", {
                "credit": credit,
                "parent": "minecraft:block/block",
                "textures": textures,
                "display": display,
                "elements": elements,
            })
        low, high = shelf_box([8, 8, 8], lectern)
        write(ASSETS / f"models/block/{NAME}_shelf{suffix}.json", {
            "credit": credit,
            "textures": {"particle": "#planks", **lectern_textures},
            "elements": [{"from": low, "to": high, "faces": shelf_faces(lectern, project["textures"], tinted)}],
        })
    write(ASSETS / f"models/block/{NAME}_part.json", {"parent": "minecraft:block/block", "textures": {"particle": "#planks"}})

    for wood in WOODS:
        tinted = "_tinted" if wood == "palm" else ""
        for part in ("wheel", "shelf", "shelves_at_rest"):
            write(ASSETS / f"models/block/{NAME}_{part}_{wood}.json", {
                "parent": f"agesandtheart:block/{NAME}_{part}{tinted}",
                "textures": {"planks": planks_of(wood)},
            })
        write(ASSETS / f"models/block/{NAME}_part_{wood}.json", {
            "parent": f"agesandtheart:block/{NAME}_part",
            "textures": {"planks": planks_of(wood)},
        })

    variants = {}
    for wood in WOODS:
        variants[f"piece=stand,wood={wood}"] = {"model": f"agesandtheart:block/{NAME}_part_{wood}"}
        variants[f"piece=wheel,wood={wood}"] = {"model": f"agesandtheart:block/{NAME}_wheel_{wood}"}
        variants[f"piece=shelf,wood={wood}"] = {"model": f"agesandtheart:block/{NAME}_shelf_{wood}"}
    write(ASSETS / f"blockstates/{NAME}.json", {
        "_comment": "[AI] Jonah's large book wheel in the wood it was built of, standing on nothing: its blocks draw only their particles; the wheel and a shelf unturned, for BookWheelRenderer.",
        "variants": variants,
    })

    def in_wood(model, wood):
        written = {"type": "minecraft:model", "model": model}
        if wood == "palm":
            written["tints"] = [{"type": "minecraft:constant", "value": PALM_TINT}]
        return written

    def whole_in(wood):
        return {
            "type": "minecraft:composite",
            "models": [in_wood(f"agesandtheart:block/{NAME}_{part}_{wood}", wood) for part in ("wheel", "shelves_at_rest")],
        }

    write(ASSETS / f"items/{NAME}.json", {
        "_comment": "[AI] The large book wheel in its wood, its shelves at rest.",
        "model": {
            "type": "minecraft:select",
            "property": "minecraft:block_state",
            "block_state_property": "wood",
            "cases": [{"when": wood, "model": whole_in(wood)} for wood in WOODS],
            "fallback": whole_in("oak"),
        },
    })


if __name__ == "__main__":
    main()
