package dev.doji.mipaycard.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class CardImageFingerprintTest {
    @Test
    fun detectsSameSizeChangesBeyondTheFirstTwoBlocks() {
        val file = Files.createTempFile("synthetic-card-", ".cache").toFile()
        try {
            val bytes = ByteArray(16385) { (it % 251).toByte() }
            file.writeBytes(bytes)
            assertEquals("ba4f9b37402df1e3ad948a794ab43a9ed887d63e3a389c208ca4314fdd5add58", fingerprint(file))

            bytes[bytes.lastIndex] = 9
            file.writeBytes(bytes)
            assertEquals("165f1f5bab56f1db5801d4f591782c9e6a2bf0226c478d66f81b0c370f745c3d", fingerprint(file))
        } finally {
            Files.deleteIfExists(file.toPath())
        }
    }
}
