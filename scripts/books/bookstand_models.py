#!/usr/bin/env python3
"""
The bookstand's models, in every wood, from Jonah's Blockbench project.

    scripts/books/bookstand_models.py <path to bookstand.bbmodel>

Writes, under the mod's assets:

- `models/block/bookstand.json`, the project's elements with every face on `#planks`, and `bookstand_tinted.json`, the
  same with every face tinted, for the palm, whose planks are birch's under a tint until its own are drawn.
- `models/block/bookstand_<wood>.json` for each of `WoodKind`'s woods, the planks it is drawn in.
- `blockstates/bookstand.json`: each wood's model, turned by facing.
- `items/bookstand.json`: each wood's model, chosen by the item's `wood`.

The books are not in the model; `BookstandRenderer` draws them. Run it again whenever the project changes.
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

# The faces on the block's own edges, in the model's frame, hidden where a block beside them covers them.
CULLED_AT = {"down": ("y", 0), "west": ("x", 0), "east": ("x", 16)}
AXES = {"x": 0, "y": 1, "z": 2}


def planks_of(wood):
    return "minecraft:block/birch_planks" if wood == "palm" else f"minecraft:block/{wood}_planks"


def element_of(element, tinted):
    faces = {}
    for name, face in element["faces"].items():
        written = {"uv": face["uv"], "texture": "#planks"}
        if name in CULLED_AT:
            axis, at = CULLED_AT[name]
            edge = element["from" if at == 0 else "to"][AXES[axis]]
            if edge == at:
                written["cullface"] = name
        if tinted:
            written["tintindex"] = 0
        faces[name] = written
    return {"from": element["from"], "to": element["to"], "faces": faces}


def write(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    project = json.loads(Path(sys.argv[1]).read_text())
    exported = [element for element in project["elements"] if element.get("export", True)]

    for name, tinted in (("bookstand", False), ("bookstand_tinted", True)):
        write(ASSETS / f"models/block/{name}.json", {
            "credit": "Made with Blockbench; textured by wood by scripts/books/bookstand_models.py",
            "parent": "minecraft:block/block",
            "textures": {"particle": "#planks"},
            "elements": [element_of(element, tinted) for element in exported],
        })

    for wood in WOODS:
        parent = "bookstand_tinted" if wood == "palm" else "bookstand"
        write(ASSETS / f"models/block/bookstand_{wood}.json", {
            "parent": f"agesandtheart:block/{parent}",
            "textures": {"planks": planks_of(wood)},
        })

    variants = {}
    for wood in WOODS:
        for facing, turn in FACINGS.items():
            variant = {"model": f"agesandtheart:block/bookstand_{wood}"}
            if turn:
                variant["y"] = turn
            variants[f"facing={facing},wood={wood}"] = variant
    write(ASSETS / "blockstates/bookstand.json", {
        "_comment": "[AI] Jonah's bookstand, in the wood it was built of. The palm's is birch under its tint.",
        "variants": variants,
    })

    def model_of(wood):
        model = {"type": "minecraft:model", "model": f"agesandtheart:block/bookstand_{wood}"}
        if wood == "palm":
            model["tints"] = [{"type": "minecraft:constant", "value": PALM_TINT}]
        return model

    write(ASSETS / "items/bookstand.json", {
        "_comment": "[AI] The bookstand in its wood.",
        "model": {
            "type": "minecraft:select",
            "property": "minecraft:block_state",
            "block_state_property": "wood",
            "cases": [{"when": wood, "model": model_of(wood)} for wood in WOODS],
            "fallback": model_of("oak"),
        },
    })


if __name__ == "__main__":
    main()
