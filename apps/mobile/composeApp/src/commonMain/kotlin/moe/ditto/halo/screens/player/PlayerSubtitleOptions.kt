package moe.ditto.halo.screens.player

import moe.ditto.halo.PlaybackHost
import moe.ditto.halo.api.AddonSubtitles
import moe.ditto.halo.player.PlayerTrack
import moe.ditto.halo.player.PlayerTracks
import moe.ditto.halo.player.SubtitleFileException
import moe.ditto.halo.storage.SubtitleChoice
import moe.ditto.halo.storage.SubtitleChoiceKind
import moe.ditto.halo.ui.languageLabel
import moe.ditto.halo.ui.languageMatches
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One subtitle an addon offered, in the shape the rail draws and the memory
 * records.
 *
 * [id] is scoped by addon because subtitle ids are only unique within the addon
 * that minted them, and two addons answering the same video with "1" is
 * ordinary. [subId] keeps the addon's own id, which is what a remembered choice
 * is re-found by.
 */
internal data class AddonSubtitleOption(
    val id: String,
    val addonId: String,
    val addonName: String,
    val lang: String,
    val subId: String,
    val url: String,
) {
    /** "English · os-en-6821194": what distinguishes two same-language results. */
    val detail: String get() = "${languageLabel(lang)} · $subId"

    /** Null when the URL does not name a format, which is not worth guessing at. */
    val format: String? get() = subtitleFileFormat(url)
}

/**
 * Formats are read off the URL's extension rather than fetched, because the
 * badge exists before anything is downloaded. Query strings are stripped first:
 * a signed URL ends in a signature, not a file name.
 */
internal fun subtitleFileFormat(url: String): String? {
    val path = url.substringBefore('?').substringBefore('#')
    val extension = path.substringAfterLast('/', "").substringAfterLast('.', "")
    return when (extension.lowercase()) {
        "srt" -> "SRT"
        "vtt", "webvtt" -> "VTT"
        "ass", "ssa" -> "ASS"
        "sub" -> "SUB"
        else -> null
    }
}

/** Addon results that are all in one language, under the name of that language. */
internal data class SubtitleLanguageGroup(
    val language: String,
    val options: List<AddonSubtitleOption>,
)

/**
 * Addon results folded into one row per language.
 *
 * A popular film comes back with twenty-odd results, nearly all of them the
 * same language and told apart only by an id, and a flat list of those is a
 * scroll with nothing to aim at. Folded, the choice a viewer is actually making
 * comes first — which language — and the rail is a handful of rows again.
 *
 * Grouped by the displayed name rather than the code the addon sent, so the
 * codes the label map treats as one language ("chi" and "zho", "ger" and "deu")
 * land in one group: two headers both reading Chinese is a list a viewer has to
 * open twice to learn they are the same thing. A code the map does not know is
 * its own group under the code itself, which is honest — nothing here can tell
 * whether an unknown code is a language it already has.
 *
 * Ordered the way someone hunting for a subtitle scans: the language they said
 * they prefer, then whichever has the most on offer, then alphabetically so the
 * order cannot depend on the order addons happened to answer in. Results keep
 * their original order inside a group, which is the addon's own ranking.
 */
internal fun subtitleLanguageGroups(
    options: List<AddonSubtitleOption>,
    preferredLang: String?,
): List<SubtitleLanguageGroup> {
    if (options.isEmpty()) return emptyList()
    val preferred = preferredLang?.takeIf { it.isNotBlank() }?.let(::languageLabel)
    return options
        .groupBy { languageLabel(it.lang) }
        .map { (language, group) -> SubtitleLanguageGroup(language, group) }
        .sortedWith(
            compareBy(
                { it.language != preferred },
                { -it.options.size },
                { it.language },
            ),
        )
}

/**
 * Which groups a freshly opened rail shows the contents of: the one holding the
 * current choice, so a viewer can see what is playing without hunting for it,
 * and otherwise the first, which is their language or the richest list.
 *
 * Everything else stays folded. That is the point of folding: a rail that opens
 * with every group expanded is the flat list again.
 */
internal fun defaultExpandedSubtitleLanguages(
    groups: List<SubtitleLanguageGroup>,
    selectedAddonId: String?,
): Set<String> {
    if (groups.isEmpty()) return emptySet()
    val holdingSelection = groups.firstOrNull { group ->
        group.options.any { it.id == selectedAddonId }
    }
    return setOf((holdingSelection ?: groups.first()).language)
}

internal fun addonSubtitleOptions(results: List<AddonSubtitles>): List<AddonSubtitleOption> =
    results.flatMap { group ->
        group.subtitles.map { subtitle ->
            AddonSubtitleOption(
                id = "${group.addon.id}:${subtitle.id}",
                addonId = group.addon.id,
                addonName = group.addon.name,
                lang = subtitle.lang,
                subId = subtitle.id,
                url = subtitle.url,
            )
        }
    }

/**
 * What to show when playback starts.
 *
 * [Unchanged] is a real outcome and not a failure: with nothing remembered and
 * no preferred language, the engine's own automatic selection is a better
 * answer than anything this could impose.
 */
internal sealed interface SubtitleSelection {
    data object Unchanged : SubtitleSelection
    data object Off : SubtitleSelection
    data class Embedded(val trackId: String) : SubtitleSelection
    data class External(val option: AddonSubtitleOption) : SubtitleSelection
}

/**
 * Restores what the viewer last chose for this video, falling back to language
 * when the exact track is gone.
 *
 * The fallback is the whole point of remembering a language alongside an id: a
 * different release of the next episode has different track ids and different
 * addon results, and "the English one" is what was actually meant.
 *
 * [preferredLang] applies only when nothing was remembered. It is a standing
 * preference, not a decision about this video, which is why choosing it here
 * must never be written back as one.
 */
internal fun resolveSubtitleSelection(
    remembered: SubtitleChoice?,
    tracks: PlayerTracks,
    addonSubtitles: List<AddonSubtitleOption>,
    preferredLang: String?,
): SubtitleSelection {
    if (remembered == null) return preferredSelection(tracks, addonSubtitles, preferredLang)

    return when (remembered.kind) {
        SubtitleChoiceKind.Off -> SubtitleSelection.Off

        SubtitleChoiceKind.Embedded -> {
            val exact = tracks.subtitles.firstOrNull { it.label == remembered.trackName }
            val byLanguage = tracks.subtitles.firstOrNull { it.matches(remembered.lang) }
            (exact ?: byLanguage)?.let { SubtitleSelection.Embedded(it.id) }
                // The remembered track is not in this file. Its language may
                // still be, from an addon.
                ?: addonSubtitles.firstOrNull { it.matches(remembered.lang) }
                    ?.let { SubtitleSelection.External(it) }
                ?: SubtitleSelection.Unchanged
        }

        // A downloaded subtitle is restored the same way as an external one
        // here: this screen has no on-disk copies, so the addon result for the
        // same language is the closest true answer.
        SubtitleChoiceKind.External,
        SubtitleChoiceKind.Downloaded,
        -> {
            val exact = addonSubtitles.firstOrNull { it.subId == remembered.subId }
            val byLanguage = addonSubtitles.firstOrNull { it.matches(remembered.lang) }
            (exact ?: byLanguage)?.let { SubtitleSelection.External(it) }
                ?: tracks.subtitles.firstOrNull { it.matches(remembered.lang) }
                    ?.let { SubtitleSelection.Embedded(it.id) }
                ?: SubtitleSelection.Unchanged
        }
    }
}

/**
 * In-file tracks win over addon results at equal language: they are already
 * present, already timed to this file, and cost no request.
 */
private fun preferredSelection(
    tracks: PlayerTracks,
    addonSubtitles: List<AddonSubtitleOption>,
    preferredLang: String?,
): SubtitleSelection {
    val lang = preferredLang?.takeIf { it.isNotBlank() } ?: return SubtitleSelection.Unchanged
    tracks.subtitles.firstOrNull { it.matches(lang) }?.let { return SubtitleSelection.Embedded(it.id) }
    addonSubtitles.firstOrNull { it.matches(lang) }?.let { return SubtitleSelection.External(it) }
    return SubtitleSelection.Unchanged
}

/** What the viewer just picked, in the form the store keeps it. */
internal fun embeddedChoice(track: PlayerTrack): SubtitleChoice = SubtitleChoice(
    kind = SubtitleChoiceKind.Embedded,
    lang = track.language,
    trackName = track.label,
)

internal fun externalChoice(option: AddonSubtitleOption): SubtitleChoice = SubtitleChoice(
    kind = SubtitleChoiceKind.External,
    lang = option.lang,
    subId = option.subId,
)

internal val OffChoice = SubtitleChoice(kind = SubtitleChoiceKind.Off)

/**
 * Language codes arrive from addons and from track metadata, and the two do not
 * agree on spelling: ISO 639-2 has both a bibliographic and a terminological
 * code for several languages, and some addons send the two-letter form.
 */
private fun PlayerTrack.matches(lang: String?): Boolean =
    languageMatches(language, lang)

private fun AddonSubtitleOption.matches(other: String?): Boolean = languageMatches(lang, other)

/**
 * Puts a resolved selection into effect.
 *
 * The shipping screen resolves external URLs through [ExternalSubtitleSelectionCoordinator]
 * first. This helper therefore receives external selections only when their URL
 * is already safe for the engine, such as a local file in a test or harness.
 */
internal suspend fun applySubtitleSelection(
    selection: SubtitleSelection,
    playback: PlaybackHost,
    onAddonSelected: (String?) -> Unit,
) {
    when (selection) {
        SubtitleSelection.Unchanged -> Unit
        SubtitleSelection.Off -> {
            onAddonSelected(null)
            playback.selectSubtitleTrack(null)
        }
        is SubtitleSelection.Embedded -> {
            onAddonSelected(null)
            playback.selectSubtitleTrack(selection.trackId)
        }
        is SubtitleSelection.External -> {
            playback.addSubtitle(selection.option.url)
            onAddonSelected(selection.option.id)
        }
    }
}

/**
 * A monotonically increasing claim for external subtitle loads.
 *
 * Downloads can finish out of order. Only the newest attempt may reach libmpv
 * or update selection memory, even if an older HTTP client ignores cancellation.
 */
internal class ExternalSubtitleSelectionCoordinator {
    private var latestAttempt = 0L
    private val commitMutex = Mutex()

    fun begin(): Long {
        latestAttempt += 1L
        return latestAttempt
    }

    fun supersede() {
        latestAttempt += 1L
    }

    fun currentAttempt(): Long = latestAttempt

    suspend fun load(
        attempt: Long,
        resolve: suspend () -> String,
        addToPlayer: suspend (String) -> Unit,
        onLoaded: () -> Unit = {},
    ): Boolean {
        val localFile = resolve()
        return commitMutex.withLock {
            if (attempt != latestAttempt) return@withLock false
            addToPlayer(localFile)
            if (attempt != latestAttempt) return@withLock false
            onLoaded()
            true
        }
    }
}

internal fun subtitleLoadErrorMessage(failure: Throwable): String =
    (failure as? SubtitleFileException)?.message ?: "This subtitle could not be loaded."

internal fun subtitleCacheIdentity(context: PlaybackContext, option: AddonSubtitleOption): String =
    listOf(context.itemId, context.videoId, option.addonId, option.subId).joinToString("|")
