#!/usr/bin/env python3
"""
The book carousel's models, in every wood, from Jonah's Blockbench project.

    scripts/books/book_carousel_models.py <path to book_carousel.bbmodel>

The project, facing north and built round the middle block of the carousel's three by three, has `base`, the plinth
and the upright post; `arms`, eight arms of which only the first, pointing to the front, is used (the one holding
`top arm 1`); and `book`, for scale, which is left out. An arm is a bar out from the post, a strut under it, a top arm
over it and a brace from the bar's tip up to the top arm, and at the tip a board leaning back 15 degrees from upright
with a lip at its foot, which a book stands on, leaning against the board, read from outside the carousel.

The project's top arm and brace reached above the block; here the top arm is level and both sit a pixel lower than
the post's top, which shows as a hub (Jonah): the brace rises at 30 degrees from the centre of the bar's tip to the
centre of the top arm, 4 pixels, so it is 8 long, and its end lies inside the top arm's end (`TOP_ARM`, `BRACE`).

Writes, under the mod's assets:

- `models/block/book_carousel_stand.json`: the plinth and the post, in the block holding the carousel.
- `models/block/book_carousel_arm.json`: the front arm with its board and lip, which the renderer turns to each of the
  eight places round the post.
- `models/block/book_carousel_arms_at_rest.json`: all eight arms, for the item.
- `models/block/book_carousel_part.json`: nothing, for the carousel's other blocks, but their wood's particles.
- Each in each wood, `_<wood>`, with `_tinted` parents for the palm.
- `blockstates/book_carousel.json` and `items/book_carousel.json`.

Wood is cut at block lines and textured from where it lies, as the large wheel's is; the board and lip take the
lectern's textures (`BOARD_FACES`, `LIP_FACES`). Run it again whenever the project changes.
"""
import json
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from book_wheel_models import FACINGS, PALM_TINT, WOODS, planks_of, write  # noqa: E402
from large_book_wheel_models import display_of, groups_of, wooden  # noqa: E402

ASSETS = Path(__file__).resolve().parents[2] / "common/src/main/resources/assets/agesandtheart"
NAME = "book_carousel"

# The block holding the carousel, as `WheelCells.CAROUSEL` counts it: across 1, up 0, back 1, of three by one by three.
HOLDER_CELL = 4
CELLS = 9
ARMS = 8
AXLE = (8, 8)

# The level top arm, a pixel under the post's top, and the brace up to it from the tip of the bar, at 30 degrees.
TOP_ARM = ((7.25, 13, -5), (8.75, 15, 8))
BRACE = ((7.5, 9, -11), (8.5, 11, -3))
BRACE_ROTATION = {"angle": -30, "axis": "x", "origin": [8, 10, -11]}

# How much lower the diagonal arms lie, where all eight meet by the post (Jonah).
DIAGONAL_ARMS_LOWERED = 0.02

# The lectern's textures on the board and its lip, by face: the book stands against the north face of the board, on
# the lip's top, read from outside.
BOARD_FACES = {
    "north": ("top", [0, 2, 16, 12]),
    "south": ("base", [0, 0, 16, 10]),
    "up": ("sides", [0, 1, 16, 3]),
    "down": ("sides", [0, 1, 16, 3]),
    "east": ("sides", [14, 4, 16, 14]),
    "west": ("sides", [14, 4, 16, 14]),
}
LIP_FACES = {
    "north": ("sides", [0, 1, 16, 2]),
    "up": ("top", [0, 14, 16, 16]),
    "down": ("base", [0, 0, 16, 2]),
    "east": ("sides", [0, 1, 2, 2]),
    "west": ("sides", [0, 1, 2, 2]),
}
LECTERN = {
    "top": "minecraft:block/lectern_top",
    "sides": "minecraft:block/lectern_sides",
    "base": "minecraft:block/lectern_base",
}

# The item, a carousel three blocks across: as the large wheel's, a little larger, being one block high.
DISPLAY = {
    "gui": {"rotation": [30, -135, 0], "translation": [0, 0, 0], "scale": 0.25},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": 0.12},
    "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": 0.22},
    "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": 0.15},
    "firstperson_righthand": {"rotation": [0, 135, 0], "translation": [0, 0, 0], "scale": 0.18},
}


def rotation_of(element):
    angles = element.get("rotation") or [0, 0, 0]
    turned = [(axis, angle) for axis, angle in zip("xyz", angles) if angle]
    if not turned:
        return None
    if len(turned) > 1:
        sys.exit(f"{element['name']} turns on more than one axis")
    axis, angle = turned[0]
    return {"angle": angle, "axis": axis, "origin": element["origin"]}


def lectern_element(element, faces):
    shaped = {
        "from": element["from"],
        "to": element["to"],
        "faces": {name: {"uv": uv, "texture": f"#{texture}"} for name, (texture, uv) in faces.items()},
    }
    if rotation := rotation_of(element):
        shaped["rotation"] = rotation
    return shaped


def turned_round(element, quarter_eighths):
    """
    [element] turned [quarter_eighths] eighths of a turn round the post, for the item: its tilt about x moved onto a
    pivot on the post's axis, which shifts its box to keep it where it was, and then turned about that axis.
    """
    yaw = 45 * quarter_eighths
    rotation = element.get("rotation")
    if rotation is None:
        pivot = [AXLE[0], 8, AXLE[1]]
        return {**element, "rotation": {"origin": pivot, "x": 0, "y": yaw, "z": 0}}
    if rotation["axis"] != "x":
        sys.exit("an arm's pieces tilt only about x")
    tilt = math.radians(rotation["angle"])
    old = rotation["origin"]
    new = [AXLE[0], old[1], AXLE[1]]
    # Tilting about the old pivot lands a point (R - I)(new - old) away from tilting about the new one, so the box
    # moves by that, untilted, before tilting.
    dy, dz = new[1] - old[1], new[2] - old[2]
    moved_y = dy * math.cos(tilt) - dz * math.sin(tilt) - dy
    moved_z = dy * math.sin(tilt) + dz * math.cos(tilt) - dz
    shift_y = moved_y * math.cos(tilt) + moved_z * math.sin(tilt)
    shift_z = -moved_y * math.sin(tilt) + moved_z * math.cos(tilt)
    shift = [0, shift_y, shift_z]
    return {
        **element,
        "from": [round(value + shift[axis], 5) for axis, value in enumerate(element["from"])],
        "to": [round(value + shift[axis], 5) for axis, value in enumerate(element["to"])],
        "rotation": {"origin": new, "x": rotation["angle"], "y": yaw, "z": 0},
    }


def lowered(element, eighth):
    """[element] of the arm [eighth] round, the diagonal ones a hair lower, so where they meet no two lie in one plane."""
    if eighth % 2 == 0:
        return element
    drop = DIAGONAL_ARMS_LOWERED
    moved = dict(element)
    moved["from"] = [value - (drop if axis == 1 else 0) for axis, value in enumerate(element["from"])]
    moved["to"] = [value - (drop if axis == 1 else 0) for axis, value in enumerate(element["to"])]
    moved["rotation"] = {**element["rotation"], "origin": [value - (drop if axis == 1 else 0) for axis, value in enumerate(element["rotation"]["origin"])]}
    return moved


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    project = json.loads(Path(sys.argv[1]).read_text())
    groups = groups_of(project)
    by_uuid = {element["uuid"]: element for element in project["elements"]}
    names = {group["uuid"]: group["name"] for group in project["groups"]}
    arms_entry = next(entry for entry in project["outliner"] if not isinstance(entry, str) and names[entry["uuid"]] == "arms")
    arm_entries = [entry for entry in arms_entry["children"] if not isinstance(entry, str)]
    front_arm = next(
        [by_uuid[child] for child in entry["children"] if isinstance(child, str)]
        for entry in arm_entries
        if any(isinstance(child, str) and by_uuid[child]["name"] == "top arm 1" for child in entry["children"])
    )
    base = groups["base"]

    def is_bar(element):
        return element["name"] == "cube" and not element.get("rotation") and element["from"][2] < 0

    board, lip = sorted(
        (element for element in front_arm if element["from"][0] == 0 and element["to"][0] == 16),
        key=lambda element: element["to"][1] - element["from"][1],
        reverse=True,
    )
    bar = next(element for element in front_arm if is_bar(element))
    strut = next(
        element for element in front_arm
        if element["name"] == "cube" and element.get("rotation") and element is not board and element is not lip
    )

    def box(element):
        return tuple(element["from"]), tuple(element["to"])

    def arm(tinted):
        elements = []
        elements += wooden(box(bar), tinted)
        elements += wooden(TOP_ARM, tinted)
        elements += wooden(BRACE, tinted, rotation=BRACE_ROTATION)
        elements += wooden(box(strut), tinted, rotation=rotation_of(strut))
        elements.append(lectern_element(board, BOARD_FACES))
        elements.append(lectern_element(lip, LIP_FACES))
        return elements

    def stand(tinted):
        elements = []
        for element in base:
            elements += wooden(box(element), tinted)
        return elements

    def arms_at_rest(tinted):
        return [lowered(turned_round(element, eighth), eighth) for eighth in range(ARMS) for element in arm(tinted)]

    lows = [min(element["from"][axis] for element in stand(False)) for axis in range(3)]
    highs = [max(element["to"][axis] for element in stand(False)) for axis in range(3)]
    display = display_of(([-16, lows[1], -16], [32, highs[1], 32]), DISPLAY)
    credit = "Made with Blockbench; cut and textured by scripts/books/book_carousel_models.py"

    for suffix, tinted in (("", False), ("_tinted", True)):
        for part, elements, textures in (
            ("stand", stand(tinted), {}),
            ("arm", arm(tinted), LECTERN),
            ("arms_at_rest", arms_at_rest(tinted), LECTERN),
        ):
            write(ASSETS / f"models/block/{NAME}_{part}{suffix}.json", {
                "credit": credit,
                "parent": "minecraft:block/block",
                "textures": {"particle": "#planks", **textures},
                "display": display,
                "elements": elements,
            })
    write(ASSETS / f"models/block/{NAME}_part.json", {"parent": "minecraft:block/block", "textures": {"particle": "#planks"}})

    for wood in WOODS:
        tinted = "_tinted" if wood == "palm" else ""
        for part in ("stand", "arm", "arms_at_rest"):
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
        for facing, turn in FACINGS.items():
            variant = {"model": f"agesandtheart:block/{NAME}_stand_{wood}"}
            if turn:
                variant["y"] = turn
            variants[f"cell={HOLDER_CELL},facing={facing},piece=stand,wood={wood}"] = variant
        for cell in range(CELLS):
            if cell != HOLDER_CELL:
                variants[f"cell={cell},piece=stand,wood={wood}"] = {"model": f"agesandtheart:block/{NAME}_part_{wood}"}
        variants[f"piece=wheel,wood={wood}"] = {"model": f"agesandtheart:block/{NAME}_arm_{wood}"}
        variants[f"piece=shelf,wood={wood}"] = {"model": f"agesandtheart:block/{NAME}_part_{wood}"}
    write(ASSETS / f"blockstates/{NAME}.json", {
        "_comment": "[AI] Jonah's book carousel in the wood it was built of: the stand by facing in the block holding it, nothing in its others; one arm unturned, for BookWheelRenderer to turn to each place.",
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
            "models": [in_wood(f"agesandtheart:block/{NAME}_{part}_{wood}", wood) for part in ("stand", "arms_at_rest")],
        }

    write(ASSETS / f"items/{NAME}.json", {
        "_comment": "[AI] The book carousel in its wood, its eight arms at rest.",
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
