package dev.doji.mipaycard.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Owned by one ViewModel, which accepts one operation at a time before launching IO work. */
internal class WalletRepository(
    context: Context,
) {
    private val context = context.applicationContext
    private val originals = File(this.context.filesDir, "originals")
    private val images = CardImages(this.context)
    private val densityPreferences = this.context.getSharedPreferences("wallet-density", Context.MODE_PRIVATE)
    private val files by lazy { RootWalletFiles(this.context, WalletProfile.read(this.context).dataDirectory) }

    suspend fun scan(): List<WalletCard> =
        withContext(Dispatchers.IO) {
            scanCards()
        }

    suspend fun prepareReplacement(
        cardId: String,
        expected: CachedImage,
        uri: Uri,
    ): Bitmap =
        withContext(Dispatchers.IO) {
            currentImage(cardId, expected)
            val original = backupFile(expected.key)
            if (original.exists()) return@withContext images.prepare(expected, uri, original)
            val current = files.read(expected.key)
            try {
                requireFingerprint(expected, current)
                images.prepare(expected, uri, current)
            } finally {
                Files.deleteIfExists(current.toPath())
            }
        }

    suspend fun replace(
        cardId: String,
        expected: CachedImage,
        bitmap: Bitmap,
    ) = withContext(Dispatchers.IO) {
        val image = currentImage(cardId, expected)
        if (bitmap.width != image.width || bitmap.height != image.height) throw IOException("新卡面的尺寸与原卡面不一致")
        val replacement = File.createTempFile("replacement-", ".png", context.cacheDir)
        try {
            images.save(bitmap, replacement)
            mutate(image, replacement, saveBackup = true)
        } finally {
            Files.deleteIfExists(replacement.toPath())
        }
    }

    suspend fun restore(
        cardId: String,
        expected: CachedImage,
    ) = withContext(Dispatchers.IO) {
        val image = currentImage(cardId, expected)
        val original = backupFile(image.key)
        if (!original.isFile) throw IOException("这张卡还没有原始卡面备份")
        images.validate(original, image.width, image.height)
        mutate(image, original, saveBackup = false)
    }

    private suspend fun scanCards(): List<WalletCard> {
        var profile = WalletProfile.read(context)
        val cards = parseWalletCards(files.records(), profile.useThemeArt, profile.themeLogoType)
        if (cards.isEmpty()) return emptyList()
        if (profile.problem != null) return cards.map { WalletCard(it, CardFace.Unavailable(profile.problem)) }
        if (cards.any { it.art is CardArt.BankIdentifier }) {
            val resolutionOverride = files.displayResolutionOverride()
            val observation = files.resourceDensity()
            val currentProfile = WalletProfile.read(context)
            val stableObservation =
                if (profile.densitySignature == currentProfile.densitySignature &&
                    resolutionOverride == files.displayResolutionOverride()
                ) {
                    observation
                } else {
                    WalletDensityObservation.Invalid
                }
            val signature = JSONArray(listOf(currentProfile.densitySignature, resolutionOverride)).toString()
            val density = resolveDensity(stableObservation, signature)
            profile = WalletProfile.read(context, density)
        }

        val keys = files.keys()
        val sources = cards.map { card -> swipeImageUrl(card.art, profile.bankWidth, profile.bankHeight) }
        val sharedSources =
            sources
                .filterNotNull()
                .groupingBy { WalletImageKeys.sourceIdentity(it) }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        return cards.mapIndexed { index, card ->
            currentCoroutineContext().ensureActive()
            val face =
                when {
                    card.problem != null -> {
                        CardFace.Unavailable(card.problem)
                    }

                    card.art == CardArt.Native -> {
                        CardFace.Unavailable("刷卡页使用内置卡面，暂不支持更换")
                    }

                    card.art == CardArt.Animated -> {
                        CardFace.Unavailable("刷卡页使用动态卡面，暂不支持更换")
                    }

                    card.art is CardArt.BankIdentifier && profile.bankWidth == 0 -> {
                        CardFace.Unavailable("尚未读取小米智能卡的实际显示密度，请先打开智能卡首页或卡片列表后刷新")
                    }

                    sources[index] == null -> {
                        CardFace.Unavailable("未找到刷卡页的在线卡面地址")
                    }

                    else -> {
                        resolveFace(sources[index]!!, keys, sharedSources)
                    }
                }
            WalletCard(card, face)
        }
    }

    private fun resolveDensity(
        observation: WalletDensityObservation,
        signature: String,
    ): Int? {
        val previous =
            densityPreferences.getString("signature", null)?.let { savedSignature ->
                val density = densityPreferences.getInt("density", 0)
                if (density > 0) WalletDensityEvidence(savedSignature, density) else null
            }
        val current = reconcileWalletDensity(observation, signature, previous)
        if (current != previous) {
            val editor = densityPreferences.edit().clear()
            if (current != null) {
                editor.putString("signature", current.signature).putInt("density", current.densityDpi)
            }
            // The next root mutation can stop the wallet. Calibration must already be durable.
            if (!editor.commit()) throw IOException("无法保存银行卡显示配置，请检查应用存储空间")
        }
        return current?.densityDpi
    }

    private fun resolveFace(
        url: String,
        keys: Set<String>,
        sharedSources: Set<String>,
    ): CardFace {
        val shared = WalletImageKeys.sourceIdentity(url) in sharedSources
        var failure: IOException? = null
        for (key in WalletImageKeys.candidates(url, ImageModel.CustomUrl)) {
            if (key !in keys) continue
            val image =
                try {
                    readImage(key)
                } catch (error: ImageCacheException) {
                    failure = error
                    continue
                }
            if (shared) return CardFace.Unavailable("多张卡共用这张卡面，暂不支持单独更换", image.preview)
            val original = backupFile(key)
            try {
                if (original.exists()) images.validate(original, image.width, image.height)
            } catch (error: IOException) {
                return CardFace.Unavailable(error.message ?: "原始卡面备份已损坏", image.preview)
            }
            return CardFace.Editable(image, original.isFile)
        }

        var preview: Bitmap? = null
        for (key in WalletImageKeys.candidates(url, ImageModel.PlainUrl)) {
            if (key !in keys) continue
            try {
                preview = readImage(key).preview
                break
            } catch (error: ImageCacheException) {
                failure = error
            }
        }
        val reason =
            when {
                shared -> "多张卡共用这张卡面，暂不支持单独更换"
                failure != null -> failure.message ?: "无法解码卡面缓存"
                preview != null -> "仅匹配到普通 URL 卡面缓存，尚未匹配刷卡页请求；请打开刷卡页面后刷新"
                else -> "未找到刷卡页卡面缓存，请先打开这张卡的刷卡页面后刷新"
            }
        return CardFace.Unavailable(reason, preview)
    }

    private fun readImage(key: String): CachedImage {
        val current = files.read(key)
        try {
            return try {
                images.read(key, current)
            } catch (error: IOException) {
                throw ImageCacheException(error.message ?: "无法解码卡面缓存", error)
            }
        } finally {
            Files.deleteIfExists(current.toPath())
        }
    }

    private suspend fun currentImage(
        cardId: String,
        expected: CachedImage,
    ): CachedImage {
        val card = scanCards().find { it.info.id == cardId } ?: throw IOException("这张卡已不在刷卡列表中，请刷新后重试")
        val face = card.face
        if (face is CardFace.Unavailable) throw IOException(face.reason)
        val image = (face as CardFace.Editable).image
        if (image.key != expected.key || image.fingerprint != expected.fingerprint || image.width != expected.width ||
            image.height != expected.height
        ) {
            throw IOException("这张卡的卡面已发生变化，请刷新后重新选择图片")
        }
        return image
    }

    private suspend fun mutate(
        image: CachedImage,
        replacement: File,
        saveBackup: Boolean,
    ) {
        currentCoroutineContext().ensureActive()
        // Once commit starts, cancellation must not separate the write from rollback and cleanup.
        withContext(NonCancellable) {
            val current = files.read(image.key)
            try {
                requireFingerprint(image, current)
                images.validate(current, image.width, image.height)
                if (saveBackup) saveOriginal(image, current)
                files.write(image.key, replacement, current)
            } finally {
                Files.deleteIfExists(current.toPath())
            }
        }
    }

    private fun saveOriginal(
        image: CachedImage,
        current: File,
    ) {
        val original = backupFile(image.key)
        if (original.exists()) {
            images.validate(original, image.width, image.height)
            return
        }
        if (!originals.isDirectory && !originals.mkdirs()) throw IOException("无法创建原始卡面备份目录")
        val staging = File.createTempFile("original-", ".cache", originals)
        try {
            current.inputStream().use { input ->
                staging.outputStream().use { output -> input.copyTo(output) }
            }
            images.validate(staging, image.width, image.height)
            Files.move(staging.toPath(), original.toPath())
        } finally {
            Files.deleteIfExists(staging.toPath())
        }
    }

    private fun requireFingerprint(
        image: CachedImage,
        current: File,
    ) {
        if (fingerprint(current) != image.fingerprint) throw IOException("卡面缓存已发生变化，请刷新后重试")
    }

    private fun backupFile(key: String) = File(originals, "$key.original")

    private class ImageCacheException(
        message: String,
        cause: Throwable,
    ) : IOException(message, cause)
}

internal fun swipeImageUrl(
    art: CardArt,
    bankWidth: Int,
    bankHeight: Int,
): String? =
    when (art) {
        is CardArt.Online -> art.value
        is CardArt.BankIdentifier -> "https://did-cdn.pay.xiaomi.com/mfc/thumbnail/webp/w${bankWidth}h${bankHeight}q80/${art.value}"
        else -> null
    }
