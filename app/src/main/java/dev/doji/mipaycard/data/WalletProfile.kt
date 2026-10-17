package dev.doji.mipaycard.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.util.Size
import android.view.Display
import org.json.JSONArray
import java.io.IOException

internal const val WALLET_PACKAGE = "com.miui.tsmclient"

/** Read the installed wallet's resources without restricting its version. */
internal class WalletProfile(
    context: Context,
) {
    private val walletContext: Context
    val dataDirectory: String
    val useThemeArt: Boolean
    val themeLogoType: String
    val densitySignature: String

    init {
        val packageInfo =
            try {
                context.packageManager.getPackageInfo(WALLET_PACKAGE, 0)
            } catch (error: PackageManager.NameNotFoundException) {
                throw IOException("未找到小米智能卡应用", error)
            }
        dataDirectory = packageInfo.applicationInfo?.dataDir ?: throw IOException("无法读取小米智能卡的数据目录")
        walletContext =
            try {
                context.createPackageContext(WALLET_PACKAGE, 0)
            } catch (error: PackageManager.NameNotFoundException) {
                throw IOException("无法读取小米智能卡资源，请检查安装状态", error)
            }
        val resources = walletContext.resources
        try {
            useThemeArt = resources.getBoolean(resources.walletResource("use_theme_card_art", "bool"))
            themeLogoType = resources.getString(resources.walletResource("use_theme_card_logo_type", "string"))
        } catch (error: Resources.NotFoundException) {
            throw IOException("当前小米智能卡版本不适配：缺少更换卡面所需的资源", error)
        }
        val display =
            context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                ?: throw IOException("无法读取主屏显示配置")
        densitySignature =
            JSONArray(
                listOf(
                    packageInfo.lastUpdateTime,
                    Settings.Global.getString(context.contentResolver, Settings.Global.BOOT_COUNT),
                    context.resources.configuration.densityDpi,
                    display.mode.physicalWidth,
                    display.mode.physicalHeight,
                    Settings.Secure.getString(context.contentResolver, "display_density_forced"),
                    Settings.System.getString(context.contentResolver, "key_screen_zoom_level"),
                ),
            ).toString()
    }

    fun bankImageSize(densityDpi: Int): Size {
        val configuration = Configuration(walletContext.resources.configuration)
        configuration.densityDpi = densityDpi
        val resources = walletContext.createConfigurationContext(configuration).resources
        return try {
            Size(
                resources.getDimensionPixelSize(resources.walletResource("trade_alert_card_image_width", "dimen")),
                resources.getDimensionPixelSize(resources.walletResource("trade_alert_card_image_height", "dimen")),
            )
        } catch (error: Resources.NotFoundException) {
            throw IOException("当前小米智能卡版本不适配：缺少更换卡面所需的资源", error)
        }
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
