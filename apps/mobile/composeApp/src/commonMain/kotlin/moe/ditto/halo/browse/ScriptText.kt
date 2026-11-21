package moe.ditto.halo.browse

/*
 * JavaScript's idea of white space, for the Search rules ported from
 * `@halo/core`. Kotlin's `\s` is ASCII only, and its `trim()` keeps U+FEFF
 * (a byte order mark a pasted link can carry) while removing U+001C to
 * U+001F, so "dune 2021" typed with a no-break space would lose its year and
 * a magnet pasted after a byte order mark would read as words. These follow
 * the ECMAScript definition, WhiteSpace plus LineTerminator, which is both
 * what `\s` matches and what `String.prototype.trim` removes.
 */

/** Every character JavaScript's `\s` matches. */
private const val ScriptSpaceChars =
    "\t\n\u000B\u000C\r \u00A0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200A" +
        "\u2028\u2029\u202F\u205F\u3000\uFEFF"

/** JavaScript's `\s` as a regex character class, built from the characters themselves so every engine reads it alike. */
internal const val ScriptSpaceClass = "[$ScriptSpaceChars]"

/** JavaScript's `\S`. */
internal const val ScriptNonSpaceClass = "[^$ScriptSpaceChars]"

internal fun Char.isScriptSpace(): Boolean = this in ScriptSpaceChars

/** What JavaScript's `trim()` leaves. */
internal fun String.scriptTrim(): String = trim { it.isScriptSpace() }

/** What JavaScript's `trimEnd()` leaves. */
internal fun String.scriptTrimEnd(): String = trimEnd { it.isScriptSpace() }
