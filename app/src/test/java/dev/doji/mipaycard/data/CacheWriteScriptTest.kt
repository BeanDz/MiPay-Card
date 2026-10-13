package dev.doji.mipaycard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

class CacheWriteScriptTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val key = "a".repeat(64)
    private lateinit var wallet: File
    private lateinit var cache: File
    private lateinit var target: File
    private lateinit var previous: File
    private lateinit var replacement: File
    private lateinit var bin: File
    private lateinit var stopped: File

    @Before
    fun setUp() {
        wallet = temporary.newFolder("wallet's directory")
        cache = File(wallet, "cache/image_manager_disk_cache").apply { mkdirs() }
        target = File(cache, "$key.0").apply { writeText("old-face") }
        previous = temporary.newFile("previous's image").apply { writeText("old-face") }
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
        if (System.getProperty("os.name")?.startsWith("Mac") == true) {
            // Exercise the Android script unchanged; only adapt the host stat CLI.
            executable(
                "stat",
                """
                test "${'$'}1" = -c || exit 1
                case "${'$'}2" in
                    '%h') exec /usr/bin/stat -f '%l' "${'$'}3" ;;
                    '%u:%g:%a:%C:%d:%i:%h') exec /usr/bin/stat -f '%u:%g:%Lp:synthetic:%d:%i:%l' "${'$'}3" ;;
                    *) exit 1 ;;
                esac
                """.trimIndent(),
            )
        }
    }

    @Test
    fun writesIntoExistingInodeAndPreservesPermissions() {
        val permissions = PosixFilePermissions.fromString("rw-r-----")
        Files.setPosixFilePermissions(target.toPath(), permissions)
        val inode = Files.getAttribute(target.toPath(), "unix:ino")
        val owner = Files.getOwner(target.toPath())

        assertEquals(0, execute())

        assertEquals("new-face", target.readText())
        assertEquals("old-face", previous.readText())
        assertEquals(inode, Files.getAttribute(target.toPath(), "unix:ino"))
        assertEquals(owner, Files.getOwner(target.toPath()))
        assertEquals(permissions, Files.getPosixFilePermissions(target.toPath()))
        assertEquals("stopped\n", stopped.readText())
    }

    @Test
    fun rejectsSameSizeStaleContentBeforeStoppingWallet() {
        target.writeText("elseface")

        assertEquals(42, execute())

        assertEquals("elseface", target.readText())
        assertFalse(stopped.exists())
    }

    @Test
    fun rejectsSymbolicLinkTargetWithoutTouchingItsDestination() {
        val external = temporary.newFile("external").apply { writeText("old-face") }
        Files.delete(target.toPath())
        Files.createSymbolicLink(target.toPath(), external.toPath())

        assertEquals(41, execute())

        assertEquals("old-face", external.readText())
        assertFalse(stopped.exists())
    }

    @Test
    fun rejectsCacheDirectorySymbolicLink() {
        val external = temporary.newFolder("external-cache")
        val externalTarget = File(external, "$key.0").apply { writeText("old-face") }
        assertTrue(cache.deleteRecursively())
        Files.createSymbolicLink(cache.toPath(), external.toPath())

        assertEquals(41, execute())

        assertEquals("old-face", externalTarget.readText())
        assertFalse(stopped.exists())
    }

    @Test
    fun rejectsSymbolicLinkInCacheParent() {
        val external = temporary.newFolder("external-parent")
        val externalCache = File(external, "image_manager_disk_cache").apply { mkdir() }
        val externalTarget = File(externalCache, "$key.0").apply { writeText("old-face") }
        val parent = File(wallet, "cache")
        assertTrue(parent.deleteRecursively())
        Files.createSymbolicLink(parent.toPath(), external.toPath())

        assertEquals(41, execute())

        assertEquals("old-face", externalTarget.readText())
        assertFalse(stopped.exists())
    }

    @Test
    fun rejectsHardLinkedEntry() {
        val alias = File(temporary.root, "hard-link")
        Files.createLink(alias.toPath(), target.toPath())

        assertEquals(41, execute())

        assertEquals("old-face", alias.readText())
        assertFalse(stopped.exists())
    }

    @Test
    fun failedWriterRollsBackInsideTheSameJob() {
        failWrites()
        val inode = Files.getAttribute(target.toPath(), "unix:ino")

        assertEquals(44, execute())

        assertEquals("old-face", target.readText())
        assertEquals(inode, Files.getAttribute(target.toPath(), "unix:ino"))
        assertTrue(stopped.exists())
    }

    @Test
    fun reportsRollbackFailureAndKeepsPreviousSnapshot() {
        failWrites(rollback = true)

        assertEquals(45, execute())

        assertEquals("old-face", previous.readText())
        assertEquals("broken", target.readText())
    }

    @Test
    fun failedForceStopDoesNotWrite() {
        executable("am", "exit 1")

        assertEquals(46, execute())

        assertEquals("old-face", target.readText())
    }

    @Test
    fun checksOnlyRequestedSafeCacheKeys() {
        val missing = "b".repeat(64)
        val unrelated = "c".repeat(64)
        File(cache, "$unrelated.0").writeText("unrelated")
        val linked = "d".repeat(64)
        val linkedTarget = File(cache, "$linked.0").apply { writeText("linked") }
        Files.createLink(temporary.newFile("linked-alias").apply { delete() }.toPath(), linkedTarget.toPath())

        val (exitCode, output) = execute(cacheCandidateKeysScript(wallet.path, setOf(key, missing, linked)))

        assertEquals(0, exitCode)
        assertEquals("$key\n", output)
    }

    @Test
    fun rejectsPathTraversalKeyBeforeBuildingTheJob() {
        assertThrows(IllegalArgumentException::class.java) {
            cacheWriteScript(wallet.path, "../$key", replacement.path, previous.path)
        }
        assertEquals("old-face", target.readText())
    }

    private fun failWrites(rollback: Boolean = false) {
        executable(
            "cat",
            """
            if test "${'$'}1" = ${quoteShell(replacement.canonicalPath)}; then
                printf broken
                exit 1
            fi
            if test ${quoteShell(
                rollback.toString(),
            )} = true && test "${'$'}1" = ${quoteShell(previous.canonicalPath)}; then
                printf broken
                exit 1
            fi
            exec /bin/cat "${'$'}@"
            """.trimIndent(),
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

    private fun execute(): Int {
        val (exitCode, diagnostics) =
            execute(cacheWriteScript(wallet.path, key, replacement.path, previous.path))
        assertEquals("Unexpected shell diagnostics: $diagnostics", "", diagnostics)
        return exitCode
    }

    private fun execute(script: String): Pair<Int, String> {
        val builder =
            ProcessBuilder(
                "/bin/sh",
                "-c",
                script,
            ).redirectErrorStream(true)
        builder.environment().apply {
            this["PATH"] = "${bin.path}:${this["PATH"]}"
            this["STOP_MARKER"] = stopped.path
        }
        val process = builder.start()
        try {
            assertTrue("Root transaction did not finish", process.waitFor(10, TimeUnit.SECONDS))
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
