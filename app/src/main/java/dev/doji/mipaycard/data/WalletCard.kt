package dev.doji.mipaycard.data

import android.graphics.Bitmap

internal data class CachedImage(
    val key: String,
    val width: Int,
    val height: Int,
    val fingerprint: String,
    val preview: Bitmap,
)

internal sealed interface CardFace {
    data class Editable(
        val image: CachedImage,
        val hasBackup: Boolean,
    ) : CardFace

    data class Unavailable(
        val reason: String,
        val preview: Bitmap? = null,
    ) : CardFace
}

internal data class WalletCard(
    val info: WalletCardInfo,
    val face: CardFace,
)
