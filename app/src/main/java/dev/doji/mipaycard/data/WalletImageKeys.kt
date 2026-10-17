package dev.doji.mipaycard.data

import java.math.BigInteger
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest

internal enum class ImageModel {
    CustomUrl,
    PlainUrl,
}

/** Matches DATA and original-size RESOURCE keys for the selected wallet image model. */
internal fun walletImageCandidates(
    url: String,
    model: ImageModel,
): List<String> {
    val source = walletImageSourceIdentity(url, model).toByteArray(Charsets.UTF_8)
    val originalSize =
        ByteBuffer
            .allocate(8)
            .putInt(Int.MIN_VALUE)
            .putInt(Int.MIN_VALUE)
            .array()
    // Glide hashes the decoded class before transcoding Bitmap to the requested Drawable.
    val resourceClasses = listOf("android.graphics.drawable.BitmapDrawable", "android.graphics.Bitmap")
    // EmptySignature and the default transformation/options contribute no bytes.
    return resourceClasses.map { resourceClass ->
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(source)
        digest.update(originalSize)
        digest.update(resourceClass.toByteArray(Charsets.UTF_8))
        digest.digest().toHexString()
    } + MessageDigest.getInstance("SHA-256").digest(source).toHexString()
}

internal fun walletImageSourceIdentity(
    url: String,
    model: ImageModel,
): String {
    val secureUrl = if (url.startsWith("http://")) url.replace("http://", "https://") else url
    if (model == ImageModel.PlainUrl) return secureUrl

    // CustomGlideUrl applies h4's HTTPS upgrade before its MD5 filename generator.
    // The generator's HTTP-only market prefix cannot match after that upgrade.
    val normalizedUrl = clearSignedQuery(secureUrl)
    val digest = MessageDigest.getInstance("MD5").digest(normalizedUrl.toByteArray(Charsets.UTF_8))
    return BigInteger(digest).abs().toString(36)
}

private fun clearSignedQuery(url: String): String {
    val query = URI(url).rawQuery ?: return url
    val hasSignature =
        query.split('&').any { parameter ->
            // Android Uri.getQueryParameter compares the encoded key, not a decoded key.
            parameter.substringBefore('=') == "GalaxyAccessKeyId"
        }
    if (!hasSignature) return url

    val queryStart = url.indexOf('?')
    val fragmentStart = url.indexOf('#', queryStart)
    val fragment = if (fragmentStart < 0) "" else url.substring(fragmentStart)
    return url.substring(0, queryStart) + fragment
}
