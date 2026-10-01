package moe.ditto.halo.screens

import moe.ditto.halo.api.WatchState
import moe.ditto.halo.downloads.DownloadEntry
import moe.ditto.halo.downloads.DownloadMedia
import moe.ditto.halo.downloads.DownloadStatus
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
    fun onlyAnEpisodeCarriesASecondLine() {
        assertEquals("A Show", downloadRowSubtitle(episode("s1e1", tag = "S01E01")))
        // A film's name is already the title line; repeating it says nothing.
        assertNull(downloadRowSubtitle(entry("f")))
    }

    @Test
    fun theRateSlotSaysWhyThereIsNoRate() {
        assertEquals(
            "12 MB/s",
            downloadRateLabel(entry("a", status = DownloadStatus.Downloading, rate = 12_400_000)),
        )
        assertEquals(
            "4.8 MB/s",
            downloadRateLabel(entry("a", status = DownloadStatus.Downloading, rate = 5_000_000)),
        )
        // A transfer that has started but has not been measured yet holds the
        // slot with a zero rather than jumping in from nothing.
        assertEquals("0.0 MB/s", downloadRateLabel(entry("a", status = DownloadStatus.Downloading)))
        assertEquals("Queued", downloadRateLabel(entry("a", status = DownloadStatus.Queued, rate = 999)))
        assertEquals("Paused", downloadRateLabel(entry("a", status = DownloadStatus.Paused)))
        assertEquals("Failed", downloadRateLabel(entry("a", status = DownloadStatus.Failed)))
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
    fun theQueueLineCountsTransfersRatherThanEntries() {
        assertEquals("queue empty", queueLine(emptyList()))
        assertEquals(
            "1 transfer · 2 waiting",
            queueLine(
                listOf(
                    entry("a", status = DownloadStatus.Downloading),
                    entry("b", status = DownloadStatus.Queued),
                    entry("c", status = DownloadStatus.Paused),
                ),
            ),
        )
        assertEquals("2 items waiting", queueLine(List(2) { entry("p$it", status = DownloadStatus.Paused) }))
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
        assertEquals("16 GB of downloads · 64 GB used", meter.usedLine)
        assertEquals("64 GB free of 128 GB", meter.freeLine)
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

        assertEquals(emptyList(), downloadFacts(bare))
        assertEquals(
            listOf("File size" to "1.5 GB"),
            downloadFacts(entry("a", total = 1_610_612_736, done = 1_610_612_736)),
        )
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
