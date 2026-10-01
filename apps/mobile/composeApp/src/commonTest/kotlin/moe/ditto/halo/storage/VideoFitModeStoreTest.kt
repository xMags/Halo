package moe.ditto.halo.storage

import moe.ditto.halo.api.VideoFitMode
import kotlin.test.Test
import kotlin.test.assertEquals

class VideoFitModeStoreTest {
    @Test
    fun validLocalValueIsRestored() {
        val values = mutableMapOf(StorageKeys.VideoFitMode to "cover")
        val store = VideoFitModeStore(FakeStore(values))

        assertEquals(VideoFitMode.Cover, store.current())
    }

    @Test
    fun missingLocalValueFitsTheWholePicture() {
        assertEquals(VideoFitMode.Contain, VideoFitModeStore(FakeStore()).current())
    }

    @Test
    fun corruptLocalValueFallsBackAndCanBeReplaced() {
        val values = mutableMapOf(StorageKeys.VideoFitMode to "stretch")
        val store = VideoFitModeStore(FakeStore(values))

        assertEquals(VideoFitMode.Contain, store.current())
        store.update(VideoFitMode.Cover)
        assertEquals("cover", values[StorageKeys.VideoFitMode])
    }

    private class FakeStore(
        val values: MutableMap<String, String> = mutableMapOf(),
    ) : KeyValueStore {
        override fun read(key: String): String? = values[key]

        override fun write(key: String, value: String) {
            values[key] = value
        }

        override fun delete(key: String) {
            values.remove(key)
        }
    }
}
