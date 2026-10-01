package moe.ditto.halo.player

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The hash has to agree byte for byte with what subtitle addons index, so these
 * cases pin the definition rather than this implementation of it: the modulus,
 * the byte order, and the short trailing word.
 */
class VideoHashTest {

    @Test
    fun sizeAloneDecidesTheHashOfEmptyChunks() {
        // With nothing to sum, the hash is the file size in hex, padded to the
        // full sixteen digits addons expect.
        assertEquals(
            "0000000000000010",
            VideoHash.fromChunks(sizeBytes = 16, head = ByteArray(0), tail = ByteArray(0)),
        )
    }

    @Test
    fun wordsAreReadLittleEndian() {
        // 0x01 in the lowest byte is one, not 0x0100000000000000.
        val head = byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0)
        assertEquals("0000000000000001", VideoHash.fromChunks(0, head, ByteArray(0)))

        val highest = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1)
        assertEquals("0100000000000000", VideoHash.fromChunks(0, highest, ByteArray(0)))
    }

    @Test
    fun aTrailingPartialWordIsZeroPaddedRatherThanDropped() {
        // Five bytes is not a whole word, and ignoring the remainder would make
        // two different files hash the same.
        val head = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 2, 1)
        assertEquals("0000000000000102", VideoHash.fromChunks(0, head, ByteArray(0)))
    }

    @Test
    fun theSumWrapsAtTwoToTheSixtyFourth() {
        // Every byte set is 0xFFFFFFFFFFFFFFFF; adding one wraps to zero, which
        // is the modulus the definition calls for.
        val head = ByteArray(8) { 0xFF.toByte() }
        assertEquals("0000000000000000", VideoHash.fromChunks(sizeBytes = 1, head = head, tail = ByteArray(0)))
    }

    @Test
    fun headAndTailBothCount() {
        val head = byteArrayOf(3, 0, 0, 0, 0, 0, 0, 0)
        val tail = byteArrayOf(4, 0, 0, 0, 0, 0, 0, 0)
        assertEquals("000000000000000c", VideoHash.fromChunks(sizeBytes = 5, head = head, tail = tail))
    }

    @Test
    fun theChunkLengthIsTheOneTheDefinitionFixes() {
        // Not a tuning knob: changing it changes the function, and the results
        // would silently stop matching what addons have indexed.
        assertEquals(65_536, VideoHash.ChunkBytes)
        assertEquals(131_072L, VideoHash.MinimumHashableBytes)
    }
}
