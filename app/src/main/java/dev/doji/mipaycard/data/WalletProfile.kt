package dev.doji.mipaycard.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.view.Display
import org.json.JSONArray
import java.io.IOException

internal const val WALLET_PACKAGE = "com.miui.tsmclient"

/** The external resource contract is pinned to the verified wallet version below. */
internal data class WalletProfile(
    val dataDirectory: String,
    val bankWidth: Int,
    val bankHeight: Int,
    val useThemeArt: Boolean,
    val themeLogoType: String,
    val problem: String? = null,
    val densitySignature: String = "",
)

internal fun readWalletProfile(
    context: Context,
    bankDensityDpi: Int? = null,
): WalletProfile {
    val manager = context.packageManager
    val packageInfo =
        try {
            manager.getPackageInfo(WALLET_PACKAGE, 0)
        } catch (error: PackageManager.NameNotFoundException) {
            throw IOException("未找到小米智能卡应用", error)
        }
    val dataDirectory = packageInfo.applicationInfo?.dataDir ?: throw IOException("无法读取小米智能卡的数据目录")
    if (packageInfo.versionName != "26.08.28.4.f" || packageInfo.longVersionCode != 4180L) {
        return WalletProfile(dataDirectory, 0, 0, false, "", "当前小米智能卡版本尚未验证，暂不支持更换卡面")
    }
    val walletContext =
        try {
            context.createPackageContext(WALLET_PACKAGE, 0)
        } catch (error: PackageManager.NameNotFoundException) {
            throw IOException("无法读取小米智能卡资源，请检查安装状态", error)
        }
    val resources =
        if (bankDensityDpi == null) {
            walletContext.resources
        } else {
            val configuration = Configuration(walletContext.resources.configuration)
            configuration.densityDpi = bankDensityDpi
            walletContext.createConfigurationContext(configuration).resources
        }
    val display =
        context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            ?: throw IOException("无法读取主屏显示配置")
    val rawMetrics = context.resources.displayMetrics
    return try {
        WalletProfile(
            dataDirectory,
            if (bankDensityDpi ==
                null
            ) {
                0
            } else {
                resources.getDimensionPixelSize(resources.walletResource("trade_alert_card_image_width", "dimen"))
            },
            if (bankDensityDpi ==
                null
            ) {
                0
            } else {
                resources.getDimensionPixelSize(resources.walletResource("trade_alert_card_image_height", "dimen"))
            },
            resources.getBoolean(resources.walletResource("use_theme_card_art", "bool")),
            resources.getString(resources.walletResource("use_theme_card_logo_type", "string")),
            densitySignature =
                JSONArray(
                    listOf(
                        packageInfo.versionName,
                        packageInfo.longVersionCode,
                        packageInfo.lastUpdateTime,
                        dataDirectory,
                        Build.FINGERPRINT,
                        Settings.Global.getString(context.contentResolver, Settings.Global.BOOT_COUNT),
                        rawMetrics.densityDpi,
                        rawMetrics.xdpi,
                        rawMetrics.ydpi,
                        context.resources.configuration.fontScale,
                        display.mode.physicalWidth,
                        display.mode.physicalHeight,
                        display.supportedModes
                            .map { "${it.physicalWidth}x${it.physicalHeight}" }
                            .distinct()
                            .sorted(),
                        Settings.Secure.getString(context.contentResolver, "display_density_forced"),
                        Settings.System.getString(context.contentResolver, "key_screen_zoom_level"),
                    ),
                ).toString(),
        )
    } catch (error: Resources.NotFoundException) {
        throw IOException("无法读取已验证版本的卡面资源，请检查小米智能卡安装状态", error)
    }
}

// The wallet's R class is external to this app. Named lookup also respects its resource overlays.
@SuppressLint("DiscouragedApi")
private fun Resources.walletResource(
    name: String,
    type: String,
): Int {
    val id = getIdentifier(name, type, WALLET_PACKAGE)
    if (id == 0) throw Resources.NotFoundException("Missing wallet resource $type/$name")
    return id
}
