package co.voik.agesandtheart.preview.authoring.ui

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.preview.authoring.Corpus
import co.voik.agesandtheart.preview.authoring.TagFile
import co.voik.agesandtheart.preview.authoring.TagLayer
import com.github.ajalt.mordant.input.MouseTracking
import com.github.ajalt.mordant.input.enterRawMode
import com.github.ajalt.mordant.terminal.Terminal

/**
 * The tag layer, read and retuned — **every tag as a set, and everything in it.**
 *
 * **A tag owns members; it is not a label stuck on them** (Jonah, 2026-09-02). Both readings describe the
 * same data and only one of them makes the screen's verbs come out right: you empty a set, take a member
 * out of one, put one back. Said the other way the same acts are "take the tag off", "give it back", and
 * a reader has to reverse the sentence before every keystroke.
 *
 * The primary view is one tag's members rather than one preset's tags, because that is how both
 * hand-tuning passes actually worked: asking each set what it had picked up is how all eleven faults in
 * `notes/the-tag-layer.md` §7 were found, and reading the rules never found any of them.
 *
 * **The overlay has to be legible or the screen is a liar** — see [TagLayer]. Every row says whether its
 * weight was authored or derived, and what the derivation had said underneath where an entry stands over
 * one.
 */
class Tags(
    private val terminal: Terminal,
    private val canvas: Canvas,
    private val corpus: Corpus,
) {

    private val layer = TagLayer(corpus)

    /** The whole list, from the top. */
    fun run() {
        // **The way to make one is a row, and a failed search is the other way in** — both, because
        // somebody looking for a tag that is not there has already typed its name, and somebody who has
        // not looked should not have to search for nothing to find the door.
        val rowsOf = { listOf(makingRow()) + layer.facts().map(::factRow) }
        val table = Table(
            title = "tag editor",
            columns = listOf(
                Table.Column("tag", TAG_WIDTH),
                Table.Column("members", COUNT_WIDTH),
                Table.Column("words using", COUNT_WIDTH),
                Table.Column("where", WHERE_WIDTH),
                Table.Column("", NOTE_WIDTH),
            ),
            rows = canvas.whileBusy("Reading the tag layer") { rowsOf() },
            whenNothingMatches = { typed ->
                if (couldBeATag(typed)) "no set matches '$typed' — enter to make it"
                else "no set matches '$typed'"
            },
        )
        while (true) {
            val chosen = walkTheList(table) ?: return
            if (chosen == MAKE) makeATag(table.filter.takeIf(::couldBeATag)) else open(chosen)
            table.withRows(rowsOf())
        }
    }

    private fun makingRow() = Table.Row(
        key = MAKE,
        cells = listOf("+ a new set", "", "", "", "named, then given its first member"),
        tone = Palette.faint,
    )

    /**
     * A tag made — **named, and then given its first member in the same breath.**
     *
     * A tag nothing carries is one the top list already reports as a fault, and it is one a word can ask
     * for and find nothing by. So there is no state in which one exists and is empty: the name is only
     * half of making one, and leaving before the other half makes nothing.
     */
    private fun makeATag(searched: String?) {
        val standing = layer.facts().map { it.tag }.toSet()
        val named = searched?.takeUnless { it in standing } ?: ask(
            title = "a new set",
            hint = "what the world is like — `wooded`, `molten`, `ruined` ${Glyph.BULLET} " +
                "${standing.size} so far",
        ) { said ->
            when {
                said.isBlank() -> "a set needs a name"
                said in standing -> "'$said' is already a set"
                !said.matches(LEGAL_TAG) -> "lower case, digits and _ only"
                else -> null
            }
        } ?: return
        addAMember(named)
        layer.reread()
        // Nothing was written where the member was never chosen, and a name alone is not a tag.
        if (layer.membersTagged(named).isNotEmpty()) open(named)
    }

    private fun couldBeATag(said: String) = said.matches(LEGAL_TAG)

    /**
     * A tag unwritten everywhere, once somebody has read what that costs — **true where it is gone.**
     *
     * The one edit here that no other edit undoes: a weight cleared comes back off a rule, a drop is
     * restored by the key that made it, and a rename can be renamed back. This takes lines out of files
     * across three directories, and the count of them is the whole of what a reader needs before saying
     * yes — so the question is what will be touched rather than "are you sure".
     */
    private fun deleted(tag: String): Boolean {
        val members = layer.membersTagged(tag)
        val asked = layer.askedBy(tag)
        val rules = TagFile.rulesGranting(tag, corpus)
        val says = buildList {
            add(Line("${Glyph.WARN} this cannot be undone", Palette.refused))
            add(Line.BLANK)
            add(Line("empties $TAG_MARK$tag of its ${members.size} member(s)", Palette.value))
            add(
                if (asked.isEmpty()) Line("no word asks for it", Palette.faint)
                else Line("and removes it from ${asked.size} word(s): ${asked.joinToString(" ")}", Palette.value),
            )
            // **A rule would put it straight back**, and this cannot reach one: a rule moves dozens of
            // members at once and is the rules screen's to delete, deliberately.
            if (rules.isNotEmpty()) {
                add(Line.BLANK)
                add(Line("${Glyph.WARN} ${rules.size} rule(s) automatically populate it:", Palette.warned))
                rules.forEach { add(Line("    $it", Palette.faint)) }
            }
        }
        if (!confirmed("delete '$tag'?", says)) return false
        val touched = canvas.whileBusy("Deleting") { TagFile.deleteTag(tag) }
        layer.reread()
        show(
            "'$tag' is gone",
            listOf(Line("changed ${touched.size} files", Palette.settled)) +
                touched.map { Line("  $it", Palette.faint) },
        )
        return true
    }

    /** A dangerous thing asked about, and answered only by the key that says the word. */
    private fun confirmed(title: String, says: List<Line>): Boolean {
        terminal.enterRawMode(MouseTracking.Off).use { scope ->
            while (true) {
                canvas.show(
                    listOf(Line("  $title", Palette.refused), Line.BLANK) +
                        says.flatMap { (Line("  ") + it).wrapped(canvas.width) } +
                        listOf(
                            Frame.rule(canvas.width),
                            hints("y" to "delete it", "anything else" to "keep it"),
                        ),
                )
                val key = scope.readKey() ?: return false
                if (key.ctrl && key.key == "c") throw Leaving()
                return key.key == "y" && !key.ctrl
            }
        }
    }

    /** One tag, opened straight — what the word editor does when the cursor is on a query row. */
    fun open(tag: String) {
        // **Grouped by where the weight came from, until asked otherwise.** What somebody wrote by hand is
        // what somebody has already thought about, and reading that against the derived mass underneath is
        // the whole of a tuning pass; alphabetical is for when you know the name and want the row.
        var grouped = true
        var named = tag
        // **The way to add one is a row**, above what is already there. It filters away as soon as
        // anything is typed, which is right: while you are searching you are looking, not adding.
        val rowsOf = { listOf(addingRow(named)) + layer.membersTagged(named, grouped).map(::memberRow) }
        val table = Table(
            title = "what is in '$tag'",
            columns = listOf(
                Table.Column("aspect", ASPECT_WIDTH),
                Table.Column("member", CARRIER_WIDTH),
                Table.Column("weight", WEIGHT_WIDTH, WEIGHT),
                Table.Column("source", SOURCE_WIDTH),
                Table.Column("", NOTE_WIDTH),
            ),
            rows = canvas.whileBusy("Reading the tag layer") { rowsOf() },
            whenEmpty = "this set is empty \u2014 a word asking for it would find nothing",
        )
        terminal.enterRawMode(MouseTracking.Off).use { scope ->
            while (true) {
                canvas.show(memberLines(table, named, grouped))
                val key = scope.readKey() ?: return
                if (key.ctrl && key.key == "c") throw Leaving()
                val row = table.focused

                fun rebuild() {
                    layer.reread()
                    table.withRows(rowsOf())
                }

                fun bump(by: Int) {
                    val member = memberFor(row, named) ?: return
                    retune(member, named, by)
                    rebuild()
                }

                /**
                 * Back to what the rules had said — which is the only thing "reset" can mean here.
                 *
                 * An overridden weight loses its line and the rule's value returns; a dropped one is
                 * un-dropped. A row standing on nothing has nothing to go back to, which is why the key
                 * is offered on neither.
                 */
                fun reset() {
                    val member = memberFor(row, named)?.takeIf { it.under != null } ?: return
                    if (member.source == TagLayer.Source.DROPPED) {
                        TagFile.setDropped(member.aspect.page, member.preset, named, dropped = false)
                    } else {
                        carry(member, named, null)
                    }
                    rebuild()
                }

                when {
                    key.ctrl && (key.key == "q" || key.key == "c") -> return
                    key.key == "Escape" -> if (table.isFiltered) table.clearFilter() else return
                    key.key == "ArrowLeft" -> if (table.column == 0) return else table.across(-1)
                    key.key == "ArrowRight" -> table.across(1)
                    key.key == "ArrowUp" -> table.move(-1)
                    key.key == "ArrowDown" -> table.move(1)
                    key.key == "Home" -> table.home()
                    key.key == "End" -> table.end()
                    key.key == "PageUp" -> table.page(-1)
                    key.key == "PageDown" -> table.page(1)
                    key.key == "Tab" -> { grouped = !grouped; table.withRows(rowsOf()) }
                    key.key == "Enter" -> if (row?.key == ADD) {
                        addAMember(named)
                        rebuild()
                    }
                    key.key == "Backspace" -> table.backspace()
                    // **`^o` for the original value**, which is the column's own word. Backspace was the
                    // obvious key and is the search's: taking it meant finding a row by typing and then
                    // clearing the search before the row could be reset.
                    key.ctrl && key.key == "o" -> reset()
                    key.key == "=" -> bump(1)
                    key.key == "-" -> bump(-1)
                    key.ctrl && key.key == "d" -> {
                        memberFor(row, named)?.let { drop(it, named) }
                        rebuild()
                    }
                    key.ctrl && key.key == "r" -> {
                        named = renamed(named) ?: named
                        table.withRows(rowsOf())
                    }
                    key.ctrl && key.key == "w" -> showWhatAsks(named)
                    key.ctrl && key.key == "x" -> if (deleted(named)) return
                    key.key.length == 1 && !key.ctrl && !key.alt -> table.type(key.key)
                }
            }
        }
    }

    private fun memberFor(row: Table.Row?, tag: String): TagLayer.Member? {
        val key = row?.key ?: return null
        return layer.membersTagged(tag).firstOrNull { keyOf(it) == key }
    }

    private fun addingRow(tag: String) = Table.Row(
        key = ADD,
        cells = listOf("+ add a member", "", "", "", "into $TAG_MARK$tag"),
        tone = Palette.faint,
    )

    /**
     * A member given this tag — **the part of the world first, then the thing**, as everywhere else.
     *
     * It lands at a whole weight and can be stepped from there, which is what a hand-written weight
     * usually is: an exception is written because something is *very* one thing or not one at all.
     */
    private fun addAMember(tag: String) {
        val offered = Aspect.entries.associateWith { layer.untaggedIn(it, tag) }
            .filterValues { it.isNotEmpty() }
        val aspect = ask("Add what, from where?", offered.keys.sortedBy { it.page }.map { one ->
            // **Two counts, and they are not the same one.** How many could take *this* tag is what the
            // list is for; how many carry nothing at all is the number worth acting on, and calling the
            // first "untagged" said the second's word about the first's number.
            val here = offered.getValue(one)
            val bare = here.count { it.carriesNothing }
            Picker.Option(
                value = one.page,
                label = one.page,
                note = "${here.size} without it" + if (bare == 0) "" else "  ${Glyph.BULLET}  $bare untagged",
            )
        }) ?: return
        val where = Aspect.entries.firstOrNull { it.page == aspect } ?: return
        // **Marked where nothing has ever described it** — no rule, no line. Those are the members the
        // Art cannot reach by any vague word, and for an open aspect a first tag is also what enrols one
        // in the pool a vague word draws from (world model §8.2).
        val untagged = layer.untaggedIn(where, tag)
        val preset = ask("Add which ${where.page}?", untagged.map { one ->
            Picker.Option(
                value = one.preset,
                label = one.preset,
                note = if (one.carriesNothing) "untagged" else "",
                tone = if (one.carriesNothing) Palette.warned else null,
            )
        }) ?: return
        TagFile.setWeight(where.page, preset, tag, WHOLLY)
    }

    /** One question, answered — the small blocking picker this screen's flows are made of. */
    private fun ask(title: String, options: List<Picker.Option>): String? {
        if (options.isEmpty()) return null
        var picked: String? = null
        val table = Table(
            title = title,
            columns = listOf(Table.Column("", CARRIER_WIDTH), Table.Column("", NOTE_WIDTH, grows = true)),
            rows = options.map { Table.Row(it.value, listOf(it.label, it.note), tone = it.tone) },
        )
        terminal.enterRawMode(MouseTracking.Off).use { scope ->
            while (true) {
                canvas.show(
                    tableLines(
                        table,
                        canvas,
                        listOf(hints("enter" to "take it", "←" to "back", searching(table.filter))),
                    ),
                )
                val key = scope.readKey() ?: return null
                if (key.ctrl && key.key == "c") throw Leaving()
                when {
                    key.ctrl && (key.key == "q" || key.key == "c") -> return null
                    key.key == "Escape" -> if (table.isFiltered) table.clearFilter() else return null
                    key.key == "ArrowLeft" -> return null
                    key.key == "ArrowUp" -> table.move(-1)
                    key.key == "ArrowDown" -> table.move(1)
                    key.key == "Home" -> table.home()
                    key.key == "End" -> table.end()
                    key.key == "PageUp" -> table.page(-1)
                    key.key == "PageDown" -> table.page(1)
                    key.key == "Backspace" -> table.backspace()
                    key.key == "Enter" -> { picked = table.focused?.key; return picked }
                    key.key.length == 1 && !key.ctrl && !key.alt -> table.type(key.key)
                }
            }
        }
    }

    private fun keyOf(member: TagLayer.Member) = "${member.aspect.page}/${member.preset}"

    /**
     * A member's weight, moved one step — and **which file that writes depends on where it came from.**
     *
     * Stepping a derived weight writes the first authored entry for that preset; stepping an authored one
     * down past nothing takes the entry out again and lets the derivation come back, which is the
     * distinction that makes the screen trustworthy.
     */
    private fun retune(member: TagLayer.Member, tag: String, by: Int) {
        if (member.source == TagLayer.Source.DROPPED) {
            if (by > 0) TagFile.setDropped(member.aspect.page, member.preset, tag, dropped = false)
            return
        }
        val wanted = member.weight + by * STEP
        carry(member, tag, wanted.coerceAtMost(1.0).takeIf { wanted >= STEP / 2 })
    }

    /**
     * A weight written for this member, or taken off where [weight] is null.
     *
     * Taking one off is not deleting the tag: where a rule granted it too, the rule's weight comes back —
     * which is what the row's last column says, and why `overridden` is its own word.
     */
    private fun carry(member: TagLayer.Member, tag: String, weight: Double?) {
        TagFile.setWeight(member.aspect.page, member.preset, tag, weight)
    }

    /** One member taken out of a set a rule put it in, or put back — `drop`, never deletion. */
    private fun drop(member: TagLayer.Member, tag: String) {
        val page = member.aspect.page
        when (member.source) {
            TagLayer.Source.DROPPED -> TagFile.setDropped(page, member.preset, tag, dropped = false)
            // A written weight would come back over the top of a drop, so it goes first.
            TagLayer.Source.AUTHORED, TagLayer.Source.OVERRIDDEN -> {
                TagFile.setWeight(page, member.preset, tag, null)
                TagFile.setDropped(page, member.preset, tag, dropped = true)
            }
            TagLayer.Source.DERIVED, TagLayer.Source.REMEMBERED ->
                TagFile.setDropped(page, member.preset, tag, dropped = true)
        }
    }

    /**
     * A tag renamed everywhere, after saying what that will touch.
     *
     * Named the sharpest thing on this screen in the plan and it is: the pass keeps doing renames, and a
     * rename done by hand reaches the carriers and forgets the words, which is silent — a query for a tag
     * nothing carries is perfectly legal and simply finds nothing.
     */
    private fun renamed(tag: String): String? {
        val asked = layer.askedBy(tag)
        val members = layer.membersTagged(tag).size
        val typed = ask(
            title = "rename '$tag'",
            hint = "$members members ${Glyph.BULLET} ${asked.size} words ask for it" +
                if (asked.isEmpty()) "" else " (${asked.joinToString(" ")})",
        ) { said ->
            when {
                said.isBlank() -> "a set needs a name"
                said == tag -> "that is the name it has"
                !said.matches(LEGAL_TAG) -> "lower case, digits and _ only"
                else -> null
            }
        } ?: return null
        val touched = canvas.whileBusy("Renaming") { TagFile.renameTag(tag, typed) }
        layer.reread()
        show(
            "'$tag' is now '$typed'",
            listOf(Line("changed ${touched.size} files", Palette.settled)) +
                touched.map { Line("  $it", Palette.faint) },
        )
        return typed
    }

    private fun showWhatAsks(tag: String) {
        val asked = layer.askedBy(tag)
        show(
            "words that ask for '$tag'",
            if (asked.isEmpty()) {
                listOf(
                    Line("nothing asks for it", Palette.warned),
                    Line("a tag nothing asks for is a distinction the world makes and the language cannot", Palette.faint),
                )
            } else {
                asked.map { name ->
                    val word = corpus.vocabulary.word(name)
                    Line(name.padEnd(24), Palette.value) +
                        Line(word?.let { "${it.tier.key}  ${it.aspects.joinToString(" ") { on -> on.page }}" }.orEmpty(), Palette.faint)
                }
            },
        )
    }

    private fun factRow(fact: TagLayer.Fact) = Table.Row(
        key = fact.tag,
        cells = listOf(
            "$TAG_MARK${fact.tag}",
            if (fact.members == 0) "" else fact.members.toString(),
            if (fact.asked == 0) "" else fact.asked.toString(),
            fact.aspects.joinToString(" ") { it.page },
            noteOn(fact),
        ),
        tone = when {
            fact.members == 0 && !fact.onlyOnAServer -> Palette.refused
            fact.asked == 0 -> Palette.warned
            else -> null
        },
    )

    private fun noteOn(fact: TagLayer.Fact): String = when {
        fact.members == 0 && fact.onlyOnAServer ->
            "needs a server snapshot to populate — load minecraft data"
        fact.members == 0 -> "used by a word, but empty"
        fact.asked == 0 -> "has members, not used by any word"
        fact.opposed -> "opposed in the antonym table"
        else -> ""
    }

    /**
     * One member — and **the last column is what a rule had said**, where a rule said anything.
     *
     * Only `overridden` and `dropped` have one: those are the two rows standing on top of something, and
     * the number is what backspace would put back. An authored weight stands on nothing and a derived one
     * *is* the rule, so for both the column is empty rather than restating the weight beside it.
     */
    private fun memberRow(member: TagLayer.Member) = Table.Row(
        key = keyOf(member),
        cells = listOf(
            member.aspect.page,
            member.preset,
            if (member.source == TagLayer.Source.DROPPED) "—" else "%.2f".format(member.weight),
            member.source.title,
            member.under?.let { "original value %.2f".format(it) }.orEmpty(),
        ),
        tone = when (member.source) {
            TagLayer.Source.DROPPED -> Palette.warned
            TagLayer.Source.AUTHORED, TagLayer.Source.OVERRIDDEN -> Palette.value
            TagLayer.Source.REMEMBERED -> Palette.nudged
            TagLayer.Source.DERIVED -> null
        },
    )

    private fun walkTheList(table: Table): String? {
        terminal.enterRawMode(MouseTracking.Off).use { scope ->
            while (true) {
                canvas.show(
                    tableLines(
                        table,
                        canvas,
                        listOf(
                            hints(
                                "↑↓" to "move",
                                "←→" to "column",
                                "tab" to "sort",
                                "pgup/pgdn" to "a page",
                            ),
                            hints(
                                "enter" to if (table.focused?.key == MAKE) "name a new set" else "view details",
                                "←" to "back",
                                searching(table.filter),
                            ),
                        ),
                    ),
                )
                val key = scope.readKey() ?: return null
                if (key.ctrl && key.key == "c") throw Leaving()
                when {
                    key.ctrl && (key.key == "q" || key.key == "c") -> return null
                    key.key == "Escape" -> if (table.isFiltered) table.clearFilter() else return null
                    key.key == "ArrowLeft" -> if (table.column == 0) return null else table.across(-1)
                    key.key == "ArrowRight" -> table.across(1)
                    key.key == "ArrowUp" -> table.move(-1)
                    key.key == "ArrowDown" -> table.move(1)
                    key.key == "Home" -> table.home()
                    key.key == "End" -> table.end()
                    key.key == "PageUp" -> table.page(-1)
                    key.key == "PageDown" -> table.page(1)
                    key.key == "Tab" -> table.sortByTheColumnInHand()
                    key.key == "Backspace" -> table.backspace()
                    // A search that found nothing is a name already typed, so enter takes it as one.
                    key.key == "Enter" -> return when {
                        table.shown.isEmpty() && couldBeATag(table.filter) -> MAKE
                        else -> table.focused?.key ?: continue
                    }
                    key.key.length == 1 && !key.ctrl && !key.alt -> table.type(key.key)
                }
            }
        }
    }

    private fun memberLines(table: Table, tag: String, grouped: Boolean): List<Line> {
        val asked = layer.askedBy(tag)
        val adding = table.focused?.key == ADD
        // Offered only where there is something to go back to, which is a row standing on a rule.
        val resettable = memberFor(table.focused, tag)?.under
        return tableLines(
            table,
            canvas,
            listOf(
                Line(
                    "  " + if (asked.isEmpty()) "no word asks for it" else "asked for by ${asked.joinToString(" ")}",
                    if (asked.isEmpty()) Palette.warned else Palette.faint,
                ),
                if (adding) {
                    hints("enter" to "add a member")
                } else {
                    hints(
                        "- =" to "step the weight",
                        if (resettable == null) "" to "" else "^o" to "back to %.2f".format(resettable),
                    )
                },
                hints(
                    "tab" to if (grouped) "sort by name" else "group by source",
                    "^d" to "drop or restore",
                    "^r" to "rename the set",
                    "^w" to "what asks",
                    "^x" to "delete the set",
                    "←" to "back",
                    searching(table.filter),
                ),
            ),
        )
    }

    /** One line, typed, with a live complaint — the editor's prompt, in a screen that has no editor. */
    private fun ask(title: String, hint: String, complaint: (String) -> String?): String? {
        var typed = ""
        terminal.enterRawMode(MouseTracking.Off).use { scope ->
            while (true) {
                val says = complaint(typed)
                canvas.show(
                    listOf(
                        Line("  $title", Palette.heading),
                        Line("  $hint", Palette.faint),
                        Line.BLANK,
                        Line("  ${Glyph.FOCUS} ", Palette.focused) + Line(typed, Palette.value) + Line("_", Palette.faint),
                        Line.BLANK,
                        Line("  ${says.orEmpty()}", Palette.refused),
                        Frame.rule(canvas.width),
                        hints("enter" to "do it", "escape" to "leave it alone"),
                    ),
                )
                val key = scope.readKey() ?: return null
                if (key.ctrl && key.key == "c") throw Leaving()
                when {
                    key.ctrl && (key.key == "q" || key.key == "c") -> return null
                    key.key == "Escape" -> return null
                    key.key == "Enter" -> if (says == null) return typed
                    key.key == "Backspace" -> typed = typed.dropLast(1)
                    key.key.length == 1 && !key.ctrl && !key.alt -> typed += key.key
                }
            }
        }
    }

    private fun show(title: String, lines: List<Line>) {
        terminal.enterRawMode(MouseTracking.Off).use { scope ->
            while (true) {
                canvas.show(
                    listOf(Line("  $title", Palette.heading), Line.BLANK) +
                        lines.flatMap { (Line("  ") + it).wrapped(canvas.width) } +
                        listOf(Frame.rule(canvas.width), hints("←" to "back")),
                )
                val key = scope.readKey() ?: return
                if (key.ctrl && key.key == "c") throw Leaving()
                if (key.key == "Escape" || key.key == "ArrowLeft" || key.key == "Enter" ||
                    (key.ctrl && (key.key == "q" || key.key == "c"))
                ) {
                    return
                }
            }
        }
    }

    private companion object {
        const val TAG_WIDTH = 18
        const val COUNT_WIDTH = 10
        const val WHERE_WIDTH = 30
        const val NOTE_WIDTH = 34
        const val ASPECT_WIDTH = 12
        const val CARRIER_WIDTH = 40
        const val WEIGHT_WIDTH = 8
        const val SOURCE_WIDTH = 10
        const val WEIGHT = "weight"

        /** What one press moves a weight — the corpus is written in tenths and reads as a scale of ten. */
        /** The row that adds one, told from a member's `aspect/preset` key by having no slash in it. */
        const val ADD = "+"

        /** The row that makes a tag, and what a search finding nothing comes back as. */
        const val MAKE = "+tag"

        /** What a new entry lands at: an exception is written because something is very one thing. */
        const val WHOLLY = 1.0

        const val STEP = 0.1


        val LEGAL_TAG = Regex("[a-z0-9_]+")
    }
}
