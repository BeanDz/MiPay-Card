package dev.doji.mipaycard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class CacheWriteScriptTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val key = "a".repeat(64)
    private val notificationKey = "b".repeat(64)
    private val thumbnailKey = "c".repeat(64)
    private lateinit var wallet: File
    private lateinit var cache: File
    private lateinit var target: File
    private lateinit var notification: File
    private lateinit var replacement: File
    private lateinit var bin: File
    private lateinit var stopped: File

    @Before
    fun setUp() {
        wallet = temporary.newFolder("wallet's directory")
        cache = File(wallet, "cache/image_manager_disk_cache").apply { mkdirs() }
        target = File(cache, "$key.0").apply { writeText("old-face") }
        notification = File(cache, "$notificationKey.0").apply { writeText("old-notification") }
        replacement = temporary.newFile("new image").apply { writeText("new-face") }
        stopped = File(temporary.root, "wallet-stopped")
        bin = temporary.newFolder("bin")
        executable(
            "am",
            """
            test "${'$'}1" = force-stop && test "${'$'}2" = com.miui.tsmclient || exit 1
            printf 'stopped\n' >> "${'$'}STOP_MARKER"
            """.trimIndent(),
        )
    }

    @Test
    fun replacesCardAndNotificationImages() {
        val journal = File(cache, "journal").apply { writeText("unchanged-journal") }

        assertEquals(0, execute())

        assertEquals("new-face", target.readText())
        assertEquals("new-face", notification.readText())
        assertEquals("unchanged-journal", journal.readText())
        assertEquals("stopped\n", stopped.readText())
    }

    @Test
    fun restoresEachImageFromItsOwnOriginal() {
        val original = temporary.newFile("original's image").apply { writeText("original-face") }
        val notificationOriginal = temporary.newFile("notification original").apply { writeText("original-notification") }
        target.writeText("custom-face")
        notification.writeText("custom-face")

        assertEquals(
            0,
            execute(
                writes =
                    listOf(
                        CacheWrite(key, original.path),
                        CacheWrite(notificationKey, notificationOriginal.path),
                    ),
            ),
        )

        assertEquals("original-face", target.readText())
        assertEquals("original-notification", notification.readText())
    }

    @Test
    fun deletesDerivedNotificationThumbnail() {
        val thumbnail = File(cache, "$thumbnailKey.0").apply { writeText("old-thumbnail") }

        assertEquals(0, execute(invalidation = thumbnailKey))

        assertEquals("new-face", target.readText())
        assertEquals("new-face", notification.readText())
        assertFalse(thumbnail.exists())
    }

    @Test
    fun acceptsAlreadyMissingThumbnail() {
        assertEquals(0, execute(invalidation = thumbnailKey))

        assertEquals("new-face", target.readText())
        assertEquals("new-face", notification.readText())
    }

    @Test
    fun reportsWriteFailureAndLeavesThumbnailForNextAttempt() {
        val thumbnail = File(cache, "$thumbnailKey.0").apply { writeText("old-thumbnail") }
        executable(
            "cat",
            """
            if test "${'$'}1" = ${quoteShell(replacement.path)} && test -f "${'$'}WRITE_MARKER"; then
                exit 1
            fi
            touch "${'$'}WRITE_MARKER"
            exec /bin/cat "${'$'}@"
            """.trimIndent(),
        )

        assertEquals(1, execute(invalidation = thumbnailKey))

        assertEquals("new-face", target.readText())
        assertEquals("old-thumbnail", thumbnail.readText())
    }

    @Test
    fun reportsThumbnailDeletionFailure() {
        val thumbnail = File(cache, "$thumbnailKey.0").apply { writeText("old-thumbnail") }
        executable("rm", "exit 1")

        assertEquals(1, execute(invalidation = thumbnailKey))

        assertEquals("new-face", target.readText())
        assertEquals("new-face", notification.readText())
        assertEquals("old-thumbnail", thumbnail.readText())
    }

    @Test
    fun failedForceStopDoesNotWrite() {
        executable("am", "exit 1")

        assertEquals(1, execute())

        assertEquals("old-face", target.readText())
        assertEquals("old-notification", notification.readText())
    }

    @Test
    fun listsOnlyCacheEntryNames() {
        File(cache, "note.txt").writeText("note")
        File(cache, "subdirectory.0").mkdir()

        val (exitCode, output) = execute(cacheListScript(wallet.path))

        assertEquals(0, exitCode)
        assertEquals(setOf(key, notificationKey), parseCacheKeys(output.lines()))
    }

    @Test
    fun acceptsMissingCacheDirectoryAsEmpty() {
        assertTrue(cache.deleteRecursively())

        val (exitCode, output) = execute(cacheListScript(wallet.path))

        assertEquals(0, exitCode)
        assertTrue(parseCacheKeys(output.lines()).isEmpty())
    }

    @Test
    fun parsesCacheKeysFromListing() {
        assertEquals(
            setOf(key),
            parseCacheKeys(listOf("$key.0", "$key.0/", "note", "not-a-key.0", "d".repeat(64))),
        )
    }

    private fun executable(
        name: String,
        script: String,
    ) {
        File(bin, name).apply {
            writeText("#!/bin/sh\n$script\n")
            check(setExecutable(true))
        }
    }

    private fun execute(
        writes: List<CacheWrite> =
            listOf(
                CacheWrite(key, replacement.path),
                CacheWrite(notificationKey, replacement.path),
            ),
        invalidation: String? = null,
    ): Int {
        val (exitCode, diagnostics) = execute(cacheWriteScript(wallet.path, writes, invalidation))
        assertEquals("Unexpected shell diagnostics: $diagnostics", "", diagnostics)
        return exitCode
    }

    private fun execute(script: String): Pair<Int, String> {
        val builder = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true)
        builder.environment().apply {
            this["PATH"] = "${bin.path}:${this["PATH"]}"
            this["STOP_MARKER"] = stopped.path
            this["WRITE_MARKER"] = File(temporary.root, "first-write").path
        }
        val process = builder.start()
        try {
            assertTrue("Cache command did not finish", process.waitFor(10, TimeUnit.SECONDS))
            val diagnostics = process.inputStream.bufferedReader().use { it.readText() }
            return process.exitValue() to diagnostics
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.inputStream.close()
            process.outputStream.close()
            process.errorStream.close()
        }
    }
}
