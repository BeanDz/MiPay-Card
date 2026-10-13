package dev.doji.mipaycard.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletCardParserTest {
    @Test
    fun buildsCardSpecificNamesBeforeGenericTitles() {
        val cards =
            parseWalletCards(
                listOf(
                    WalletRecord(
                        "BANKCARD",
                        """{"title":"Mi Pay","bank_card_product_name":"Example Debit Card","bank_name":"Example Bank","aid":"bank"}""",
                    ),
                    WalletRecord(
                        "QRBANKCARD",
                        """{"title":"Mi Pay","bank_name":"Example QR Bank","aid":"qr"}""",
                    ),
                    WalletRecord(
                        "MIFARE_ENTRANCE",
                        """{"title":"Door Card","door_card_product_name":"Office Pass","aid":"door"}""",
                    ),
                ),
            )

        assertEquals(listOf("Example Bank · Example Debit Card", "Example QR Bank", "Office Pass"), cards.map { it.name })
    }

    @Test
    fun keepsBankInstancesWithSharedArtAndLockedStatus() {
        val record =
            WalletRecord(
                "BANKCARD",
                """[
                    {"title":"Example Bank","aid":"product-aid","bank_card_vc_reference_id":"reference-one",
                     "card_vc_status":1,"card_art":"NextPay/example-art"},
                    {"title":"Example Bank","aid":"product-aid","bank_card_vc_reference_id":"reference-two",
                     "card_vc_status":2,"card_art":"NextPay/example-art"}
                ]""",
            )
        val cards = parseWalletCards(listOf(record))

        assertEquals(2, cards.size)
        assertNotEquals(cards[0].id, cards[1].id)
        assertTrue(cards.all { it.kind == CardKind.Bank && it.problem == null })
        assertEquals(CardArt.BankIdentifier("NextPay/example-art"), cards[0].art)
        assertEquals(cards[0].art, cards[1].art)
        val reversed = record.copy(value = JSONArray(record.value).let { "[${it.get(1)},${it.get(0)}]" })
        assertEquals(cards.map { it.id }.reversed(), parseWalletCards(listOf(reversed)).map { it.id })
    }

    @Test
    fun usesGenericTransitFactoryAndIgnoresGroupAndJsonTypeOverrides() {
        val generic =
            transitRecord("CUSTOM_CITY", true, """{"issuedListBgHd":"https://example.com/city.png"}""")
                .copy(
                    value =
                        """{"title":"Example City","aid":"city-aid","has_issue":true,
                            "groupId":2,"cardType":"BANKCARD","card_art":"NextPay/art",
                            "card_ui_info":{"issuedListBgHd":"https://example.com/city.png"}}""",
                )
        val cards =
            parseWalletCards(
                listOf(
                    generic,
                    transitRecord("SPTC_OTHER", false, "{}"),
                    WalletRecord("settings", """{"theme":"dark","cardType":"BANKCARD","aid":"setting"}"""),
                ),
            )

        assertEquals(1, cards.size)
        assertEquals(CardKind.Transit, cards.single().kind)
        assertEquals(CardArt.Online("https://example.com/city.png"), cards.single().art)
    }

    @Test
    fun excludesUnissuedAndEmptyAidCardsBeforeReplacement() {
        val cards =
            parseWalletCards(
                listOf(
                    transitRecord("TRANSIT_ONE", false, "{}"),
                    WalletRecord("CUSTOM_CITY", """{"title":"No Aid Transit","has_issue":true}"""),
                    WalletRecord("BANKCARD", """{"title":"No Aid Bank","bank_card_vc_reference_id":"reference"}"""),
                    WalletRecord("MIFARE_ENTRANCE", """{"title":"Empty Aid Door","aid":""}"""),
                    WalletRecord("EID", """{"title":"Visible ID","aid":"synthetic-eid"}"""),
                ),
            )

        assertEquals(listOf("Visible ID"), cards.map { it.name })
        assertEquals(CardArt.Unknown, cards.single().art)
    }

    @Test
    fun keepsTransitIdentityAcrossArtChangesAndDistinguishesDifferentAids() {
        val before = transitRecord("TRANSIT_CITY", true, """{"personalCardFace":"https://example.com/personal.png"}""")
        val after = transitRecord("TRANSIT_CITY", true, """{"issuedListBgHd":"https://example.com/new.png"}""")
        val second = before.copy(value = JSONObject(before.value).put("aid", "another-aid").toString())

        assertEquals(parseWalletCards(listOf(before)).single().id, parseWalletCards(listOf(after)).single().id)
        val cards = parseWalletCards(listOf(before, second))
        assertEquals(2, cards.size)
        assertNotEquals(cards[0].id, cards[1].id)
        assertTrue(cards.all { it.problem == null })
    }

    @Test
    fun distinguishesPersonalAnimatedMissingAndNativeArt() {
        val cards =
            parseWalletCards(
                listOf(
                    transitRecord(
                        "PERSONAL_CITY",
                        true,
                        """{"personalCardFace":"https://example.com/personal.png",
                            "gifUrl":"https://example.com/animated.gif",
                            "issuedListBgHd":"https://example.com/standard.png"}""",
                    ),
                    transitRecord("ANIMATED_CITY", true, """{"gifUrl":"https://example.com/animated.gif"}"""),
                    transitRecord("MISSING_CITY", true, "{}"),
                    WalletRecord("BANKCARD", """{"title":"Native Bank","aid":"native-bank"}"""),
                    WalletRecord("MIFARE_ENTRANCE", """{"title":"Native Door","aid":"native-door","card_native_drawable":2}"""),
                ),
            )

        assertEquals(
            listOf(CardArt.Online("https://example.com/personal.png"), CardArt.Animated, CardArt.Unknown, CardArt.Native, CardArt.Native),
            cards.map { it.art },
        )
    }

    @Test
    fun selectsNativeThemeOnlyWhenEnabledAndMatchingForegroundExists() {
        val objectRules =
            transitRecord(
                "THEME_CITY",
                true,
                """{"issuedListBgHd":"https://example.com/background.png",
                    "themeFgRules":{"light":"https://example.com/logo.png"}}""",
            )
        val ui = JSONObject(objectRules.value).getJSONObject("card_ui_info")
        ui.put("themeFgRules", ui.getJSONObject("themeFgRules").toString())
        val stringRules = objectRules.copy(value = JSONObject(objectRules.value).put("card_ui_info", ui).toString())

        assertEquals(CardArt.Online("https://example.com/background.png"), parseWalletCards(listOf(objectRules)).single().art)
        assertEquals(CardArt.Native, parseWalletCards(listOf(objectRules), useThemeArt = true).single().art)
        assertEquals(CardArt.Native, parseWalletCards(listOf(stringRules), useThemeArt = true).single().art)
        assertEquals(
            CardArt.Online("https://example.com/background.png"),
            parseWalletCards(listOf(objectRules), useThemeArt = true, themeLogoType = "dark").single().art,
        )
    }

    @Test
    fun followsDoorDummyAndTemporaryReaderPredicates() {
        val cards =
            parseWalletCards(
                listOf(
                    WalletRecord(
                        "MIFARE_ENTRANCE",
                        """[
                            {"title":"Issued Door","aid":"issued-door","apply_status":"ISSUED","card_art":"https://example.com/door.png"},
                            {"title":"Legacy Door","aid":"legacy-door"},
                            {"title":"Pending Door","aid":"pending-door","apply_status":"APPLYING"},
                            {"title":"Temporary Reader","aid":"reader","apply_status":"ISSUED","card_vc_status":10}
                        ]""",
                    ),
                ),
            )

        assertEquals(listOf("Issued Door", "Legacy Door"), cards.map { it.name })
        assertTrue(cards.all { it.kind == CardKind.Door })
        assertEquals(CardArt.Online("https://example.com/door.png"), cards[0].art)
    }

    @Test
    fun supportsEachProvenStaticSourceAndCarNames() {
        val cards =
            parseWalletCards(
                listOf(
                    WalletRecord("QRBANKCARD", """{"title":"QR Bank","aid":"qr","bg_image":"https://example.com/qr.png"}"""),
                    WalletRecord("EID", """{"title":"Electronic ID","aid":"eid","card_art":"https://example.com/eid.png"}"""),
                    WalletRecord("DCEPCARD", """{"title":"Currency","aid":"currency","cardArt":"https://example.com/currency.png"}"""),
                    WalletRecord(
                        "TRADITIONALCARKEYCARD",
                        """{"name":"Traditional Key","aid":"traditional","cardArt":"https://example.com/traditional.png"}""",
                    ),
                    WalletRecord(
                        "DIGITALKEY",
                        """{"name":"Digital Key","aid":"digital","product":{"cardArt":"https://example.com/digital.png"}}""",
                    ),
                    WalletRecord(
                        "ICCOADIGITALKEY",
                        """{"customName":"Custom Key","friendlyName":"Factory Key","aid":"iccoa","product":{"cardArt":"https://example.com/iccoa.png"}}""",
                    ),
                ),
            )

        assertEquals(
            listOf(
                CardKind.QrBank,
                CardKind.Eid,
                CardKind.Currency,
                CardKind.CarKey,
                CardKind.CarKey,
                CardKind.CarKey,
            ),
            cards.map {
                it.kind
            },
        )
        assertEquals(listOf("QR Bank", "Electronic ID", "Currency", "Traditional Key", "Digital Key", "Custom Key"), cards.map { it.name })
        assertEquals(
            listOf("qr", "eid", "currency", "traditional", "digital", "iccoa").map { CardArt.Online("https://example.com/$it.png") },
            cards.map { it.art },
        )
        assertTrue(cards.all { it.problem == null })
    }

    @Test
    fun filtersBleAndIccoaTagsWithNfcOrRkeExceptions() {
        val cards =
            parseWalletCards(
                listOf(
                    WalletRecord("BLECAR", """{"title":"BLE","aid":"ble"}"""),
                    WalletRecord(
                        "ICCOADIGITALKEY",
                        """[
                            {"friendlyName":"No Config","aid":"plain"},
                            {"friendlyName":"No Tags","aid":"no-tags","uiConfig":{}},
                            {"friendlyName":"NFC","aid":"nfc","uiConfig":{"tag":["NFC"]}},
                            {"friendlyName":"RKE","aid":"rke","uiConfig":{"tag":["BLE"]},"product":{"rkeFunctions":[{"type":"lock"}]}},
                            {"friendlyName":"RKE Prefix","aid":"prefix","uiConfig":{"tag":["BLE"]},"product":{"rkeFunctions":[{},false]}},
                            {"friendlyName":"Invalid RKE","aid":"invalid-rke","uiConfig":{"tag":["BLE"]},"product":{"rkeFunctions":[false,{}]}},
                            {"friendlyName":"Hidden","aid":"hidden","uiConfig":{"tag":["BLE"]}},
                            {"friendlyName":"Empty Tags","aid":"empty","uiConfig":{"tag":[]},"product":{"rkeFunctions":[]}}
                        ]""",
                    ),
                ),
            )

        assertEquals(listOf("No Config", "No Tags", "NFC", "RKE", "RKE Prefix"), cards.map { it.name })
        assertTrue(cards.all { it.kind == CardKind.CarKey })
    }

    @Test
    fun keepsMalformedRowsAndRejectsInvalidAddresses() {
        val cards =
            parseWalletCards(
                listOf(
                    WalletRecord(
                        "BANKCARD",
                        """[{"title":"Valid Bank","aid":"valid-bank","card_art":"NextPay/art"},42,{"title":"Broken Bank","aid":"broken-bank","card_art":false}]""",
                    ),
                    WalletRecord("MIFARE_ENTRANCE", "[invalid"),
                    WalletRecord("CITY", """{"title":"Invalid Issue","aid":"invalid-issue","has_issue":"true"}"""),
                    WalletRecord("MIFARE_ENTRANCE", """{"title":"Invalid Door","aid":"invalid-door","card_art":"file:///data/card.png"}"""),
                    transitRecord("INVALID_CITY", true, """{"issuedListBgHd":"https://"}"""),
                    WalletRecord("settings", "not json"),
                ),
            )

        assertEquals(7, cards.size)
        assertEquals(CardArt.BankIdentifier("NextPay/art"), cards.first().art)
        assertNull(cards.first().problem)
        assertTrue(cards.drop(1).all { it.art == CardArt.Unknown && it.problem != null })
    }

    @Test
    fun rejectsFullBankUrlsAndTraversalWithoutRepairingIdentifiers() {
        val invalid =
            listOf(
                "https://example.com/art.png",
                "//example.com/art",
                "/NextPay/art",
                "NextPay/../art",
                "NextPay/%2e%2e/art",
                "NextPay/art?variant=1",
                "NextPay/art#part",
            )
        val cards =
            parseWalletCards(
                listOf(
                    WalletRecord(
                        "BANKCARD",
                        JSONArray(
                            invalid.mapIndexed {
                                index,
                                art,
                                ->
                                JSONObject().put("title", "Bank $index").put("aid", "aid-$index").put("card_art", art)
                            },
                        ).toString(),
                    ),
                ),
            )

        assertEquals(invalid.size, cards.size)
        assertTrue(cards.all { it.art == CardArt.Unknown && it.problem != null })
    }

    @Test
    fun retainsEveryConflictingDuplicateWithUniqueDisplayIdsAndBlocksEditing() {
        val cards =
            parseWalletCards(
                listOf(
                    WalletRecord(
                        "BANKCARD",
                        """[
                            {"title":"First","aid":"shared","card_art":"NextPay/first"},
                            {"title":"Second","aid":"shared","card_art":"NextPay/second"},
                            {"title":"Malformed","aid":"shared","card_art":false},
                            {"title":"Independent","aid":"independent","card_art":"NextPay/other"}
                        ]""",
                    ),
                    WalletRecord("EID", "[invalid"),
                    WalletRecord("EID", "[invalid"),
                ),
            )

        assertEquals(6, cards.size)
        assertEquals(cards.size, cards.map { it.id }.toSet().size)
        assertEquals(CardArt.BankIdentifier("NextPay/first"), cards[0].art)
        assertEquals(CardArt.BankIdentifier("NextPay/second"), cards[1].art)
        assertTrue(cards.take(3).all { it.problem?.contains("重复") == true })
        assertNull(cards[3].problem)
        assertTrue(cards.takeLast(2).all { it.problem?.contains("重复") == true })
    }

    @Test
    fun keepsMalformedIdentityStableWhenOtherMetadataChanges() {
        val original = WalletRecord("BANKCARD", """[{"title":"Example Bank","aid":"broken-bank","card_art":false}]""")
        val changed = original.copy(value = """[{"title":"Renamed Bank","aid":"broken-bank","card_art":false,"card_vc_status":2}]""")

        val before = parseWalletCards(listOf(original)).single()
        val after = parseWalletCards(listOf(changed)).single()

        assertEquals(before.id, after.id)
        assertEquals("Renamed Bank", after.name)
        assertNotNull(after.problem)
    }

    private fun transitRecord(
        type: String,
        issued: Boolean,
        ui: String,
    ): WalletRecord = WalletRecord(type, """{"title":"Example Transit","aid":"aid-$type","has_issue":$issued,"card_ui_info":$ui}""")
}
