package co.voik.agesandtheart.preview.authoring.words.ui

import co.voik.agesandtheart.preview.authoring.ui.Glyph
import co.voik.agesandtheart.preview.authoring.ui.Palette
import co.voik.agesandtheart.preview.authoring.words.Verdict
import com.github.ajalt.mordant.rendering.TextStyle

/** The colour a finding of this standing is drawn in, everywhere one is. */
val Verdict.Standing.style: TextStyle
    get() = when (this) {
        Verdict.Standing.REFUSED -> Palette.refused
        Verdict.Standing.WARNED -> Palette.warned
        Verdict.Standing.NUDGED -> Palette.nudged
        Verdict.Standing.NOTED -> Palette.noted
    }

/** And the mark it carries. */
val Verdict.Standing.mark: String
    get() = when (this) {
        Verdict.Standing.REFUSED -> Glyph.CROSS
        Verdict.Standing.WARNED -> Glyph.WARN
        Verdict.Standing.NUDGED, Verdict.Standing.NOTED -> Glyph.BULLET
    }
