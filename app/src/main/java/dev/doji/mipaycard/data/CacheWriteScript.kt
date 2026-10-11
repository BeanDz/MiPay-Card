package dev.doji.mipaycard.data

/** Root-side validation and recovery keep a failed write in one shell job. */
internal object CacheWriteScript {
    private val keyPattern = Regex("[0-9a-f]{64}")

    fun validKey(key: String): Boolean = keyPattern.matches(key)

    fun quote(value: String): String = "'${value.replace("'", "'\\''")}'"

    fun directoryGuard(walletDataDir: String): String =
        """
        wallet=${quote(walletDataDir)}
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

    fun targetGuard(
        walletDataDir: String,
        key: String,
    ): String {
        require(validKey(key)) { "Invalid cache key" }
        return directoryGuard(walletDataDir) +
            "\n" +
            """
            key=${quote(key)}
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

    fun write(
        walletDataDir: String,
        key: String,
        source: String,
        previous: String,
    ): String =
        targetGuard(walletDataDir, key) +
            "\n" +
            """
            source=${quote(source)}
            previous=${quote(previous)}
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
}
