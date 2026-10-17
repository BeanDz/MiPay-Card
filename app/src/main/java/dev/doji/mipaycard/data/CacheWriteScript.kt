package dev.doji.mipaycard.data

private val cacheKeyPattern = Regex("[0-9a-f]{64}")

internal fun quoteShell(value: String): String = "'${value.replace("'", "'\\''")}'"

internal fun cacheListScript(walletDataDir: String): String =
    """
    cache=${quoteShell("$walletDataDir/cache/image_manager_disk_cache")}
    test -d "${'$'}cache" || exit 0
    ls -1p "${'$'}cache"
    """.trimIndent()

/** Cache entries are named `<sha-256>.0` by Glide; directories are listed with a trailing slash. */
internal fun parseCacheKeys(lines: List<String>): Set<String> =
    lines
        .asSequence()
        .filter { !it.endsWith("/") && it.endsWith(".0") }
        .map { it.removeSuffix(".0") }
        .filter(cacheKeyPattern::matches)
        .toSet()

internal data class CacheWrite(
    val key: String,
    val source: String,
)

internal fun cacheWriteScript(
    walletDataDir: String,
    writes: List<CacheWrite>,
    invalidation: String? = null,
): String =
    buildString {
        val cache = "$walletDataDir/cache/image_manager_disk_cache"
        appendLine("am force-stop $WALLET_PACKAGE || exit 1")
        writes.forEach { write ->
            appendLine("cat ${quoteShell(write.source)} > ${quoteShell("$cache/${write.key}.0")} || exit 1")
        }
        if (invalidation != null) {
            appendLine("rm -f ${quoteShell("$cache/$invalidation.0")} || exit 1")
        }
    }
