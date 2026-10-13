package dev.doji.mipaycard.data

/** Root-side validation and recovery keep a failed write in one shell job. */
private val cacheKeyPattern = Regex("[0-9a-f]{64}")

internal fun validCacheKey(key: String): Boolean = cacheKeyPattern.matches(key)

internal fun quoteShell(value: String): String = "'${value.replace("'", "'\\''")}'"

internal fun cacheDirectoryGuard(walletDataDir: String): String =
    """
    wallet=${quoteShell(walletDataDir)}
    validate_directory() {
        wallet_root=${'$'}(readlink -f "${'$'}wallet") || return 1
        test -d "${'$'}wallet_root" || return 1
        test ! -L "${'$'}wallet_root/cache" || return 1
        test ! -L "${'$'}wallet_root/cache/image_manager_disk_cache" || return 1
        cache_root=${'$'}(readlink -f "${'$'}wallet_root/cache/image_manager_disk_cache") || return 1
        test "${'$'}cache_root" = "${'$'}wallet_root/cache/image_manager_disk_cache" || return 1
        test -d "${'$'}cache_root" || return 1
    }
    """.trimIndent()

internal fun cacheTargetGuard(
    walletDataDir: String,
    key: String,
): String {
    require(validCacheKey(key)) { "Invalid cache key" }
    return cacheDirectoryGuard(walletDataDir) +
        "\n" +
        """
        key=${quoteShell(key)}
        validate_target() {
            validate_directory || return 1
            target="${'$'}cache_root/${'$'}key.0"
            test -f "${'$'}target" || return 1
            test ! -L "${'$'}target" || return 1
            resolved=${'$'}(readlink -f "${'$'}target") || return 1
            test "${'$'}resolved" = "${'$'}target" || return 1
            links=${'$'}(stat -c '%h' "${'$'}target") || return 1
            test "${'$'}links" = 1 || return 1
        }
        """.trimIndent()
}

internal fun cacheCandidateKeysScript(
    walletDataDir: String,
    candidates: Set<String>,
): String {
    require(candidates.all(::validCacheKey)) { "Invalid cache key" }
    val checks = candidates.sorted().joinToString("\n") { "existing_key ${quoteShell(it)} || exit 41" }
    return cacheDirectoryGuard(walletDataDir) +
        "\n" +
        """
        wallet_root=${'$'}(readlink -f "${'$'}wallet") || exit 41
        test -d "${'$'}wallet_root" || exit 41
        test ! -L "${'$'}wallet_root/cache" || exit 41
        test ! -L "${'$'}wallet_root/cache/image_manager_disk_cache" || exit 41
        test -d "${'$'}wallet_root/cache/image_manager_disk_cache" || exit 0
        validate_directory || exit 41
        existing_key() {
            key="${'$'}1"
            target="${'$'}cache_root/${'$'}key.0"
            test -f "${'$'}target" || return 0
            test ! -L "${'$'}target" || return 0
            resolved=${'$'}(readlink -f "${'$'}target") || return 1
            test "${'$'}resolved" = "${'$'}target" || return 0
            links=${'$'}(stat -c '%h' "${'$'}target") || return 1
            test "${'$'}links" = 1 || return 0
            printf '%s\n' "${'$'}key"
        }
        $checks
        """.trimIndent()
}

internal fun cacheWriteScript(
    walletDataDir: String,
    key: String,
    source: String,
    previous: String,
): String =
    cacheTargetGuard(walletDataDir, key) +
        "\n" +
        """
        source=${quoteShell(source)}
        previous=${quoteShell(previous)}
        validate_target || exit 41
        test -f "${'$'}source" && test ! -L "${'$'}source" || exit 41
        test -f "${'$'}previous" && test ! -L "${'$'}previous" || exit 41
        source=${'$'}(readlink -f "${'$'}source") || exit 41
        previous=${'$'}(readlink -f "${'$'}previous") || exit 41
        test "${'$'}source" != "${'$'}target" && test "${'$'}previous" != "${'$'}target" || exit 41
        cmp -s "${'$'}previous" "${'$'}target" || exit 42
        am force-stop com.miui.tsmclient || exit 46
        validate_target || exit 41
        cmp -s "${'$'}previous" "${'$'}target" || exit 42
        metadata=${'$'}(stat -c '%u:%g:%a:%C:%d:%i:%h' "${'$'}target") || exit 43
        test -n "${'$'}metadata" || exit 43
        recover() {
            trap - EXIT HUP INT TERM
            validate_target || exit 45
            actual=${'$'}(stat -c '%u:%g:%a:%C:%d:%i:%h' "${'$'}target") || exit 45
            test "${'$'}actual" = "${'$'}metadata" || exit 45
            cat "${'$'}previous" > "${'$'}target" || exit 45
            cmp -s "${'$'}previous" "${'$'}target" || exit 45
            actual=${'$'}(stat -c '%u:%g:%a:%C:%d:%i:%h' "${'$'}target") || exit 45
            test "${'$'}actual" = "${'$'}metadata" || exit 45
            exit 44
        }
        trap recover EXIT HUP INT TERM
        cat "${'$'}source" > "${'$'}target" || exit 44
        cmp -s "${'$'}source" "${'$'}target" || exit 44
        validate_target || exit 44
        actual=${'$'}(stat -c '%u:%g:%a:%C:%d:%i:%h' "${'$'}target") || exit 44
        test "${'$'}actual" = "${'$'}metadata" || exit 44
        trap - EXIT HUP INT TERM
        """.trimIndent()
