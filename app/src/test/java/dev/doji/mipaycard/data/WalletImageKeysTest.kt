package dev.doji.mipaycard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletImageKeysTest {
    // Independent digest fixtures use Glide's original-size RESOURCE and DATA byte sequences.
    @Test
    fun matchesDecodedResourceClassesThenDataWithSignedMd5Source() {
        val keys = walletImageCandidates("https://example.com/card.png", ImageModel.CustomUrl)

        // This URL's MD5 has its high bit set, so unsigned base36 gives the wrong wallet key.
        assertEquals(
            listOf(
                "e322c74db8018e2324eded494d9abeab133e975da6f81adfa25793066a1dbec5",
                "22f2708a8e77c8b28ec7a436fab37d933e5bed67819eca9308c505c374dfcf3f",
                "71e6436063f0df50a424afeb9bb6d8d2e93a2b18c04b3ee5165ec341ac2486ab",
            ),
            keys,
        )
    }

    @Test
    fun normalizesSignedHttpUrlWithoutChangingEscapedPathOrFragment() {
        val keys =
            walletImageCandidates(
                "http://example.com/cards/blue%20card.png?GalaxyAccessKeyId=token&Expires=10#face",
                ImageModel.CustomUrl,
            )

        assertEquals("adfc75a4fcca85aa119e2283f48a346851eb6ab4fd2bebf83d16fc76da2ff061", keys.first())
        assertEquals(
            walletImageCandidates("https://example.com/cards/blue%20card.png#face", ImageModel.CustomUrl),
            keys,
        )
    }

    @Test
    fun renewedSignaturesKeepTheCustomCacheIdentity() {
        val original =
            walletImageCandidates(
                "https://example.com/card.png?GalaxyAccessKeyId=first&Expires=10&Signature=old",
                ImageModel.CustomUrl,
            )
        val renewed =
            walletImageCandidates(
                "https://example.com/card.png?Expires=20&GalaxyAccessKeyId=second&Signature=new",
                ImageModel.CustomUrl,
            )

        assertEquals(walletImageCandidates("https://example.com/card.png", ImageModel.CustomUrl), original)
        assertEquals(original, renewed)
    }

    @Test
    fun preservesOrdinaryQueryParameters() {
        val keys = walletImageCandidates("https://example.com/card.png?v=2", ImageModel.CustomUrl)

        assertEquals("c90425b0f07fa9b57bdcd5a882edfe408dc7c61e2d49669e2cf0634076f895f6", keys.first())
        assertEquals("2fa37394b60e0c2b9b045e45591aef5f4cfb916a5715c98a62d1b79473b0c87d", keys.last())
    }

    @Test
    fun matchesAndroidUriEncodedKeyComparison() {
        val keys = walletImageCandidates("https://example.com/card.png?%47alaxyAccessKeyId=token", ImageModel.CustomUrl)

        assertEquals("bac0f37e34ea7ce14060cd140fdc763f490f1fea96617cd22b8807b208cddffd", keys.first())
    }

    @Test
    fun plainUrlUsesHttpsWithoutRemovingSignedQuery() {
        val keys =
            walletImageCandidates(
                "http://example.com/card.png?GalaxyAccessKeyId=token&Expires=10",
                ImageModel.PlainUrl,
            )

        assertEquals(
            listOf(
                "f9339fdbc2bf06257ad122f9d66bda1b5264e29779206e552b48cc49634c7b30",
                "d837dcc6a03d896c2ebca6a3accebd15c24784b406036936d0e9141a5952395a",
                "143d40aea67e91285f9222b5c2c92f9d19be431008df4d8d4afbe97820392a1b",
            ),
            keys,
        )
    }

    @Test
    fun sameImageUrlHasSeparateCustomAndPlainCacheIdentities() {
        val url = "https://example.com/card.png"
        val custom = walletImageCandidates(url, ImageModel.CustomUrl)
        val plain = walletImageCandidates(url, ImageModel.PlainUrl)

        assertEquals("5b543902189eb9ca9ca45496dd0b4f5501c73b96281170a590aa939b7e907970", plain.first())
        assertEquals("cf9a2c3f49d2ea6f2942d7d37ac113c828a253f8124121cfd6e8d191ef3e8919", plain.last())
        assertTrue(custom.intersect(plain.toSet()).isEmpty())
    }

    @Test
    fun bankThumbnailPixelsStayInUrlWhileGlideUsesOriginalSize() {
        // 349dp × 220dp at density 3 is a CDN request size, not Glide's RESOURCE size.
        val keys =
            walletImageCandidates(
                "https://did-cdn.pay.xiaomi.com/mfc/thumbnail/webp/w1047h660q80/synthetic-card-art",
                ImageModel.CustomUrl,
            )

        assertEquals(
            listOf(
                "613c7a3ae1acad0214faddb887e96bc1bc7a8de1b2d9d50570018277bfcaea70",
                "787add3027991fff4fb02ba5bd51ad14c70ce10bfd5d913a43a368ae7ebb4d1e",
                "57dc7c27e5afdf6ca7808c7940557d1b8ce3a119b03175511c19930d24974f9b",
            ),
            keys,
        )
    }
}
