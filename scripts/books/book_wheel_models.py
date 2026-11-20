#!/usr/bin/env python3
"""
The small book wheel's models, in every wood, from Jonah's Blockbench project.

    scripts/books/book_wheel_models.py <path to small_bookwheel.bbmodel>

The project, facing north, has three groups: `base`, the plinth and the posts the axle runs between; `wheel`, the
four shelves where they rest and the cross of arms they ride on; and `low book`, a book for scale, which is left out.
Its resolution is 16, so its UVs are the model's.

Writes, under the mod's assets:

- `models/block/book_wheel_stand.json` and `book_wheel_arms.json`, every face on `#planks`, and `_tinted` copies
  with every face tinted, for the palm, whose planks are birch's under a tint until its own are drawn; then
  `book_wheel_stand_<wood>.json` and `book_wheel_arms_<wood>.json` for each of `WoodKind`'s woods.
- `models/block/book_wheel_shelf.json`: one shelf on the lectern's textures, flat and centred in the block, which
  the renderer carries round. Jonah set the front shelf's top, front edge and right end; its back edge, left end and
  underside are the like samples chosen here (`SHELF_FACES`).
- `models/block/book_wheel_shelves_at_rest.json`: the four shelves where the project rests them, for the item, the
  back one level (`LEVEL`).
- `blockstates/book_wheel.json`: the stand in its wood, turned by facing; the shelf and the arms, unturned, for the
  renderer, which turns them itself.
- `items/book_wheel.json`: the stand, the arms and the shelves at rest, in the item's `wood`.

The arms and the shelves move, so `BookWheelRenderer` draws them; the block's own model is only the stand. Run it
again whenever the project changes.
"""
import json
import sys
from pathlib import Path

ASSETS = Path(__file__).resolve().parents[2] / "common/src/main/resources/assets/agesandtheart"

WOODS = [
    "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak", "bamboo", "crimson",
    "warped", "poplar", "palm",
]

# The palm's own planks are birch's under this tint, as `PalmWood.PLANKS_TINT` has it.
PALM_TINT = 16763036

FACINGS = {"north": 0, "east": 90, "south": 180, "west": 270}

SHELF_NAMES = {"low shelf", "front shelf", "top shelf", "back shelf"}

# The shelves that rest level whatever the project tilts them, so that only the front one slopes (Jonah, 2026-11-19).
LEVEL = {"back shelf"}

LECTERN = {
    "top": "minecraft:block/lectern_top",
    "sides": "minecraft:block/lectern_sides",
    "base": "minecraft:block/lectern_base",
}

# Each shelf face's texture and UV. Jonah's front shelf set `up`, `north` and `east`; the rest are like samples: the
# back edge the front's strip a little along, the left end the right's mirrored, and the underside a run of the
# lectern base's boards, as vanilla's lectern puts planks under its top.
SHELF_FACES = {
    "up": ("top", None),
    "north": ("sides", None),
    "east": ("sides", None),
    "south": ("sides", [2, 15, 16, 16]),
    "west": ("sides", [9, 1, 1, 2]),
    "down": ("base", [1, 0, 15, 8]),
}

# The shelf as the renderer carries it: the project's shelves are 14 by 1 by 8; this one is centred on the block's.
SHELF_FROM = [1, 7.5, 4]
SHELF_TO = [15, 8.5, 12]

# A face lying on the block's edge, hidden where a block beside it covers it.
EDGES = {"down": (1, 0), "up": (1, 16), "north": (2, 0), "south": (2, 16), "west": (0, 0), "east": (0, 16)}


def write(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


def planks_of(wood):
    return "minecraft:block/birch_planks" if wood == "palm" else f"minecraft:block/{wood}_planks"


def rotation_of(element):
    angles = element.get("rotation") or [0, 0, 0]
    turned = [(axis, angle) for axis, angle in zip("xyz", angles) if angle]
    if not turned:
        return None
    if len(turned) > 1:
        sys.exit(f"{element['name']} turns on more than one axis")
    axis, angle = turned[0]
    return {"angle": angle, "axis": axis, "origin": element["origin"]}


def wooden(element, tinted):
    faces = {}
    for name, face in element["faces"].items():
        written = {"uv": face["uv"], "texture": "#planks"}
        axis, at = EDGES[name]
        if element["from" if at == 0 else "to"][axis] == at:
            written["cullface"] = name
        if tinted:
            written["tintindex"] = 0
        faces[name] = written
    shaped = {"from": element["from"], "to": element["to"], "faces": faces}
    if rotation := rotation_of(element):
        shaped["rotation"] = rotation
    return shaped


def shelf_faces(front_shelf, textures):
    faces = {}
    for name, (texture, uv) in SHELF_FACES.items():
        if uv is None:
            set_by_jonah = front_shelf["faces"][name]
            used = textures[set_by_jonah["texture"]]
            if LECTERN[texture] != f"minecraft:block/{used['name'].removesuffix('.png')}":
                sys.exit(f"the front shelf's {name} is on {used['name']}, not lectern_{texture}")
            uv = set_by_jonah["uv"]
        faces[name] = {"uv": uv, "texture": f"#{texture}"}
    return faces


def groups_of(project):
    by_uuid = {element["uuid"]: element for element in project["elements"]}
    names = {group["uuid"]: group["name"] for group in project["groups"]}
    return {names[entry["uuid"]]: [by_uuid[child] for child in entry["children"]] for entry in project["outliner"]}


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    project = json.loads(Path(sys.argv[1]).read_text())
    groups = groups_of(project)
    display = project["display"]
    stand = groups["base"]
    arms = [element for element in groups["wheel"] if element["name"] not in SHELF_NAMES]
    shelves = {element["name"]: element for element in groups["wheel"] if element["name"] in SHELF_NAMES}
    faces = shelf_faces(shelves["front shelf"], project["textures"])
    credit = "Made with Blockbench; textured by scripts/books/book_wheel_models.py"

    for part, elements in (("stand", stand), ("arms", arms)):
        for suffix, tinted in (("", False), ("_tinted", True)):
            write(ASSETS / f"models/block/book_wheel_{part}{suffix}.json", {
                "credit": credit,
                "parent": "minecraft:block/block",
                "textures": {"particle": "#planks"},
                "display": display,
                "elements": [wooden(element, tinted) for element in elements],
            })
        for wood in WOODS:
            parent = f"book_wheel_{part}_tinted" if wood == "palm" else f"book_wheel_{part}"
            write(ASSETS / f"models/block/book_wheel_{part}_{wood}.json", {
                "parent": f"agesandtheart:block/{parent}",
                "textures": {"planks": planks_of(wood)},
            })

    lectern_textures = {**LECTERN, "particle": LECTERN["sides"]}
    write(ASSETS / "models/block/book_wheel_shelf.json", {
        "credit": credit,
        "textures": lectern_textures,
        "elements": [{"from": SHELF_FROM, "to": SHELF_TO, "faces": faces}],
    })

    at_rest = []
    for name in ("front shelf", "top shelf", "back shelf", "low shelf"):
        shelf = shelves[name]
        element = {"from": shelf["from"], "to": shelf["to"], "faces": faces}
        if name not in LEVEL and (rotation := rotation_of(shelf)):
            element["rotation"] = rotation
        at_rest.append(element)
    write(ASSETS / "models/block/book_wheel_shelves_at_rest.json", {
        "credit": credit,
        "parent": "minecraft:block/block",
        "textures": lectern_textures,
        "display": display,
        "elements": at_rest,
    })

    variants = {}
    for wood in WOODS:
        for facing, turn in FACINGS.items():
            variant = {"model": f"agesandtheart:block/book_wheel_stand_{wood}"}
            if turn:
                variant["y"] = turn
            variants[f"facing={facing},piece=stand,wood={wood}"] = variant
        variants[f"piece=arms,wood={wood}"] = {"model": f"agesandtheart:block/book_wheel_arms_{wood}"}
    variants["piece=shelf"] = {"model": "agesandtheart:block/book_wheel_shelf"}
    write(ASSETS / "blockstates/book_wheel.json", {
        "_comment": "[AI] Jonah's small book wheel in the wood it was built of: the stand by facing; the arms and a shelf unturned, for BookWheelRenderer.",
        "variants": variants,
    })

    def in_wood(model, wood):
        written = {"type": "minecraft:model", "model": model}
        if wood == "palm":
            written["tints"] = [{"type": "minecraft:constant", "value": PALM_TINT}]
        return written

    def whole(wood):
        return {
            "type": "minecraft:composite",
            "models": [
                in_wood(f"agesandtheart:block/book_wheel_stand_{wood}", wood),
                in_wood(f"agesandtheart:block/book_wheel_arms_{wood}", wood),
                {"type": "minecraft:model", "model": "agesandtheart:block/book_wheel_shelves_at_rest"},
            ],
        }

    write(ASSETS / "items/book_wheel.json", {
        "_comment": "[AI] The small book wheel in its wood, its shelves at rest.",
        "model": {
            "type": "minecraft:select",
            "property": "minecraft:block_state",
            "block_state_property": "wood",
            "cases": [{"when": wood, "model": whole(wood)} for wood in WOODS],
            "fallback": whole("oak"),
        },
    })


if __name__ == "__main__":
    main()
