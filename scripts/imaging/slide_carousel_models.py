#!/usr/bin/env python3
"""
The slide carousel's models, from Jonah's Blockbench project.

    scripts/imaging/slide_carousel_models.py <path to slide_carousel.bbmodel>

Writes, under the companion's assets:

- `models/block/slide_carousel.json`: what never moves, the base and the focusing lens, baked with the world.
- `models/item/slide_carousel.json`: the whole carousel at rest, arm raised and no slides in it.
- `carousel/parts.json`: what moves, drawn by `SlideCarouselRenderer` each frame: the disk, one slide (drawn in every
  occupied slot), the selector arm, its hook and the two buttons, as quads in the model's own pixels, with the pivots
  each turns about read off the project.

Run it again whenever the project changes. Three things are ours rather than the project's:

- **The disk is rebuilt exactly**: eight boxes 14 long and 14 tan 11.25 wide, centred on the disk's axle and turned in
  steps of 22.5 degrees, so their union is a regular sixteen-sided disk whatever the project's fillers say. Drawn, it is
  their sixteen ends round the rim and one sixteen-sided face top and bottom, since the boxes' own tops lie in one
  plane and would flicker; in the item model their tops and bottoms are a hair apart instead.
- **Textures stand in** by part until the project has its own: oak for the base and disk, glass for the slides' faces and
  the lens with grey for the slides' edges, iron for the arms, the hook and the lens arm, stone for the buttons.
- **The main slide** is the one slide the project leaves unturned; every other slot draws it turned about the axle.
"""
import json
import math
import sys
from pathlib import Path

ASSETS = Path(__file__).resolve().parents[2] / "imaging/common/src/main/resources/assets/agesandtheart_imaging"

OAK = "minecraft:block/oak_planks"
GLASS = "minecraft:block/glass"
IRON = "minecraft:block/iron_block"
STONE = "minecraft:block/stone"
PLATE_EDGE = "minecraft:block/gray_concrete"
TEXTURES = {"oak": OAK, "glass": GLASS, "iron": IRON, "stone": STONE, "plate_edge": PLATE_EDGE}

DISK_ACROSS = 14.0
DISK_WIDTH = DISK_ACROSS * math.tan(math.radians(11.25))


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


def rounded(v, places=4):
    return [round(x, places) for x in v]


def turning(rotation):
    """The matrix of a Blockbench rotation, x then y then z, as 26.3's element rotation applies it."""
    x, y, z = rotation or (0, 0, 0)
    return times(rotation_z(z), times(rotation_y(y), rotation_x(x)))


# Each face's corners seen from outside: top left, bottom left, bottom right, top right, as vanilla bakes them.
FACE_CORNERS = {
    "north": lambda a, b: [(b[0], b[1], a[2]), (b[0], a[1], a[2]), (a[0], a[1], a[2]), (a[0], b[1], a[2])],
    "south": lambda a, b: [(a[0], b[1], b[2]), (a[0], a[1], b[2]), (b[0], a[1], b[2]), (b[0], b[1], b[2])],
    "west": lambda a, b: [(a[0], b[1], a[2]), (a[0], a[1], a[2]), (a[0], a[1], b[2]), (a[0], b[1], b[2])],
    "east": lambda a, b: [(b[0], b[1], b[2]), (b[0], a[1], b[2]), (b[0], a[1], a[2]), (b[0], b[1], a[2])],
    "up": lambda a, b: [(a[0], b[1], a[2]), (a[0], b[1], b[2]), (b[0], b[1], b[2]), (b[0], b[1], a[2])],
    "down": lambda a, b: [(a[0], a[1], b[2]), (a[0], a[1], a[2]), (b[0], a[1], a[2]), (b[0], a[1], b[2])],
}
FACE_NORMALS = {"north": (0, 0, -1), "south": (0, 0, 1), "west": (-1, 0, 0), "east": (1, 0, 0), "up": (0, 1, 0), "down": (0, -1, 0)}
FACE_SPANS = {"north": (0, 1), "south": (0, 1), "west": (2, 1), "east": (2, 1), "up": (0, 2), "down": (0, 2)}


def has_area(name, low, high):
    first, second = FACE_SPANS[name]
    return high[first] - low[first] > 1e-6 and high[second] - low[second] > 1e-6


def corner_uvs(uv, rotation):
    u1, v1, u2, v2 = [value / 16.0 for value in uv]
    corners = [(u1, v1), (u1, v2), (u2, v2), (u2, v1)]
    steps = (rotation or 0) // 90
    return corners[-steps:] + corners[:-steps] if steps else corners


def quads_of(element, texture, edges=None):
    """An element's faces as quads in the model's pixels, turned as the element is turned, its edges in [edges] if said."""
    low, high = element["from"], element["to"]
    m = turning(element.get("rotation"))
    origin = element.get("origin", [0, 0, 0])
    quads = []
    for name, face in element["faces"].items():
        if face.get("enabled") is False or not has_area(name, low, high):
            continue
        uvs = corner_uvs(face["uv"], face.get("rotation"))
        corners = []
        for (x, y, z), (u, v) in zip(FACE_CORNERS[name](low, high), uvs):
            turned = applied(m, [x - origin[0], y - origin[1], z - origin[2]])
            corners.append(rounded([turned[0] + origin[0], turned[1] + origin[1], turned[2] + origin[2]]) + [round(u, 5), round(v, 5)])
        own = texture if edges is None or name in ("north", "south") else edges
        quads.append({"texture": TEXTURES[own], "normal": rounded(applied(m, FACE_NORMALS[name])), "corners": corners})
    return quads


def json_element(element, texture):
    """An element as the block model format says it, its rotation in the three-axis form 26.3 reads."""
    out = {"from": rounded(element["from"]), "to": rounded(element["to"])}
    rotation = element.get("rotation")
    if rotation and any(rotation):
        out["rotation"] = {"origin": rounded(element.get("origin", [8, 8, 8])), "x": rotation[0], "y": rotation[1], "z": rotation[2]}
    faces = {}
    for name, face in element["faces"].items():
        if face.get("enabled") is False or not has_area(name, element["from"], element["to"]):
            continue
        f = {"uv": face["uv"], "texture": f"#{texture}"}
        if face.get("rotation"):
            f["rotation"] = face["rotation"]
        faces[name] = f
    out["faces"] = faces
    return out


def disk_elements(axle):
    """Eight boxes whose union is a regular sixteen-sided disk, centred on [axle], as tall as the project's fillers."""
    low_y, high_y = axle["low_y"], axle["high_y"]
    x, z = axle["x"], axle["z"]
    box = {
        "from": [x - DISK_WIDTH / 2, low_y, z - DISK_ACROSS / 2],
        "to": [x + DISK_WIDTH / 2, high_y, z + DISK_ACROSS / 2],
        "origin": [x, (low_y + high_y) / 2, z],
    }
    faces = {
        "north": {"uv": [0, 0, DISK_WIDTH, high_y - low_y]}, "south": {"uv": [0, 0, DISK_WIDTH, high_y - low_y]},
        "east": {"uv": [0, 0, DISK_ACROSS, high_y - low_y]}, "west": {"uv": [0, 0, DISK_ACROSS, high_y - low_y]},
        "up": {"uv": [0, 0, DISK_WIDTH, DISK_ACROSS]}, "down": {"uv": [0, 0, DISK_WIDTH, DISK_ACROSS]},
    }
    return [dict(box, rotation=[0, step * 22.5, 0], faces=faces) for step in range(8)]


def disk_quads(axle):
    """The disk as drawn: its rim, the boxes' ends, and a sixteen-sided face top and bottom, its texture laid flat."""
    rim = []
    for element in disk_elements(axle):
        rim += [q for q in quads_of(dict(element, faces={k: v for k, v in element["faces"].items() if k in ("north", "south")}), "oak")]
    centre = [axle["x"], 0.0, axle["z"]]
    corner_radius = DISK_ACROSS / 2 / math.cos(math.radians(11.25))
    ring = [[centre[0] + corner_radius * math.sin(math.radians(11.25 + 22.5 * k)), centre[2] + corner_radius * math.cos(math.radians(11.25 + 22.5 * k))] for k in range(16)]
    caps = []
    for y, facing_up in ((axle["high_y"], True), (axle["low_y"], False)):
        for k in range(16):
            a, b = ring[k], ring[(k + 1) % 16]
            points = [[centre[0], y, centre[2]], [a[0], y, a[1]], [b[0], y, b[1]]]
            first, second = [points[1][i] - points[0][i] for i in range(3)], [points[2][i] - points[1][i] for i in range(3)]
            normal_y = first[2] * second[0] - first[0] * second[2]
            if (normal_y > 0) != facing_up:
                points = [points[0], points[2], points[1]]
            corners = [rounded(pt) + [round(pt[0] / 16, 5), round(pt[2] / 16, 5)] for pt in points + [points[2]]]
            caps.append({"texture": OAK, "normal": [0, 1 if facing_up else -1, 0], "corners": corners})
    return rim + caps


def walk(project):
    """Every element under its top-level group's name and its own group's name, in the project's order."""
    elements = {e["uuid"]: e for e in project["elements"]}
    groups = {g["uuid"]: g for g in project.get("groups", [])}
    found = []

    def visit(node, path):
        if isinstance(node, str):
            found.append((path, elements[node]))
            return
        group = groups.get(node.get("uuid"), node)
        for child in node.get("children", []):
            visit(child, path + [group.get("name")])

    for top in project["outliner"]:
        visit(top, [])
    return found


def main():
    project = json.loads(Path(sys.argv[1]).read_text())
    assert project["meta"]["model_format"] == "java_block", "the carousel is a Java block model"
    walked = walk(project)

    def under(name):
        return [element for path, element in walked if name in path]

    base = [e for e in under("base") if "focusing lens" not in [p for p, x in walked if x is e][0] and "controls" not in [p for p, x in walked if x is e][0]]
    lens = under("focusing lens")
    controls = under("controls")
    wedges = [e for e in under("carousel") if e["name"] == "wedge"]
    slides = [e for e in under("carousel") if e["name"] == "slide"]
    hook = under("hook")
    arm = [e for e in under("selector arm") if e not in hook]

    main_slide = next(e for e in slides if not any(e.get("rotation") or [0, 0, 0]) and e["from"][0] < 8)
    # The axle is the middle of the fillers' boxes, where the disk is centred, whatever pivot they were given. Its
    # thickness is the thinnest filler's, the others being a hair apart in the project to keep its view from flickering.
    unturned = min(wedges, key=lambda w: w["to"][1] - w["from"][1])
    axle = {
        "x": (unturned["from"][0] + unturned["to"][0]) / 2,
        "z": (unturned["from"][2] + unturned["to"][2]) / 2,
        "low_y": unturned["from"][1],
        "high_y": unturned["to"][1],
    }
    disk = disk_elements(axle)

    def lens_texture(e):
        return "glass" if e["name"] == "lens" else "iron"

    static = [(e, "oak") for e in base] + [(e, lens_texture(e)) for e in lens]
    buttons = {e["name"]: e for e in controls}
    textures = {"oak": OAK, "glass": GLASS, "iron": IRON, "stone": STONE, "particle": OAK}

    block = {
        "_comment": "Generated by scripts/imaging/slide_carousel_models.py from Jonah's Blockbench project: what never moves, "
                    "the base and the focusing lens. The disk, slides, arm, hook and buttons are carousel/parts.json, drawn by "
                    "the carousel's renderer. Edit the project and run it again.",
        "textures": textures,
        "elements": [json_element(e, t) for e, t in static],
    }
    # A hair apart, so the boxes' tops and bottoms do not fight in the one plane.
    staggered = [dict(e, **{"from": [e["from"][0], e["from"][1] - k * 0.002, e["from"][2]], "to": [e["to"][0], e["to"][1] + k * 0.002, e["to"][2]]}) for k, e in enumerate(disk)]
    at_rest = static + [(e, "oak") for e in staggered] + [(e, "iron") for e in arm + hook] + [(e, "stone") for e in controls]
    item = {
        "_comment": "Generated by scripts/imaging/slide_carousel_models.py: the whole carousel at rest, arm raised and empty.",
        "parent": "minecraft:block/block",
        "textures": textures,
        "elements": [json_element(e, t) for e, t in at_rest],
    }

    lens_element = next(e for e in lens if e["name"] == "lens")
    lens_corners = [applied(turning(lens_element["rotation"]), [c[i] - lens_element["origin"][i] for i in range(3)]) for c in (lens_element["from"], lens_element["to"])]
    lens_box = [[lens_corners[j][i] + lens_element["origin"][i] for i in range(3)] for j in range(2)]
    lens_low = [min(lens_box[0][i], lens_box[1][i]) for i in range(3)]
    lens_high = [max(lens_box[0][i], lens_box[1][i]) for i in range(3)]
    slide_pivot = main_slide["origin"]
    held_low = [2 * slide_pivot[0] - main_slide["to"][0], 2 * slide_pivot[1] - main_slide["to"][1], main_slide["from"][2]]
    held_high = [2 * slide_pivot[0] - main_slide["from"][0], 2 * slide_pivot[1] - main_slide["from"][1], main_slide["to"][2]]

    parts = {
        "_comment": "Generated by scripts/imaging/slide_carousel_models.py from Jonah's Blockbench project: the carousel's moving "
                    "parts as quads in the model's pixels (x, y, z, u, v per corner), and the pivots they turn about.",
        "pivots": {
            "axle": rounded([axle["x"], (axle["low_y"] + axle["high_y"]) / 2, axle["z"]]),
            "hook": rounded(hook[0]["origin"]),
            "slide": rounded(slide_pivot),
        },
        "light": {
            "lens_low": rounded(lens_low),
            "lens_high": rounded(lens_high),
            "held_slide_low": rounded(held_low),
            "held_slide_high": rounded(held_high),
        },
        "parts": {
            "disk": disk_quads(axle),
            # Its picture faces glass until a picture is on them; its edges opaque, glass's own middle being clear.
            "slide": quads_of(main_slide, "glass", edges="plate_edge"),
            "arm": [q for e in arm for q in quads_of(e, "iron")],
            "hook": [q for e in hook for q in quads_of(e, "iron")],
            "button_clockwise": quads_of(buttons["clockwise"], "stone"),
            "button_counter_clockwise": quads_of(buttons["counter clockwise"], "stone"),
        },
    }
    (ASSETS / "models/block/slide_carousel.json").write_text(json.dumps(block, indent=2) + "\n")
    (ASSETS / "models/item/slide_carousel.json").write_text(json.dumps(item, indent=2) + "\n")
    (ASSETS / "carousel").mkdir(exist_ok=True)
    (ASSETS / "carousel/parts.json").write_text(json.dumps(parts, indent=1) + "\n")
    counts = {name: len(quads) for name, quads in parts["parts"].items()}
    print(f"block {len(block['elements'])} elements, item {len(item['elements'])}, moving parts {counts}")
    print("pivots", parts["pivots"], "light", parts["light"])


main()
