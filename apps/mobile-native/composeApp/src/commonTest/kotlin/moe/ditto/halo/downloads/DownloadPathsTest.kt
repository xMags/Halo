package moe.ditto.halo.downloads

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadPathsTest {

    @Test
    fun aVideoIdNeverContributesAPathComponent() {
        val name = DownloadPaths.videoFileName("../../etc/tt1:1:2", "https://source.test/file.mkv")

        assertFalse(name.contains('/'))
        assertFalse(name.contains(':'))
        assertTrue(name.endsWith(".mkv"))
    }

    @Test
    fun idsThatSanitiseAlikeStillGetDifferentFiles() {
        val first = DownloadPaths.videoFileName("show:1:2", "https://source.test/file.mkv")
        val second = DownloadPaths.videoFileName("show/1/2", "https://source.test/file.mkv")

        assertFalse(first == second)
    }

    @Test
    fun theSameVideoAlwaysResolvesToTheSameFile() {
        val first = DownloadPaths.videoFileName("tt1", "https://source.test/file.mkv")
        val second = DownloadPaths.videoFileName("tt1", "https://source.test/file.mkv")

        assertEquals(first, second)
    }

    @Test
    fun theSourceExtensionIsKeptOnlyWhenItIsOneWeStore() {
        assertTrue(DownloadPaths.videoFileName("tt1", "https://source.test/a.mp4").endsWith(".mp4"))
        // A resolver's own endpoint, not a file name.
        assertTrue(DownloadPaths.videoFileName("tt1", "https://source.test/resolve.php").endsWith(".mkv"))
        assertTrue(DownloadPaths.videoFileName("tt1", "https://source.test/a?token=x.exe").endsWith(".mkv"))
        assertTrue(DownloadPaths.videoFileName("tt1", "https://source.test/a.mkv?t=1").endsWith(".mkv"))
    }

    @Test
    fun aSubtitleIsStoredInTheFormatItWasServedIn() {
        assertTrue(DownloadPaths.subtitleFileName("tt1", "https://subs.test/a.ass").endsWith(".ass"))
        assertTrue(DownloadPaths.subtitleFileName("tt1", "https://subs.test/download/9").endsWith(".srt"))
    }

    @Test
    fun aSubtitleNeverCollidesWithItsOwnVideo() {
        val video = DownloadPaths.videoFileName("tt1", "https://source.test/a.mkv")
        val subtitle = DownloadPaths.subtitleFileName("tt1", "https://subs.test/a.srt")

        assertFalse(video == subtitle)
    }

    @Test
    fun theSweepRemovesOnlyWhatNoEntryClaims() {
        val fileSystem = FakeFileSystem()
        val directory = "/downloads".toPath()
        fileSystem.createDirectories(directory)
        val kept = entry(videoId = "kept", fileName = "kept.mkv", subtitle = DownloadSubtitle("kept.srt"))
        val partial = entry(videoId = "partial", status = DownloadStatus.Paused, fileName = "partial.mkv")
        listOf("kept.mkv", "kept.srt", "partial.mkv.part", "stray.mkv", "stray.mkv.part").forEach {
            fileSystem.write(directory / it) { writeUtf8("x") }
        }

        DownloadPaths.sweepOrphans(fileSystem, directory, listOf(kept, partial))

        assertEquals(
            listOf("kept.mkv", "kept.srt", "partial.mkv.part"),
            fileSystem.list(directory).map { it.name }.sorted(),
        )
    }

    @Test
    fun aDirectoryThatDoesNotExistIsNothingToSweep() {
        val fileSystem = FakeFileSystem()

        DownloadPaths.sweepOrphans(fileSystem, "/downloads".toPath(), emptyList())
    }
}
