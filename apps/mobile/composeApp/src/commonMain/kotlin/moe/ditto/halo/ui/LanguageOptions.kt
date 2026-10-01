package moe.ditto.halo.ui

data class LanguageOption(val code: String, val label: String)

private val LanguageLabels = mapOf(
    "ara" to "Arabic",
    "bul" to "Bulgarian",
    "chi" to "Chinese",
    "zho" to "Chinese",
    "cze" to "Czech",
    "ces" to "Czech",
    "dan" to "Danish",
    "dut" to "Dutch",
    "nld" to "Dutch",
    "eng" to "English",
    "est" to "Estonian",
    "fin" to "Finnish",
    "fre" to "French",
    "fra" to "French",
    "ger" to "German",
    "deu" to "German",
    "gre" to "Greek",
    "ell" to "Greek",
    "heb" to "Hebrew",
    "hin" to "Hindi",
    "hrv" to "Croatian",
    "hun" to "Hungarian",
    "ind" to "Indonesian",
    "ita" to "Italian",
    "jpn" to "Japanese",
    "kor" to "Korean",
    "lav" to "Latvian",
    "lit" to "Lithuanian",
    "may" to "Malay",
    "msa" to "Malay",
    "nor" to "Norwegian",
    "per" to "Persian",
    "fas" to "Persian",
    "pol" to "Polish",
    "por" to "Portuguese",
    "pob" to "Portuguese (BR)",
    "rum" to "Romanian",
    "ron" to "Romanian",
    "rus" to "Russian",
    "slo" to "Slovak",
    "slk" to "Slovak",
    "slv" to "Slovenian",
    "spa" to "Spanish",
    "srp" to "Serbian",
    "swe" to "Swedish",
    "tha" to "Thai",
    "tur" to "Turkish",
    "ukr" to "Ukrainian",
    "vie" to "Vietnamese",
)

fun languageLabel(code: String): String = LanguageLabels[code.trim().lowercase()] ?: code

/**
 * Whether two language codes name the same language.
 *
 * Codes arrive from addons and from track metadata, and the two do not agree on
 * spelling: ISO 639-2 has both a bibliographic and a terminological code for
 * several languages, and some addons send the two-letter form.
 *
 * Beside the labels above rather than in the player, because the same question
 * is asked by anything matching a preference against what a source offers,
 * including the downloads engine choosing which subtitle to keep.
 */
internal fun languageMatches(left: String?, right: String?): Boolean {
    val a = left?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
    val b = right?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
    if (a == b) return true
    return canonicalLanguage(a) == canonicalLanguage(b)
}

/**
 * Folds the spellings that mean one language onto one code. Only the pairs the
 * addons in use actually produce; an unknown code stays itself, so two unknown
 * codes still have to match exactly.
 */
private fun canonicalLanguage(code: String): String = when (code) {
    "en", "eng" -> "eng"
    "es", "spa" -> "spa"
    "pt", "por" -> "por"
    "pb", "pob" -> "pob"
    "fr", "fre", "fra" -> "fre"
    "de", "ger", "deu" -> "ger"
    "it", "ita" -> "ita"
    "nl", "dut", "nld" -> "dut"
    "pl", "pol" -> "pol"
    "ru", "rus" -> "rus"
    "ja", "jpn" -> "jpn"
    "ko", "kor" -> "kor"
    "zh", "chi", "zho" -> "chi"
    "ar", "ara" -> "ara"
    "tr", "tur" -> "tur"
    "sv", "swe" -> "swe"
    "cs", "cze", "ces" -> "cze"
    "el", "gre", "ell" -> "gre"
    "he", "heb" -> "heb"
    "fa", "per", "fas" -> "per"
    "ro", "rum", "ron" -> "rum"
    else -> code
}

val LanguageOptions = listOf(
    "eng", "spa", "por", "pob", "fre", "ger", "ita", "dut", "pol", "rus",
    "ukr", "swe", "nor", "dan", "fin", "cze", "slo", "slv", "hrv", "srp",
    "hun", "rum", "bul", "gre", "tur", "ara", "heb", "per", "hin", "tha",
    "vie", "ind", "may", "chi", "jpn", "kor", "est", "lav", "lit",
).map { code -> LanguageOption(code, LanguageLabels.getValue(code)) }
