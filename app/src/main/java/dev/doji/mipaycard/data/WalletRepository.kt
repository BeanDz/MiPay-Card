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
    private val densityPreferences = this.context.getSharedPreferences("wallet-density", Context.MODE_PRIVATE)

    suspend fun prepareReplacement(
        expected: CachedImage,
        uri: Uri,
    ): Bitmap =
        withContext(Dispatchers.IO) {
            val original = backupFile(expected.key)
            if (original.exists()) return@withContext prepareCardImage(context, expected, uri, original)
            val profile = WalletProfile(context)
            val files = RootWalletFiles(context.cacheDir, profile.dataDirectory)
            val current = files.read(expected.key)
            try {
                requireFingerprint(expected, current)
                prepareCardImage(context, expected, uri, current)
            } finally {
                Files.deleteIfExists(current.toPath())
            }
        }

    suspend fun replace(
        cardId: String,
        expected: CachedImage,
        bitmap: Bitmap,
    ): CachedImage =
        withContext(Dispatchers.IO) {
            if (bitmap.width != expected.width || bitmap.height != expected.height) {
                throw IOException("新卡面的尺寸与原卡面不一致")
            }
            val files = validatedFiles(cardId, expected)
            val replacement = File.createTempFile("replacement-", ".png", context.cacheDir)
            try {
                val replacementFingerprint = saveCardImage(bitmap, replacement)
                writeImage(files, expected, replacement)
                CachedImage(expected.key, expected.width, expected.height, replacementFingerprint, bitmap)
            } finally {
                Files.deleteIfExists(replacement.toPath())
            }
        }

    suspend fun restore(
        cardId: String,
        expected: CachedImage,
    ): CachedImage =
        withContext(Dispatchers.IO) {
            val files = validatedFiles(cardId, expected)
            val original = backupFile(expected.key)
            if (!original.isFile) throw IOException("这张卡还没有原始卡面备份")
            val restored = readCachedImage(expected.key, original)
            if (restored.width != expected.width || restored.height != expected.height) {
                throw IOException("卡面尺寸已变化，请刷新后重试")
            }
            writeImage(files, expected, original)
            restored
        }

    suspend fun scan(): List<WalletCard> =
        withContext(Dispatchers.IO) {
            val profile = WalletProfile(context)
            val files = RootWalletFiles(context.cacheDir, profile.dataDirectory)
            val cards = parseWalletCards(files.records(), profile.useThemeArt, profile.themeLogoType)
            if (cards.isEmpty()) return@withContext emptyList()
            val sources = readImageSources(cards, profile, files)
            val candidates =
                sources
                    .filterNotNull()
                    .flatMap { url ->
                        walletImageCandidates(url, ImageModel.CustomUrl) + walletImageCandidates(url, ImageModel.PlainUrl)
                    }.toSet()
            val keys = files.keys(candidates)
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
                WalletCard(card.id, card.name, card.kind, face)
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
                val original = backupFile(key)
                try {
                    if (original.exists()) validateCardImage(original, image.width, image.height)
                } catch (error: IOException) {
                    return CardFace.Unavailable(error.message ?: "原始卡面备份已损坏", image.preview)
                }
                return CardFace.Editable(image, original.isFile)
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

    private fun validatedFiles(
        cardId: String,
        expected: CachedImage,
    ): RootWalletFiles {
        val profile = WalletProfile(context)
        val files = RootWalletFiles(context.cacheDir, profile.dataDirectory)
        val cards = parseWalletCards(files.records(), profile.useThemeArt, profile.themeLogoType)
        val cardIndex = cards.indexOfFirst { it.id == cardId }
        if (cardIndex < 0) throw IOException("这张卡已不在刷卡列表中，请刷新后重试")
        val card = cards[cardIndex]
        if (card.problem != null) throw IOException(card.problem)
        val sources = readImageSources(cards, profile, files)
        val source = sources[cardIndex]
        val problem = unsupportedArtReason(card.art, source)
        if (problem != null) throw IOException(problem)
        val sourceIdentity = walletImageSourceIdentity(source!!, ImageModel.CustomUrl)
        if (sourceIdentity in sharedImageSources(sources)) {
            throw IOException("多张卡共用这张卡面，暂不支持单独更换")
        }
        if (expected.key !in walletImageCandidates(source, ImageModel.CustomUrl)) {
            throw IOException("这张卡的卡面已发生变化，请刷新后重新选择图片")
        }
        return files
    }

    private suspend fun writeImage(
        files: RootWalletFiles,
        image: CachedImage,
        replacement: File,
    ) {
        currentCoroutineContext().ensureActive()
        // Once commit starts, cancellation must not separate the write from rollback and cleanup.
        withContext(NonCancellable) {
            val current = files.read(image.key)
            try {
                requireFingerprint(image, current)
                saveOriginal(image, current)
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
            validateCardImage(original, image.width, image.height)
            return
        }
        if (!originals.isDirectory && !originals.mkdirs()) throw IOException("无法创建原始卡面备份目录")
        val staging = File.createTempFile("original-", ".cache", originals)
        try {
            current.copyTo(staging, overwrite = true)
            validateCardImage(staging, image.width, image.height)
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
