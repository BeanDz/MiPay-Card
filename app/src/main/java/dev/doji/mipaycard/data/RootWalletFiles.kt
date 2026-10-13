package dev.doji.mipaycard.data

import android.os.Process
import com.topjohnwu.superuser.NoShellException
import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Callers run root work off the UI thread and own every returned temporary file. */
internal class RootWalletFiles(
    private val cacheDir: File,
    private val walletDataDir: String,
) {
    fun displayResolutionOverride(): String =
        command(
            "getprop persist.sys.miui_resolution 2>&1",
            "无法读取小米智能卡的屏幕分辨率配置",
        ).joinToString("\n")

    fun resourceDensity(): WalletDensityObservation =
        walletDensityObservation(
            command(
                "dumpsys activity com.miui.tsmclient/ 2>&1",
                "无法读取小米智能卡的实际显示密度",
            ),
        )

    fun records(): List<WalletRecord> =
        parseWalletProviderRows(
            command(
                "content query --uri content://com.miui.tsmclient.provider/cache --projection key:value 2>&1",
                "无法读取小米智能卡的卡片记录",
            ),
        )

    fun keys(candidates: Set<String>): Set<String> {
        if (candidates.isEmpty()) return emptySet()
        val output =
            command(
                cacheCandidateKeysScript(walletDataDir, candidates),
                "无法读取小米智能卡缓存，缓存路径可能已失效",
            )
        return output.filter(::validCacheKey).toSet()
    }

    fun read(key: String): File {
        validateKey(key)
        val temporary = File.createTempFile("wallet-", ".cache", cacheDir)
        try {
            command(
                cacheTargetGuard(walletDataDir, key) +
                    "\n" +
                    """
                    validate_target || exit 41
                    cat "${'$'}target" > ${quoteShell(temporary.path)} &&
                    chown ${Process.myUid()}:${Process.myUid()} ${quoteShell(temporary.path)} &&
                    chmod 600 ${quoteShell(temporary.path)}
                    """.trimIndent(),
                "无法读取卡面缓存，请重新扫描",
            )
            return temporary
        } catch (error: IOException) {
            Files.deleteIfExists(temporary.toPath())
            throw error
        }
    }

    fun write(
        key: String,
        source: File,
        previous: File,
    ) {
        validateKey(key)
        command(
            cacheWriteScript(walletDataDir, key, source.path, previous.path),
            "无法写入卡面缓存",
            transaction = true,
        )
    }

    private fun validateKey(key: String) {
        if (!validCacheKey(key)) throw IOException("无效的卡面缓存标识")
    }

    private fun command(
        script: String,
        description: String,
        transaction: Boolean = false,
    ): List<String> {
        val cached = Shell.getCachedShell()
        if (cached != null && !cached.isRoot) {
            try {
                cached.close()
            } catch (error: IOException) {
                throw IOException("无法重试 root 授权，请重新打开应用", error)
            }
        }
        val shell =
            try {
                Shell.getShell()
            } catch (error: NoShellException) {
                throw IOException("无法启动 root shell，请检查 su 授权", error)
            }
        if (!shell.isRoot) {
            throw IOException("应用未获得 root 权限，请在 root 管理器中授权；ADB root 无法替代应用 su 授权")
        }
        val output = mutableListOf<String>()
        val result =
            shell
                .newJob()
                .add("(\n$script\n)")
                .to(output, null)
                .exec()
        if (!result.isSuccess) {
            val reason =
                if (transaction) {
                    transactionFailures[result.code] ?: description
                } else {
                    description
                }
            // Provider output and root diagnostics can contain private wallet data.
            throw IOException("$reason（退出码 ${result.code}）")
        }
        return output
    }

    private companion object {
        val transactionFailures =
            mapOf(
                41 to "缓存路径不安全或已失效，请重新扫描",
                42 to "卡面缓存已发生变化，请重新选择图片",
                43 to "无法确认卡面缓存的文件属性，未写入",
                44 to "写入失败，已自动回滚到操作前的卡面",
                45 to "写入失败，自动回滚也失败；原始备份仍保留在应用中",
                46 to "无法停止小米智能卡，未写入",
            )

        init {
            Shell.setDefaultBuilder(
                Shell.Builder
                    .create()
                    .setFlags(Shell.FLAG_MOUNT_MASTER)
                    .setTimeout(20),
            )
        }
    }
}
