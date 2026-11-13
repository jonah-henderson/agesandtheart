package co.voik.agesandtheart.age.words

import co.voik.agesandtheart.location
import net.minecraft.resources.Identifier

/** The concepts the reader itself knows by name, each for one rule of reading. Their files are pack data like any other. */
object CoreConcepts {
    /** A member of a part: taken only by a word of that part, and a thing taking one names it in place of its own. */
    val MEMBER: Identifier = "member".location()

    /** A material, which `no` before it turns into a ban. */
    val BLOCK: Identifier = "block".location()

    /** A block the Age never places. */
    val BAN: Identifier = "ban".location()

    /** A nudge toward members carrying tags, and the tags a member must clear: the descriptions a polarity can narrow by. */
    val LEAN: Identifier = "lean".location()
    val BAR: Identifier = "bar".location()

    /** What a negated or `only` phrase keeps of its description: what picks out member kinds, never a dial. */
    val NARROWING: Set<Identifier> = setOf(MEMBER, LEAN, BAR)
}
