#!/usr/bin/env python3
"""
A held book's first-person display, set in Blockbench.

    scripts/books/age_book_held.py <path to converted_linking_book.bbmodel>

A descriptive or linking book is drawn by code (`AgeBookItemRenderer`), so Blockbench cannot show it. In first person
the shut book stands exactly where Jonah's `converted_linking_book.bbmodel` has it, part for part (the renderer's
`FIRST_PERSON_X`, `_Y` and `_Z`), so the first-person display set on that project places the book as Blockbench shows.
This copies that display into `models/item/age_book.json`. Third person is not taken: there the renderer lays the book
flat itself, from the world.
"""
import json
import sys
from pathlib import Path

ITEM_MODEL = Path(__file__).resolve().parents[2] / "common/src/main/resources/assets/agesandtheart/models/item/age_book.json"
FIRST_PERSON = ("firstperson_righthand", "firstperson_lefthand")


def main():
    project = json.loads(Path(sys.argv[1]).read_text())
    taken = {k: v for k, v in project.get("display", {}).items() if k in FIRST_PERSON}
    assert taken, "the project has no first-person display"
    model = json.loads(ITEM_MODEL.read_text())
    for context in FIRST_PERSON:
        model["display"].pop(context, None)
    model["display"].update(taken)
    ITEM_MODEL.write_text(json.dumps(model, indent=2) + "\n")
    print("first-person display taken:", ", ".join(f"{k} {v}" for k, v in sorted(taken.items())))


main()
