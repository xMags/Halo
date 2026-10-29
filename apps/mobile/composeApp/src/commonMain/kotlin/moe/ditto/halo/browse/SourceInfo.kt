package moe.ditto.halo.browse

import moe.ditto.halo.api.Stream

/**
 * What a stream's free text says about the file behind it, ported rule for rule
 * from the desktop's `sources/streamInfo.ts` so both clients badge the same
 * stream the same way.
 *
 * Stremio's stream objects carry no structured technical metadata: addons put
 * it in free text (`name` is usually the addon plus a quality badge, `title`
 * the release file name plus a line of facts). The rules are deliberately
 * conservative: a token has to look unambiguous to be claimed, and anything
 * unrecognised is left out rather than guessed at. A wrong badge is worse than
 * a missing one, since the whole point of the list is deciding which file to
 * commit to.
 */
data class SourceInfo(
    /** Release file name: the line that identifies the source. */
    val filename: String,
    val quality: SourceQuality?,
    /** `HDR10`, `DV`, `HLG` or `HDR`, when the release advertises one. */
    val dynamicRange: String?,
    /** `HEVC 10-BIT`, `AV1`, `H.264`… */
    val codec: String?,
    /** `ATMOS`, `DDP 5.1`, `DTS-HD`, `AAC`… */
    val audio: String?,
    /** Bytes, from the behaviour hint when present, else parsed from the text. */
    val sizeBytes: Long?,
    /**
     * True when the addon says the file is already on the user's debrid, so
     * there is no wait. Null means the addon did not say, not "no".
     */
    val cached: Boolean?,
    /** Language tokens the release names, e.g. `ENG`, `JPN`; at most four. */
    val languages: List<String>,
)

/** Ordered best-first; the ordinal doubles as the ranking weight. */
enum class SourceQuality(val label: String) {
    P2160("2160p"),
    P1440("1440p"),
    P1080("1080p"),
    P720("720p"),
    P480("480p"),
    SD("SD"),
}

/** Shared by the fallback and the check below so they cannot disagree. */
internal const val UnnamedSource = "Unnamed source"

fun parseSourceInfo(stream: Stream): SourceInfo {
    val text = listOfNotNull(stream.name, stream.title, stream.description, stream.behaviorHints?.filename)
        .filter { it.isNotEmpty() }
        .joinToString("\n")
    val filename = stream.behaviorHints?.filename?.takeIf { it.isNotEmpty() }
        ?: firstLine(stream.title)
        ?: firstLine(stream.description)
        ?: firstLine(stream.name)
        ?: UnnamedSource
    return SourceInfo(
        filename = filename,
        quality = matchQuality(text),
        dynamicRange = matchDynamicRange(text),
        codec = matchCodec(text),
        audio = matchAudio(text),
        sizeBytes = stream.behaviorHints?.videoSize?.takeIf { it > 0 } ?: matchSize(text),
        cached = matchCached(text),
        languages = matchLanguages(text),
    )
}

/** Rank weight for a quality; unknown sorts below everything recognised. */
fun qualityRank(quality: SourceQuality?): Int = quality?.ordinal ?: SourceQuality.entries.size

/**
 * Best-first ordering: a file that plays now beats a better file that has to
 * be fetched, then resolution, then the larger file at equal resolution
 * (bitrate is the only quality signal left once the labels match).
 */
val SourceInfoOrder: Comparator<SourceInfo> = compareBy<SourceInfo> { if (it.cached == true) 0 else 1 }
    .thenBy { qualityRank(it.quality) }
    .thenByDescending { it.sizeBytes ?: 0L }

private fun firstLine(value: String?): String? =
    value?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

private fun ignoreCase(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

private val Quality2160 = ignoreCase("""\b(2160p?|4k|uhd)\b""")
private val Quality1440 = ignoreCase("""\b1440p\b""")
private val Quality1080 = ignoreCase("""\b1080p?\b|\bfullhd\b""")
private val Quality720 = ignoreCase("""\b720p?\b|\bhd\b""")
private val Quality480 = ignoreCase("""\b480p?\b""")
private val QualitySd = ignoreCase("""\b(sd|cam|ts)\b""")

/**
 * Tokens that read as a quality but are not: the DTS-HD audio codec ("HD"
 * would claim 720p) and a `.ts` file extension ending a line ("TS" would
 * claim a telesync).
 */
private val QualityLookalikes = Regex("""\bdts[-. ]?hd\b|\.ts$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

private fun matchQuality(source: String): SourceQuality? {
    val text = source.replace(QualityLookalikes, " ")
    return when {
        Quality2160.containsMatchIn(text) -> SourceQuality.P2160
        Quality1440.containsMatchIn(text) -> SourceQuality.P1440
        Quality1080.containsMatchIn(text) -> SourceQuality.P1080
        Quality720.containsMatchIn(text) -> SourceQuality.P720
        Quality480.containsMatchIn(text) -> SourceQuality.P480
        QualitySd.containsMatchIn(text) -> SourceQuality.SD
        else -> null
    }
}

private val DolbyVisionWords = ignoreCase("""\b(dolby[ .]?vision|dovi)\b""")

// Dolby Vision is claimed on a bare "DV" only in capitals: lower-case "dv"
// appears in unrelated group names often enough to be untrustworthy.
private val DolbyVisionTag = Regex("""\bDV\b""")
private val Hdr10 = ignoreCase("""\bhdr10\+?\b""")
private val Hlg = ignoreCase("""\bhlg\b""")
private val Hdr = ignoreCase("""\bhdr\b""")

private fun matchDynamicRange(text: String): String? = when {
    DolbyVisionWords.containsMatchIn(text) || DolbyVisionTag.containsMatchIn(text) -> "DV"
    Hdr10.containsMatchIn(text) -> "HDR10"
    Hlg.containsMatchIn(text) -> "HLG"
    Hdr.containsMatchIn(text) -> "HDR"
    else -> null
}

private val TenBit = ignoreCase("""\b10[ .-]?bit\b""")
private val Hevc = ignoreCase("""\b(hevc|h[ .]?265|x265)\b""")
private val Av1 = ignoreCase("""\bav1\b""")
private val Avc = ignoreCase("""\b(avc|h[ .]?264|x264)\b""")
private val Xvid = ignoreCase("""\bxvid\b""")

private fun matchCodec(text: String): String? {
    val tenBit = TenBit.containsMatchIn(text)
    return when {
        Hevc.containsMatchIn(text) -> if (tenBit) "HEVC 10-BIT" else "HEVC"
        Av1.containsMatchIn(text) -> if (tenBit) "AV1 10-BIT" else "AV1"
        Avc.containsMatchIn(text) -> "H.264"
        Xvid.containsMatchIn(text) -> "XVID"
        else -> null
    }
}

// Each codec ends at anything but a letter rather than at a word break: the
// channel count is usually glued on (`DDP5.1`, `AAC2.0`), and a digit after the
// name is no break at all. `DD+` is how many releases spell DDP.
private val Atmos = ignoreCase("""\batmos(?![a-z])""")
private val TrueHd = ignoreCase("""\btrue[ .]?hd(?![a-z])""")
private val DtsHd = ignoreCase("""\bdts[ .-]?hd(?![a-z])""")
private val Dts = ignoreCase("""\bdts(?![a-z])""")
private val Ddp = ignoreCase("""\b(ddp|dd\+|eac3|e-ac-3)(?![a-z])""")
private val Dd = ignoreCase("""\b(dd|ac3)(?![a-z])""")
private val Aac = ignoreCase("""\baac(?![a-z])""")
private val Opus = ignoreCase("""\bopus(?![a-z])""")
private val Flac = ignoreCase("""\bflac(?![a-z])""")

// No leading \b: the channel count is usually glued to the codec ("DDP5.1").
private val Channels = Regex("""([2578])[ .]([01])(?!\d)""")

private fun matchAudio(text: String): String? = when {
    Atmos.containsMatchIn(text) -> "ATMOS"
    TrueHd.containsMatchIn(text) -> "TRUEHD"
    DtsHd.containsMatchIn(text) -> "DTS-HD"
    Dts.containsMatchIn(text) -> "DTS"
    Ddp.containsMatchIn(text) -> withChannels(text, "DDP")
    Dd.containsMatchIn(text) -> withChannels(text, "DD")
    Aac.containsMatchIn(text) -> withChannels(text, "AAC")
    Opus.containsMatchIn(text) -> "OPUS"
    Flac.containsMatchIn(text) -> "FLAC"
    else -> null
}

private fun withChannels(text: String, codec: String): String {
    val match = Channels.find(text) ?: return codec
    return "$codec ${match.groupValues[1]}.${match.groupValues[2]}"
}

private val Size = ignoreCase("""(\d+(?:[.,]\d+)?)\s*(GB|GiB|MB|MiB)\b""")

private fun matchSize(text: String): Long? {
    val match = Size.find(text) ?: return null
    val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
    if (value <= 0 || value.isNaN() || value.isInfinite()) return null
    val unit = if (match.groupValues[2].startsWith("g", ignoreCase = true)) Gib else Mib
    return (value * unit).toLong()
}

private const val Mib = 1024.0 * 1024.0
private const val Gib = Mib * 1024.0

/**
 * Debrid addons flag an already-downloaded file with a `+` in their bracketed
 * provider tag (`[RD+]`), a lightning bolt, or the word "cached"/"instant".
 * The bracketed `[RD download]` form is the explicit opposite; a bare
 * "download" anywhere in the text is not, since release names contain it.
 */
private val CachedMarks = ignoreCase("""\[\w{2,3}\+\]|⚡|\b(cached|instant)\b""")
private val UncachedMark = ignoreCase("""\[\w{2,3}\s*download\]""")

private fun matchCached(text: String): Boolean? = when {
    CachedMarks.containsMatchIn(text) -> true
    UncachedMark.containsMatchIn(text) -> false
    else -> null
}

private val LanguageTokens = ignoreCase(
    """\b(ENG|ENGLISH|JPN|JAPANESE|GER|FRE|FRENCH|SPA|SPANISH|ITA|KOR|CHI|HIN|RUS|POR|DUT|NOR|SWE|DAN|FIN|POL|TUR|ARA|MULTI|DUAL)\b""",
)
private val LanguageAliases = mapOf(
    "ENGLISH" to "ENG",
    "JAPANESE" to "JPN",
    "FRENCH" to "FRE",
    "SPANISH" to "SPA",
)

private fun matchLanguages(text: String): List<String> {
    val found = LinkedHashSet<String>()
    for (match in LanguageTokens.findAll(text)) {
        val token = match.value.uppercase()
        found += LanguageAliases[token] ?: token
    }
    return found.take(4)
}
