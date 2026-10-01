package moe.ditto.halo.player

/**
 * The OpenSubtitles "moviehash": the file size plus the little-endian 64-bit
 * words of its first and last 64 KiB, summed modulo 2^64 and written as sixteen
 * hex digits.
 *
 * This is what makes subtitle results correct rather than plausible. Searching
 * by title returns subtitles for *a* release; searching by hash returns the
 * ones cut for the exact file being watched, which is the difference between
 * dialogue that lands and dialogue that drifts. Addons take it as the
 * `videoHash` extra.
 *
 * Only 128 KiB of the file is ever needed, so a stream can be hashed with two
 * range requests instead of a download.
 */
internal object VideoHash {

    /** The chunk length the hash is defined over. Not a tuning knob. */
    const val ChunkBytes = 64 * 1024

    /**
     * A file shorter than the two chunks would fold bytes into the sum twice,
     * which is not the same function; such files simply have no hash.
     */
    const val MinimumHashableBytes = ChunkBytes.toLong() * 2

    /**
     * Builds the hash from chunks the caller already has, which is the shape
     * both sources need: a downloaded file reads them from disk, and a stream
     * reads them over HTTP.
     */
    fun fromChunks(sizeBytes: Long, head: ByteArray, tail: ByteArray): String {
        val sum = sizeBytes.toULong() + sumLittleEndianWords(head) + sumLittleEndianWords(tail)
        return sum.toString(16).padStart(16, '0')
    }

    /**
     * `ULong` addition wraps at 2^64 in Kotlin, which is exactly the modulus
     * the definition calls for, so no masking is needed.
     */
    private fun sumLittleEndianWords(bytes: ByteArray): ULong {
        var sum = 0uL
        val whole = bytes.size - (bytes.size % Long.SIZE_BYTES)
        var offset = 0
        while (offset < whole) {
            var word = 0uL
            for (byte in Long.SIZE_BYTES - 1 downTo 0) {
                word = (word shl 8) or bytes[offset + byte].toUByte().toULong()
            }
            sum += word
            offset += Long.SIZE_BYTES
        }
        // A file whose length is not a multiple of eight ends in a short word,
        // which counts as if it were zero-padded on the right.
        if (whole < bytes.size) {
            var word = 0uL
            for (index in bytes.size - 1 downTo whole) {
                word = (word shl 8) or bytes[index].toUByte().toULong()
            }
            sum += word
        }
        return sum
    }
}
