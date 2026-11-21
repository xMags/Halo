package moe.ditto.halo.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.api.HaloApiException
import moe.ditto.halo.api.MetaDetail
import moe.ditto.halo.browse.SearchInput
import moe.ditto.halo.browse.SearchWebLinkTitleMaxLength
import moe.ditto.halo.browse.classifySearchInput
import moe.ditto.halo.browse.hasNoPoster
import moe.ditto.halo.browse.ratedValue
import moe.ditto.halo.cache.QueryState
import moe.ditto.halo.magnets.MagnetCardAction
import moe.ditto.halo.magnets.MagnetCardTone
import moe.ditto.halo.magnets.MagnetPhase
import moe.ditto.halo.magnets.MagnetRequest
import moe.ditto.halo.magnets.MagnetState
import moe.ditto.halo.magnets.buildMagnetCard
import moe.ditto.halo.people.CapabilitiesState
import moe.ditto.halo.people.IdLookup
import moe.ditto.halo.shell.PersonRoute
import moe.ditto.halo.shell.PlayerRoute
import moe.ditto.halo.shell.linkPlayerRoute
import moe.ditto.halo.ui.formatBytes

/*
 * The action card a pasted link or id takes the place of the top match with:
 * what it is and, when there is one, the single thing it does (the keyboard's
 * Search key, a tap on the card, or its button). A bare IMDb id is asked of
 * the addons as a film and as a series at once; TVDB numbers and IMDb people
 * are looked up on the server (or with the viewer's TMDB key); a web link
 * plays as it is, away from history; a magnet goes to the server's debrid
 * account. The desktop does the same in `search/useLinkAction.ts`.
 */

internal enum class ActionTone { Accent, Warn, Bad, Mute }
internal enum class ActionGlyph { Id, Link, Magnet, Poster }

internal data class ActionButton(
    val label: String,
    val playGlyph: Boolean = false,
    /** Cancel: a destructive control is red. */
    val destructive: Boolean = false,
    val run: () -> Unit,
)

internal data class ActionCardModel(
    val tone: ActionTone,
    val glyph: ActionGlyph,
    val kicker: String,
    val title: String,
    val rating: String = "",
    val mono: String = "",
    val year: String = "",
    val tail: String = "",
    val secondary: String = "",
    /** The poster (or a found person's photo) when the glyph is [ActionGlyph.Poster]. */
    val poster: String? = null,
    /** The IMDb id a poster falls back on (metahub draws those). */
    val metaId: String = "",
    /** The person glyph stands in for a photo that has not loaded. */
    val person: Boolean = false,
    /** 0 to 1 while a magnet is being fetched. */
    val progress: Double? = null,
    /** The accent action, which the keyboard's Search key runs too. */
    val primary: ActionButton? = null,
    /** A magnet card's standard button, by tap only. */
    val plain: ActionButton? = null,
)

private object LinkCopy {
    const val LookingUp = "Looking up "
    const val LookingUpSecondary = "Asking your addons which title this is."
    const val NoAddonKnows = "No addon knows this title"
    const val NotFoundSuffix = " was not found by any of your addons."
    const val CannotLookUp = "This link can't be looked up"
    const val TvdbSlug = "This TVDB link has no number in it. Paste the IMDb link instead."
    const val NeedsServer = "This kind of link needs a Halo server that supports it."
    const val AskingTitle = "Asking the server which title this is."
    const val AskingPerson = "Asking the server who this is."
    const val UnknownTitle = "The server doesn't know this title"
    const val UnknownPerson = "The server doesn't know this person"
    const val ServerNotFoundSuffix = " was not found."
    const val ServerNoAnswer = "The server did not answer. Try again in a moment."
    const val OpenPerson = "Open person page"
    const val WebLinkSecondary = "Plays straight from this address. Not added to your history."
    const val CannotPlay = "This link can't be played"
    const val OpenTitle = "Open title"
    const val PlayLink = "Play this link"
}

private fun kickerOf(input: SearchInput): String = when (input) {
    is SearchInput.Text -> ""
    is SearchInput.ImdbTitle, is SearchInput.ImdbPerson -> "IMDB LINK"
    is SearchInput.TvdbNumeric, is SearchInput.TvdbSlug -> "TVDB LINK"
    is SearchInput.Magnet -> "MAGNET LINK"
    is SearchInput.WebLink -> "VIDEO LINK"
    is SearchInput.Unsupported -> "LINK"
}

/** The title an IMDb id names: a series with episodes beats the film; otherwise the film wins. */
private fun chooseTitle(movie: MetaDetail?, series: MetaDetail?): MetaDetail? = when {
    series != null && series.type == "series" && series.videos.isNotEmpty() -> series
    movie != null && movie.type == "movie" -> movie
    series != null && series.type == "series" -> series
    else -> null
}

private sealed interface LookupState {
    data object Looking : LookupState
    data class Found(val answer: IdLookup) : LookupState
    data object NotFound : LookupState
    data object Failed : LookupState
}

/** A size for the magnet card, "Unknown size" when the server gave none. */
private fun magnetSize(bytes: Long?): String = bytes?.let(::formatBytes)?.ifEmpty { null } ?: "Unknown size"

/** Where the card's actions lead. */
internal class LinkActionNavigation(
    val openDetail: (MetaRef) -> Unit,
    val openPerson: (PersonRoute) -> Unit,
    val play: (PlayerRoute) -> Unit,
)

/** The card for [input], or null for words. A link is never recorded as a search term. */
@Composable
internal fun rememberLinkAction(
    graph: SessionGraph,
    input: SearchInput,
    capabilities: CapabilitiesState,
    navigation: LinkActionNavigation,
): ActionCardModel? {
    val imdbId = (input as? SearchInput.ImdbTitle)?.imdbId.orEmpty()
    val movieState by remember(graph, imdbId) {
        if (imdbId.isEmpty()) flowOf(QueryState()) else graph.browse.meta("movie", imdbId)
    }.collectAsState(QueryState())
    val seriesState by remember(graph, imdbId) {
        if (imdbId.isEmpty()) flowOf(QueryState()) else graph.browse.meta("series", imdbId)
    }.collectAsState(QueryState())

    val server: Pair<String, String>? = when (input) {
        is SearchInput.TvdbNumeric -> "tvdb" to input.tvdbId
        is SearchInput.ImdbPerson -> "imdb" to input.personId
        else -> null
    }
    var lookup by remember(server) { mutableStateOf<LookupState>(LookupState.Looking) }
    val lookupAllowed = capabilities.settled && capabilities.capabilities.lookup
    LaunchedEffect(graph, server, lookupAllowed) {
        val (source, id) = server ?: return@LaunchedEffect
        if (!lookupAllowed) return@LaunchedEffect
        lookup = LookupState.Looking
        lookup = try {
            LookupState.Found(graph.backend.lookup(source, id))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            if (failure is HaloApiException && failure.status == 404) LookupState.NotFound else LookupState.Failed
        }
    }

    val magnet = rememberMagnetTracking(graph, input as? SearchInput.Magnet, capabilities)

    val playLink = { url: String, title: String, filename: String?, videoSize: Long? ->
        // Read again rather than trusted from the card: only a web link the
        // classifier accepts, exactly as given, reaches playback.
        val again = classifySearchInput(url)
        if (again is SearchInput.WebLink && again.url == url) {
            navigation.play(linkPlayerRoute(url, title.take(SearchWebLinkTitleMaxLength), filename, videoSize))
        }
    }

    val kicker = kickerOf(input)
    return when (input) {
        is SearchInput.Text -> null
        is SearchInput.ImdbTitle -> {
            val settledAll = movieState.value != null || movieState.error != null
            val settledSeries = seriesState.value != null || seriesState.error != null
            val found = chooseTitle(movieState.value, seriesState.value)
            when {
                found != null -> ActionCardModel(
                    tone = ActionTone.Accent,
                    glyph = ActionGlyph.Poster,
                    kicker = kicker,
                    title = found.name,
                    rating = ratedValue(found.imdbRating)?.let { "★ $it" }.orEmpty(),
                    mono = if (found.type == "series") "SERIES" else "MOVIE",
                    year = found.releaseInfo.orEmpty(),
                    poster = found.poster?.takeUnless { hasNoPoster(found) },
                    metaId = found.id,
                    primary = ActionButton(LinkCopy.OpenTitle) { navigation.openDetail(MetaRef(found.type, found.id)) },
                )
                settledAll && settledSeries -> ActionCardModel(
                    tone = ActionTone.Mute,
                    glyph = ActionGlyph.Id,
                    kicker = kicker,
                    title = LinkCopy.NoAddonKnows,
                    secondary = "${input.imdbId}${LinkCopy.NotFoundSuffix}",
                )
                else -> ActionCardModel(
                    tone = ActionTone.Accent,
                    glyph = ActionGlyph.Id,
                    kicker = kicker,
                    title = "${LinkCopy.LookingUp}${input.imdbId}…",
                    secondary = LinkCopy.LookingUpSecondary,
                )
            }
        }
        is SearchInput.TvdbNumeric, is SearchInput.ImdbPerson -> {
            val isTvdb = input is SearchInput.TvdbNumeric
            val id = server?.second.orEmpty()
            val state: LookupState? = when {
                !capabilities.settled -> LookupState.Looking
                !capabilities.capabilities.lookup -> null
                else -> lookup
            }
            val answer = (state as? LookupState.Found)?.answer
            when {
                answer is IdLookup.Title && isTvdb -> ActionCardModel(
                    tone = ActionTone.Accent,
                    glyph = ActionGlyph.Poster,
                    kicker = kicker,
                    title = answer.name,
                    mono = if (answer.type == "series") "SERIES" else "MOVIE",
                    year = answer.year.orEmpty(),
                    poster = answer.poster,
                    metaId = answer.id,
                    primary = ActionButton(LinkCopy.OpenTitle) { navigation.openDetail(MetaRef(answer.type, answer.id)) },
                )
                answer is IdLookup.Person && !isTvdb -> ActionCardModel(
                    tone = ActionTone.Accent,
                    glyph = ActionGlyph.Poster,
                    kicker = kicker,
                    title = answer.name,
                    poster = answer.photo,
                    person = true,
                    primary = if (capabilities.capabilities.people) {
                        ActionButton(LinkCopy.OpenPerson) { navigation.openPerson(PersonRoute(answer.tmdbId, answer.name, answer.photo)) }
                    } else {
                        null
                    },
                )
                else -> {
                    // An answer of the other kind is as good as none.
                    val shown = if (state is LookupState.Found) LookupState.NotFound else state
                    ActionCardModel(
                        tone = when (shown) {
                            LookupState.Looking -> ActionTone.Accent
                            LookupState.Failed -> ActionTone.Warn
                            else -> ActionTone.Mute
                        },
                        glyph = ActionGlyph.Id,
                        kicker = kicker,
                        title = when (shown) {
                            LookupState.NotFound -> if (isTvdb) LinkCopy.UnknownTitle else LinkCopy.UnknownPerson
                            null, LookupState.Failed -> LinkCopy.CannotLookUp
                            else -> "${LinkCopy.LookingUp}$id…"
                        },
                        secondary = when (shown) {
                            null -> LinkCopy.NeedsServer
                            LookupState.Failed -> LinkCopy.ServerNoAnswer
                            LookupState.NotFound -> "$id${LinkCopy.ServerNotFoundSuffix}"
                            else -> if (isTvdb) LinkCopy.AskingTitle else LinkCopy.AskingPerson
                        },
                    )
                }
            }
        }
        is SearchInput.TvdbSlug -> ActionCardModel(ActionTone.Mute, ActionGlyph.Id, kicker, LinkCopy.CannotLookUp, secondary = LinkCopy.TvdbSlug)
        is SearchInput.Unsupported -> ActionCardModel(ActionTone.Mute, ActionGlyph.Link, kicker, LinkCopy.CannotPlay, secondary = input.reason)
        is SearchInput.WebLink -> {
            val title = input.displayName.ifEmpty { input.host }
            ActionCardModel(
                tone = ActionTone.Accent,
                glyph = ActionGlyph.Link,
                kicker = kicker,
                title = title,
                mono = input.host,
                secondary = LinkCopy.WebLinkSecondary,
                primary = ActionButton(LinkCopy.PlayLink, playGlyph = true) { playLink(input.url, title, null, null) },
            )
        }
        is SearchInput.Magnet -> {
            val built = buildMagnetCard(magnet.phase, input.displayName, magnet.request, magnet.cachedOnly, ::magnetSize)
            val identity = built.identity
            val file = built.unidentifiedFile
            val plainRun: (() -> Unit)? = when {
                built.action == MagnetCardAction.Cancel -> magnet.cancel
                built.action == MagnetCardAction.Retry -> magnet.retry
                // The server's own play link: opened bare, as any pasted link is.
                built.action == MagnetCardAction.PlayUnidentified && file != null -> {
                    { navigation.play(linkPlayerRoute(file.playUrl, file.name.take(SearchWebLinkTitleMaxLength), file.name, file.sizeBytes)) }
                }
                else -> null
            }
            ActionCardModel(
                tone = when (built.tone) {
                    MagnetCardTone.Accent -> ActionTone.Accent
                    MagnetCardTone.Warn -> ActionTone.Warn
                    MagnetCardTone.Bad -> ActionTone.Bad
                    MagnetCardTone.Mute -> ActionTone.Mute
                },
                glyph = if (built.showsPoster && identity != null) ActionGlyph.Poster else ActionGlyph.Magnet,
                kicker = kicker,
                title = built.title,
                mono = built.mono,
                year = built.plain,
                tail = built.tail,
                secondary = built.secondary,
                poster = identity?.poster,
                metaId = identity?.id.orEmpty(),
                progress = built.progress,
                primary = if (built.action == MagnetCardAction.OpenTitle && identity != null) {
                    ActionButton(built.actionLabel) { navigation.openDetail(MetaRef(identity.type, identity.id)) }
                } else {
                    null
                },
                plain = plainRun?.let {
                    ActionButton(
                        label = built.actionLabel,
                        playGlyph = built.action == MagnetCardAction.PlayUnidentified,
                        destructive = built.action == MagnetCardAction.Cancel,
                        run = it,
                    )
                },
            )
        }
    }
}

private const val MagnetPollMs = 3_000L
private const val MagnetPollLimitMs = 10 * 60_000L

internal class MagnetTracking(
    val phase: MagnetPhase,
    val request: MagnetRequest?,
    val cachedOnly: Boolean,
    val cancel: () -> Unit,
    val retry: () -> Unit,
)

/**
 * A pasted magnet, asked about on the account's server. Only its info hash
 * and display name leave the phone, never the link or its trackers. While the
 * server is still fetching it, it is asked again every 3 seconds, but only
 * while its card is on screen (leaving Search stops the asking) and for at
 * most 10 minutes from the paste; pasting it again asks anew. Cancel forgets
 * the request on the server. The desktop does the same in `search/useMagnet.ts`.
 */
@Composable
private fun rememberMagnetTracking(graph: SessionGraph, magnet: SearchInput.Magnet?, capabilities: CapabilitiesState): MagnetTracking {
    var phase by remember(magnet) { mutableStateOf(MagnetPhase.Checking) }
    var request by remember(magnet) { mutableStateOf<MagnetRequest?>(null) }
    var attempt by remember(magnet) { mutableIntStateOf(0) }
    var asking by remember(magnet) { mutableStateOf<Job?>(null) }
    val allowed = capabilities.capabilities.magnets

    LaunchedEffect(graph, magnet, capabilities.settled, allowed, attempt) {
        magnet ?: return@LaunchedEffect
        request = null
        // Until the server says what it offers, the card reads as checking.
        if (!capabilities.settled) {
            phase = MagnetPhase.Checking
            return@LaunchedEffect
        }
        // Without an account the capabilities read as none: no magnet call is made.
        val client = graph.accountClient
        if (client == null || !allowed) {
            phase = MagnetPhase.Unavailable
            return@LaunchedEffect
        }
        graph.readyMagnet.forgetOtherThan(magnet.infoHash)
        phase = MagnetPhase.Checking
        asking = coroutineContext[Job]
        val trackedAt = graph.nowMs()
        var next = try {
            client.addMagnet(magnet.infoHash, magnet.displayName.ifEmpty { null })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // The server's 404 on adding one: magnets are not available to this account.
            phase = if (failure is HaloApiException && failure.status == 404) MagnetPhase.Unavailable else MagnetPhase.Failed
            return@LaunchedEffect
        }
        while (true) {
            request = next
            phase = MagnetPhase.Answered
            graph.readyMagnet.remember(magnet.infoHash, next)
            // Long enough: the card keeps its last answer.
            if (next.state != MagnetState.Downloading || graph.nowMs() - trackedAt >= MagnetPollLimitMs) return@LaunchedEffect
            delay(MagnetPollMs)
            next = try {
                client.getMagnet(next.id)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                request = null
                phase = MagnetPhase.Failed
                return@LaunchedEffect
            }
        }
    }

    return MagnetTracking(
        phase = phase,
        request = request,
        cachedOnly = capabilities.capabilities.magnetsCachedOnly,
        cancel = {
            val current = request
            // Nothing more is asked about it; the server forgets the request and its torrent.
            asking?.cancel()
            request = null
            phase = MagnetPhase.Stopped
            if (current != null) graph.forgetMagnetRequest(current.id)
        },
        retry = { attempt += 1 },
    )
}
