package moe.ditto.halo.screens

import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import moe.ditto.halo.api.WatchState
import moe.ditto.halo.downloads.DownloadEntry
import moe.ditto.halo.downloads.DownloadMedia
import moe.ditto.halo.downloads.DownloadStatus
import moe.ditto.halo.downloads.DownloadSubtitle
import moe.ditto.halo.downloads.StorageSpace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadsPresentationTest {

    @Test
    fun theListSplitsIntoWhatIsArrivingAndWhatIsHere() {
        val sections = downloadSections(
            listOf(
                entry("a", status = DownloadStatus.Done),
                entry("b", status = DownloadStatus.Downloading),
                entry("c", status = DownloadStatus.Queued),
                entry("d", status = DownloadStatus.Paused),
                entry("e", status = DownloadStatus.Failed),
            ),
        )

        assertEquals(listOf("b", "c", "d", "e"), sections.active.map { it.videoId })
        assertEquals(listOf("a"), sections.ready.map { it.videoId })
    }

    @Test
    fun sectionsKeepTheOrderTheGroupedListSettledOn() {
        val sections = downloadSections(
            listOf(
                episode("s1e2", tag = "S01E02", createdAt = 10),
                episode("s1e1", tag = "S01E01", createdAt = 30),
            ),
        )

        // Episode order within a title, newest title first — the same rules
        // groupDownloads applies, flattened rather than re-sorted here.
        assertEquals(listOf("s1e1", "s1e2"), sections.ready.map { it.videoId })
    }

    @Test
    fun theDownloadThatFinishedLastComesFirstWheneverItStarted() {
        val sections = downloadSections(
            listOf(
                entry("started-first", createdAt = 1).copy(completedAt = 500),
                entry("started-last", createdAt = 300).copy(completedAt = 400),
                // Finished before completion times were recorded: its last update stands in.
                entry("legacy", createdAt = 200).copy(updatedAt = 450),
            ),
        )

        assertEquals(listOf("started-first", "legacy", "started-last"), sections.ready.map { it.videoId })
    }

    @Test
    fun aRowWithNoArtOfItsOwnBorrowsTheTitles() {
        val sections = downloadSections(
            listOf(
                episode("s1e1", tag = "S01E01", poster = null),
                episode("s1e2", tag = "S01E02", poster = "https://art.test/p.jpg"),
            ),
        )
        val bare = sections.ready.single { it.videoId == "s1e1" }

        assertEquals("https://art.test/p.jpg", downloadPoster(bare, sections))
    }

    @Test
    fun aQueueRowSplitsAnEpisodeIntoTagShowAndName() {
        val named = episode("s1e5", tag = "S01E05").let { base ->
            base.copy(media = base.media.copy(episodeName = "Zombies"))
        }

        assertEquals(QueueRowTitles("S01E05", "A Show", "Zombies"), queueRowTitles(named))
        // A film is only its title: no tag, and nothing dimmed after it.
        assertEquals(QueueRowTitles(null, "A Film", null), queueRowTitles(entry("f")))
    }

    @Test
    fun aGridCardNamesAnEpisodeByItsShowAndItsOwnName() {
        val named = episode("s1e4", tag = "S01E04").let { base ->
            base.copy(media = base.media.copy(episodeName = "Lost His Heart"))
        }

        assertEquals("A Show · Lost His Heart", downloadCardTitle(named))
        assertEquals("A Show", downloadCardTitle(episode("s1e1", tag = "S01E01")))
        assertEquals("A Film", downloadCardTitle(entry("f")))
    }

    @Test
    fun cardsUseTheEpisodeStillBeforeAnyPoster() {
        val still = episode("s1e1", tag = "S01E01").let { base ->
            base.copy(media = base.media.copy(episodeThumbnail = "https://art.test/still.jpg"))
        }
        val sections = downloadSections(listOf(still, entry("f")))

        assertEquals("https://art.test/still.jpg", downloadArt(still, sections))
        assertNull(downloadArt(sections.ready.single { it.videoId == "f" }, sections))
    }

    @Test
    fun theRateIsSplitIntoItsFigureAndItsUnit() {
        assertEquals("12" to "MB/s", rateParts(12_400_000))
        assertEquals("4.8" to "MB/s", rateParts(5_000_000))
        // A stopped link holds the figure's place with a zero.
        assertEquals("0.0" to "MB/s", rateParts(0))
    }

    @Test
    fun theSummaryCountsEachStateAndLeavesOutTheEmptyOnes() {
        assertEquals("", downloadsSummary(emptyList()))
        assertEquals(
            "1 downloading · 1 waiting · 3 ready",
            downloadsSummary(
                listOf(
                    entry("a", status = DownloadStatus.Downloading),
                    entry("b", status = DownloadStatus.Queued),
                ) + List(3) { entry("r$it") },
            ),
        )
        assertEquals(
            "2 paused · 1 failed",
            downloadsSummary(
                listOf(
                    entry("a", status = DownloadStatus.Paused),
                    entry("b", status = DownloadStatus.Paused),
                    entry("c", status = DownloadStatus.Failed),
                ),
            ),
        )
    }

    @Test
    fun theConnectionCountIsKnownOnlyWhileEveryRunningTransferReportsOne() {
        assertNull(aggregateConnections(emptyList()))
        // Nothing running: a paused entry cannot be receiving over anything.
        assertNull(aggregateConnections(listOf(entry("a", status = DownloadStatus.Paused).copy(connections = 4))))
        assertEquals(
            6,
            aggregateConnections(
                listOf(
                    entry("a", status = DownloadStatus.Downloading).copy(connections = 6),
                    entry("b", status = DownloadStatus.Queued),
                ),
            ),
        )
        // iOS never says, and unknown must not read as zero.
        assertNull(aggregateConnections(listOf(entry("a", status = DownloadStatus.Downloading))))
        assertNull(aggregateConnections(listOf(entry("a", status = DownloadStatus.Downloading).copy(connections = 0))))
    }

    @Test
    fun theConnectionPillCountsInTheSingularAndThePlural() {
        assertEquals("1 connection", connectionsLabel(1))
        assertEquals("6 connections", connectionsLabel(6))
    }

    @Test
    fun aQueueRowSaysHowFarHowMuchAndHowLong() {
        val running = entry(
            "a",
            status = DownloadStatus.Downloading,
            total = 1_610_612_736,
            done = 536_870_912,
            rate = 12_400_000,
        )

        assertEquals(listOf("512 MB of 1.5 GB", "33%", "2 min left"), queueRowMeta(running))
        // Started, but nothing has arrived and no size was declared.
        assertEquals(listOf("Starting…"), queueRowMeta(entry("a", status = DownloadStatus.Downloading, total = 0, done = 0)))
        assertEquals(
            listOf("Paused", "512 MB of 1.5 GB", "33%"),
            queueRowMeta(running.copy(status = DownloadStatus.Paused)),
        )
        assertEquals(listOf("Failed", "512 MB"), queueRowMeta(entry("a", status = DownloadStatus.Failed, total = 0, done = 536_870_912)))
        assertEquals(emptyList(), queueRowMeta(running.copy(status = DownloadStatus.Queued)))
    }

    @Test
    fun onlyAQueuedRowIsCaptionedUpNext() {
        assertEquals("Up next", queueRowCaption(entry("a", status = DownloadStatus.Queued)))
        assertNull(queueRowCaption(entry("a", status = DownloadStatus.Paused)))
        assertNull(queueRowCaption(entry("a", status = DownloadStatus.Downloading)))
    }

    @Test
    fun aCardsMetaLineEndsWithItsSubtitleOrSaysThereIsNone() {
        val named = entry("a", total = 21_474_836_480, done = 21_474_836_480).let { base ->
            base.copy(media = base.media.copy(filename = "Film.2160p.x265.mkv"))
        }

        assertEquals("2160p · HEVC · 20 GB · NO SUBS", downloadCardMeta(named))
        assertEquals(
            "2160p · HEVC · 20 GB · ENG",
            downloadCardMeta(named.copy(subtitle = DownloadSubtitle(fileName = "a.srt", lang = "eng"))),
        )
        assertEquals("SUBS", downloadSubtitleTag(named.copy(subtitle = DownloadSubtitle(fileName = "a.srt"))))
        assertEquals(DownloadQualityBadge("4K UHD", gold = true), downloadQualityBadge(named))
        val hdr = named.copy(media = named.media.copy(filename = "Film.2160p.HDR10.x265.mkv"))
        assertEquals(DownloadQualityBadge("4K HDR", gold = true), downloadQualityBadge(hdr))
        val fullHd = named.copy(media = named.media.copy(filename = "Film.1080p.x264.mkv"))
        assertEquals(false, downloadQualityBadge(fullHd)?.gold)
        assertNull(downloadQualityBadge(entry("a")))
    }

    @Test
    fun theDetailMetaLineSaysWhatTheEntryIs() {
        assertEquals("MOVIE", downloadDetailMeta(entry("f")))
        assertEquals("SERIES · A SHOW", downloadDetailMeta(episode("s1e1", tag = "S01E01")))
    }

    @Test
    fun theGridFillsTheWidthWithCellsNoNarrowerThanItsMinimum() {
        // The tablet's list beside the detail pane: three across, as drawn.
        assertEquals(3, readyGridColumns(available = 936.dp, minCell = 260.dp, gap = 16.dp))
        assertEquals(2, readyGridColumns(available = 652.dp, minCell = 260.dp, gap = 16.dp))
        // Narrower than one cell still gets one, rather than none.
        assertEquals(1, readyGridColumns(available = 200.dp, minCell = 260.dp, gap = 16.dp))
        // The gap is only counted between cells: exactly two cells and one gap fit.
        assertEquals(2, readyGridColumns(available = 536.dp, minCell = 260.dp, gap = 16.dp))
        assertEquals(2, readyGridColumns(available = 1_200.dp, minCell = 260.dp, gap = 16.dp, maxColumns = 2))
    }

    @Test
    fun theFinishedSectionIsNamedForTheDevice() {
        assertEquals("On this tablet", readySectionLabel(isTablet = true))
        assertEquals("On this device", readySectionLabel(isTablet = false))
    }

    @Test
    fun onlyARunningTransferAnimatesOrEstimates() {
        val queued = entry("a", status = DownloadStatus.Queued, total = 1_610_612_736, done = 0, rate = 5_000_000)

        assertTrue(!isTransferLive(queued))
        // A queued entry can hold a stale rate from before it was paused and
        // re-queued; it must not be turned into an estimate.
        assertNull(downloadEtaLabel(queued))
        assertEquals(
            "2 min left",
            downloadEtaLabel(
                entry(
                    "a",
                    status = DownloadStatus.Downloading,
                    total = 1_610_612_736,
                    done = 536_870_912,
                    rate = 12_400_000,
                ),
            ),
        )
    }

    @Test
    fun theAggregateIsTheRunningTransfersAlone() {
        val entries = listOf(
            entry("a", status = DownloadStatus.Downloading, rate = 12_400_000),
            entry("b", status = DownloadStatus.Paused, rate = 9_000_000),
            entry("c", status = DownloadStatus.Queued, rate = 9_000_000),
        )

        assertEquals(12_400_000, aggregateRate(entries))
    }

    @Test
    fun bytesReadAsBothHalvesWhenTheSourceDeclaredASize() {
        assertEquals(
            "512 MB of 1.5 GB",
            downloadBytesLabel(entry("a", total = 1_610_612_736, done = 536_870_912)),
        )
        assertEquals("512 MB", downloadBytesLabel(entry("a", total = 0, done = 536_870_912)))
        assertNull(downloadBytesLabel(entry("a", total = 0, done = 0)))
    }

    @Test
    fun theQueueControlOffersOnlyWhatThereIsToDo() {
        assertNull(queueControl(emptyList()))
        assertEquals(
            QueueControl.PauseAll,
            queueControl(
                listOf(
                    entry("a", status = DownloadStatus.Downloading),
                    entry("b", status = DownloadStatus.Paused),
                ),
            ),
        )
        assertEquals(
            QueueControl.ResumeAll,
            queueControl(listOf(entry("b", status = DownloadStatus.Paused))),
        )
        // Nothing running and nothing paused: a failed entry is retried from its
        // own card, not from a button that means "carry on".
        assertNull(queueControl(listOf(entry("c", status = DownloadStatus.Failed))))
    }

    @Test
    fun theMeterPlacesDownloadsAgainstTheWholeVolume() {
        val meter = storageMeter(
            space = StorageSpace(freeBytes = 64L * Gib, totalBytes = 128L * Gib),
            downloadBytes = 16L * Gib,
        )

        assertEquals(0.125f, meter.downloadsFraction)
        assertEquals(0.375f, meter.otherFraction)
        assertEquals("16", meter.downloadsFigure)
        assertEquals("GB of 128 GB", meter.ofTotalLine)
        assertEquals("16 GB", meter.downloadsLabel)
        assertEquals("48 GB", meter.otherLabel)
        assertEquals("64 GB", meter.freeLabel)
    }

    @Test
    fun anEmptyLibraryReadsAsZeroInTheVolumesUnit() {
        val meter = storageMeter(
            space = StorageSpace(freeBytes = 64L * Gib, totalBytes = 128L * Gib),
            downloadBytes = 0,
        )

        assertEquals("0", meter.downloadsFigure)
        assertEquals("GB of 128 GB", meter.ofTotalLine)
        assertEquals("0 GB", meter.downloadsLabel)
    }

    @Test
    fun theMeterCannotDrawPastItsOwnTrack() {
        // The entry byte counts and the volume figures are measured separately,
        // so downloads can exceed what the volume calls used. The segment is
        // clamped rather than allowed to push the other one negative.
        val meter = storageMeter(
            space = StorageSpace(freeBytes = 120L * Gib, totalBytes = 128L * Gib),
            downloadBytes = 64L * Gib,
        )

        assertEquals(0.0625f, meter.downloadsFraction)
        assertEquals(0f, meter.otherFraction)
    }

    @Test
    fun whatIsOnTheDeviceCountsArrivedBytesNotPromisedOnes() {
        val entries = listOf(
            entry("a", status = DownloadStatus.Done, total = 2L * Gib, done = 2L * Gib),
            entry("b", status = DownloadStatus.Paused, total = 2L * Gib, done = 1L * Gib),
        )

        assertEquals(3L * Gib, downloadedBytesOnDevice(entries))
    }

    @Test
    fun onlyAPartWatchedRowGetsAHairline() {
        assertEquals(0.5f, watchedFraction(watchState(position = 50.0, duration = 100.0)))
        assertNull(watchedFraction(null))
        assertNull(watchedFraction(watchState(position = 95.0, duration = 100.0, watched = true)))
        // A tap that opened the wrong thing and backed out is not progress.
        assertNull(watchedFraction(watchState(position = 1.0, duration = 100.0)))
        assertNull(watchedFraction(watchState(position = 10.0, duration = 0.0)))
    }

    @Test
    fun theFactsTableHoldsOnlyWhatTheEntryKnows() {
        val bare = entry("a", total = 0, done = 0)

        assertEquals(emptyList(), downloadFacts(bare.copy(createdAt = 0), Now, TimeZone.UTC))
        assertEquals(
            listOf("Size" to "1.5 GB", "Subtitle" to "English", "Added" to "29 Oct"),
            downloadFacts(
                entry("a", total = 1_610_612_736, done = 1_610_612_736, createdAt = Now)
                    .copy(subtitle = DownloadSubtitle(fileName = "a.srt", lang = "eng")),
                Now,
                TimeZone.UTC,
            ),
        )
    }

    @Test
    fun anAddedDateCarriesItsYearOnlyWhenItIsNotThisOne() {
        assertEquals("29 Oct", downloadAddedLabel(Now, Now, TimeZone.UTC))
        assertEquals("29 Oct 2025", downloadAddedLabel(Now - 365L * DayMs, Now, TimeZone.UTC))
        assertNull(downloadAddedLabel(0, Now, TimeZone.UTC))
    }

    @Test
    fun qualityIsReadFromTheSourcesOwnNamingOrLeftOut() {
        val named = entry("a").let { base ->
            base.copy(media = base.media.copy(filename = "Show.S01E01.1080p.x265.mkv"))
        }

        assertEquals("1080p · HEVC", downloadQualityLabel(named))
        assertEquals("", downloadQualityLabel(entry("a")))
    }

    @Test
    fun sectionCountsSayHowManyAndHowMuch() {
        assertEquals("1 item", itemCountLabel(1))
        assertEquals("3 items", itemCountLabel(3))
        assertEquals(
            "2 items · 3.0 GB",
            readyCountLabel(List(2) { entry("r$it", total = 1_610_612_736, done = 1_610_612_736) }),
        )
    }
}

private const val Gib = 1024L * 1024 * 1024
private const val DayMs = 24L * 60 * 60 * 1000

/** 2026-10-29T12:00:00Z. */
private const val Now = 1_793_275_200_000L

private fun entry(
    videoId: String,
    status: DownloadStatus = DownloadStatus.Done,
    total: Long = 100,
    done: Long = 100,
    rate: Long = 0,
    createdAt: Long = 1,
): DownloadEntry = DownloadEntry(
    media = DownloadMedia(
        videoId = videoId,
        type = "movie",
        metaId = videoId,
        showTitle = "A Film",
        sourceUrl = "https://source.test/$videoId.mkv",
        addonId = "addon",
    ),
    fileName = "$videoId.mkv",
    status = status,
    totalBytes = total,
    downloadedBytes = done,
    createdAt = createdAt,
    updatedAt = createdAt,
    bytesPerSecond = rate,
)

private fun episode(
    videoId: String,
    tag: String,
    status: DownloadStatus = DownloadStatus.Done,
    createdAt: Long = 1,
    poster: String? = "https://art.test/p.jpg",
): DownloadEntry = DownloadEntry(
    media = DownloadMedia(
        videoId = videoId,
        type = "series",
        metaId = "show",
        showTitle = "A Show",
        episodeTag = tag,
        poster = poster,
        sourceUrl = "https://source.test/$videoId.mkv",
        addonId = "addon",
    ),
    fileName = "$videoId.mkv",
    status = status,
    totalBytes = 100,
    downloadedBytes = 100,
    createdAt = createdAt,
    updatedAt = createdAt,
)

private fun watchState(
    position: Double,
    duration: Double,
    watched: Boolean = false,
): WatchState = WatchState(
    videoId = "a",
    itemId = "movie:a",
    positionSec = position,
    durationSec = duration,
    watched = watched,
    updatedAt = 1,
)
