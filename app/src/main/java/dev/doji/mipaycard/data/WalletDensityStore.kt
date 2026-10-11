package dev.doji.mipaycard.data

import android.content.Context
import java.io.IOException

/** Persist verified calibration before a picker or our writer can remove wallet activities. */
internal class WalletDensityStore(
    context: Context,
) {
    private val preferences = context.getSharedPreferences("wallet-density", Context.MODE_PRIVATE)

    fun resolve(
        observation: WalletDensityObservation,
        signature: String,
    ): Int? {
        val previous =
            preferences.getString("signature", null)?.let { savedSignature ->
                val density = preferences.getInt("density", 0)
                if (density > 0) WalletDensityEvidence(savedSignature, density) else null
            }
        val current = reconcileWalletDensity(observation, signature, previous)
        if (current != previous) {
            val editor = preferences.edit().clear()
            if (current != null) {
                editor.putString("signature", current.signature).putInt("density", current.densityDpi)
            }
            // The next root mutation can stop the wallet. Calibration must already be durable.
            if (!editor.commit()) throw IOException("无法保存银行卡显示配置，请检查应用存储空间")
        }
        return current?.densityDpi
    }
}
