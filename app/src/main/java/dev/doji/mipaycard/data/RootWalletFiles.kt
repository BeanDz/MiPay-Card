package dev.doji.mipaycard.data

import android.content.Context
import android.content.pm.PackageManager
import com.topjohnwu.superuser.NoShellException
import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Callers run root work off the UI thread and own every returned temporary file. */
internal class RootWalletFiles(
    context: Context,
) {
    private val cacheDir = context.cacheDir
    private val walletDataDir =
        try {
            context.packageManager.getApplicationInfo(WALLET_PACKAGE, 0).dataDir
        } catch (error: PackageManager.NameNotFoundException) {
            throw IOException("未找到小米智能卡应用", error)
        }

    fun displayResolutionOverride(): String =
        command(
            "getprop persist.sys.miui_resolution 2>&1",
            "无法读取小米智能卡的屏幕分辨率配置",
        ).joinToString("\n")

    fun resourceDensity(): WalletDensityObservation =
        walletDensityObservation(
            command(
                "dumpsys activity $WALLET_PACKAGE/ 2>&1",
                "无法读取小米智能卡的实际显示密度",
            ),
        )

    fun records(): List<WalletRecord> =
        parseWalletProviderRows(
            command(
                "content query --uri content://$WALLET_PACKAGE.provider/cache --projection key:value 2>&1",
                "无法读取小米智能卡的卡片记录",
            ),
        )

    fun cacheKeys(): Set<String> =
        parseCacheKeys(
            command(
                cacheListScript(walletDataDir),
                "无法读取小米智能卡缓存，缓存路径可能已失效",
            ),
        )

    fun read(key: String): File {
        val temporary = File.createTempFile("wallet-", ".cache", cacheDir)
        try {
            command(
                "cat ${quoteShell("$walletDataDir/cache/image_manager_disk_cache/$key.0")} > ${quoteShell(temporary.path)}",
                "无法读取卡面缓存，请重新扫描",
            )
            return temporary
        } catch (error: IOException) {
            Files.deleteIfExists(temporary.toPath())
            throw error
        }
    }

    fun write(
        writes: List<CacheWrite>,
        invalidation: String?,
    ) {
        command(
            cacheWriteScript(walletDataDir, writes, invalidation),
            "无法写入卡面缓存",
        )
    }

    companion object {
        fun previewNotification(aid: String) {
            command(
                notificationPreviewScript(aid),
                "无法预览通知，请确认已开启简洁刷卡，并在钱包中选中了这张卡",
            )
        }

        private fun command(
            script: String,
            description: String,
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
                // Provider output and root diagnostics can contain private wallet data.
                throw IOException("$description（退出码 ${result.code}）")
            }
            return output
        }

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
