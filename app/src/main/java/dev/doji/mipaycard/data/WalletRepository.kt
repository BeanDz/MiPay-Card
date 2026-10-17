package dev.doji.mipaycard.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
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
    private val densityPreferences = this.context.getSharedPreferences("wallet-density", Context.MODE_PRIVATE)

    suspend fun prepareReplacement(
        expected: CachedImage,
        uri: Uri,
    ): Bitmap =
        withContext(Dispatchers.IO) {
            val original = backupFile(expected.key)
            if (original.exists()) return@withContext prepareCardImage(context, expected, uri, original)
            val files = RootWalletFiles(context)
            val current = files.read(expected.key)
            try {
                prepareCardImage(context, expected, uri, current)
            } finally {
                Files.deleteIfExists(current.toPath())
            }
        }

    suspend fun replace(
        card: WalletCard,
        bitmap: Bitmap,
    ): CachedImage =
        withContext(Dispatchers.IO) {
            val image = (card.face as CardFace.Editable).image
            val replacement = File.createTempFile("replacement-", ".png", context.cacheDir)
            try {
                saveCardImage(bitmap, replacement)
                writeImage(card, replacement, restoring = false)
                image.copy(preview = bitmap)
            } finally {
                Files.deleteIfExists(replacement.toPath())
            }
        }

    suspend fun restore(card: WalletCard): CachedImage =
        withContext(Dispatchers.IO) {
            val image = (card.face as CardFace.Editable).image
            val original = backupFile(image.key)
            val restored = readCachedImage(image.key, original)
            writeImage(card, original, restoring = true)
            restored
        }

    suspend fun scan(): List<WalletCard> =
        withContext(Dispatchers.IO) {
            val profile = WalletProfile(context)
            val files = RootWalletFiles(context)
            val cards = parseWalletCards(files.records(), profile.useThemeArt, profile.themeLogoType)
            if (cards.isEmpty()) return@withContext emptyList()
            val sources = readImageSources(cards, profile, files)
            val keys = files.cacheKeys()
            val sharedSources = sharedImageSources(sources)
            cards.mapIndexed { index, card ->
                currentCoroutineContext().ensureActive()
                val source = sources[index]
                val problem = card.problem ?: unsupportedArtReason(card.art, source)
                val face =
                    if (problem == null) {
                        resolveFace(files, source!!, keys, sharedSources)
                    } else {
                        CardFace.Unavailable(problem)
                    }
                val notificationAid = card.aid.takeIf { card.kind == CardKind.Transit || card.kind == CardKind.Door }
                val notificationImageKeys =
                    source
                        ?.takeIf { card.kind == CardKind.Transit || card.kind == CardKind.Door || card.kind == CardKind.CarKey }
                        ?.let(::walletNotificationImageKeys)
                WalletCard(card.id, card.name, card.kind, face, notificationAid, notificationImageKeys)
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

    private fun readImageSources(
        cards: List<WalletCardInfo>,
        profile: WalletProfile,
        files: RootWalletFiles,
    ): List<String?> {
        val bankSize =
            if (cards.any { it.art is CardArt.BankIdentifier }) {
                val signature = JSONArray(listOf(profile.densitySignature, files.displayResolutionOverride())).toString()
                resolveDensity(files.resourceDensity(), signature)?.let(profile::bankImageSize)
            } else {
                null
            }
        return cards.map { card ->
            when (val art = card.art) {
                is CardArt.Online -> {
                    art.value
                }

                is CardArt.BankIdentifier -> {
                    bankSize?.let { size ->
                        "https://did-cdn.pay.xiaomi.com/mfc/thumbnail/webp/w${size.width}h${size.height}q80/${art.value}"
                    }
                }

                else -> {
                    null
                }
            }
        }
    }

    private fun resolveFace(
        files: RootWalletFiles,
        url: String,
        keys: Set<String>,
        sharedSources: Set<String>,
    ): CardFace {
        val shared = walletImageSourceIdentity(url, ImageModel.CustomUrl) in sharedSources
        var failure: String? = null
        for (model in listOf(ImageModel.CustomUrl, ImageModel.PlainUrl)) {
            for (key in walletImageCandidates(url, model)) {
                if (key !in keys) continue
                val image =
                    readImage(files, key).getOrElse { error ->
                        failure = error.message ?: "无法解码卡面缓存"
                        null
                    } ?: continue
                if (shared) return CardFace.Unavailable("多张卡共用这张卡面，暂不支持单独更换", image.preview)
                if (model == ImageModel.PlainUrl) {
                    return CardFace.Unavailable(
                        failure ?: "仅匹配到普通 URL 卡面缓存，尚未匹配刷卡页请求；请打开刷卡页面后刷新",
                        image.preview,
                    )
                }
                return CardFace.Editable(image, backupFile(key).isFile)
            }
        }
        val reason =
            if (shared) {
                "多张卡共用这张卡面，暂不支持单独更换"
            } else {
                failure ?: "未找到刷卡页卡面缓存，请先打开这张卡的刷卡页面后刷新"
            }
        return CardFace.Unavailable(reason)
    }

    private fun readImage(
        files: RootWalletFiles,
        key: String,
    ): Result<CachedImage> {
        // Root failures abort the scan; a corrupt image only disables its own card.
        val current = files.read(key)
        return try {
            Result.success(readCachedImage(key, current))
        } catch (error: IOException) {
            Result.failure(error)
        } finally {
            Files.deleteIfExists(current.toPath())
        }
    }

    private fun writeImage(
        card: WalletCard,
        replacement: File,
        restoring: Boolean,
    ) {
        val files = RootWalletFiles(context)
        val image = (card.face as CardFace.Editable).image
        if (!restoring) saveOriginal(files, image.key)
        val writes = mutableListOf(CacheWrite(image.key, replacement.path))
        val notification = card.notificationImageKeys
        if (notification != null) {
            val original = backupFile(notification.data)
            if (restoring) {
                writes.add(CacheWrite(notification.data, if (original.exists()) original.path else replacement.path))
            } else {
                // Glide ignores files created without an existing journal entry.
                if (notification.data !in files.cacheKeys()) {
                    throw IOException("通知卡面缓存缺失，请先触发通知或在小米智能卡中重新加载卡面")
                }
                saveOriginal(files, notification.data)
                writes.add(CacheWrite(notification.data, replacement.path))
            }
        }
        files.write(writes, notification?.resource)
    }

    private fun saveOriginal(
        files: RootWalletFiles,
        key: String,
    ) {
        val original = backupFile(key)
        if (original.exists()) return
        if (!originals.isDirectory && !originals.mkdirs()) throw IOException("无法创建原始卡面备份目录")
        val current = files.read(key)
        try {
            current.copyTo(original)
        } finally {
            Files.deleteIfExists(current.toPath())
        }
    }

    private fun backupFile(key: String) = File(originals, "$key.original")
}

private fun sharedImageSources(sources: List<String?>): Set<String> =
    sources
        .filterNotNull()
        .groupingBy { walletImageSourceIdentity(it, ImageModel.CustomUrl) }
        .eachCount()
        .filterValues { it > 1 }
        .keys

private fun unsupportedArtReason(
    art: CardArt,
    source: String?,
): String? =
    when {
        art == CardArt.Native -> "刷卡页使用内置卡面，暂不支持更换"
        art == CardArt.Animated -> "刷卡页使用动态卡面，暂不支持更换"
        art is CardArt.BankIdentifier && source == null -> "尚未读取小米智能卡的实际显示密度，请先打开智能卡首页或卡片列表后刷新"
        source == null -> "未找到刷卡页的在线卡面地址"
        else -> null
    }
