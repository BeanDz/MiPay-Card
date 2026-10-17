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

class NotificationPreviewScriptTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val aid = "A000000001000001"
    private lateinit var bin: File
    private lateinit var invoked: File

    @Before
    fun setUp() {
        bin = temporary.newFolder("bin")
        invoked = File(temporary.root, "am-invoked")
        executable(
            "settings",
            """
            test "${'$'}1" = get && test "${'$'}2" = secure || exit 1
            case "${'$'}3" in
                key_simple_card_swipe_switch) printf '%s\n' "${'$'}PREVIEW_ENABLED" ;;
                TSM_CARD_ACTIVATE_RECORD) printf '%s\n' "${'$'}ACTIVE_RECORD" ;;
                *) exit 1 ;;
            esac
            """.trimIndent(),
        )
        executable(
            "am",
            """
            printf '%s\n' "${'$'}@" > "${'$'}AM_MARKER"
            printf '%s\n' "${'$'}AM_OUTPUT"
            """.trimIndent(),
        )
    }

    @Test
    fun disabledSimpleSwipingDoesNotStartService() {
        assertEquals(51, execute(enabled = "0"))

        assertFalse(invoked.exists())
    }

    @Test
    fun changedActiveCardDoesNotStartService() {
        assertEquals(52, execute(activeRecord = "A000000001000002,12345"))

        assertFalse(invoked.exists())
    }

    @Test
    fun reportsServiceErrorEvenWhenAmExitsSuccessfully() {
        assertEquals(53, execute(amOutput = "Error: Not found; no service started."))

        assertTrue(invoked.exists())
    }

    @Test
    fun startsNfcMonitorWithRfOnForTheActiveCard() {
        assertEquals(0, execute())

        assertEquals(
            listOf(
                "startservice",
                "-n",
                "com.miui.tsmclient/.service.NfcEventMonitorService",
                "-a",
                "com.miui.nfc.action.RF_ON",
            ),
            invoked.readLines(),
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
        enabled: String = "1",
        activeRecord: String = "$aid,12345",
        amOutput: String = "Starting service: Intent",
    ): Int {
        val builder =
            ProcessBuilder("/bin/sh", "-c", notificationPreviewScript(aid))
                .redirectErrorStream(true)
        builder.environment().apply {
            this["PATH"] = "${bin.path}:${this["PATH"]}"
            this["PREVIEW_ENABLED"] = enabled
            this["ACTIVE_RECORD"] = activeRecord
            this["AM_MARKER"] = invoked.path
            this["AM_OUTPUT"] = amOutput
        }
        val process = builder.start()
        try {
            assertTrue("Notification preview did not finish", process.waitFor(10, TimeUnit.SECONDS))
            val diagnostics = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals("Unexpected shell diagnostics: $diagnostics", "", diagnostics)
            return process.exitValue()
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.inputStream.close()
            process.outputStream.close()
            process.errorStream.close()
        }
    }
}
