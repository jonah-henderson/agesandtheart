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
}
