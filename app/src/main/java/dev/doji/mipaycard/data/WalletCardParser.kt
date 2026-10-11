package dev.doji.mipaycard.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest

internal data class WalletRecord(
    val key: String,
    val value: String,
)

internal enum class CardKind { Transit, Bank, QrBank, Door, Eid, Currency, CarKey, Other }

internal sealed interface CardArt {
    data class Online(
        val value: String,
    ) : CardArt

    data class BankIdentifier(
        val value: String,
    ) : CardArt

    data object Native : CardArt

    data object Animated : CardArt

    data object Unknown : CardArt
}

internal data class WalletCardInfo(
    val id: String,
    val name: String,
    val kind: CardKind,
    val art: CardArt,
    val problem: String? = null,
)

private val knownKinds =
    mapOf(
        "SPTC" to CardKind.Transit,
        "SPTC_NEW" to CardKind.Transit,
        "BANKCARD" to CardKind.Bank,
        "QRBANKCARD" to CardKind.QrBank,
        "MIFARE_ENTRANCE" to CardKind.Door,
        "EID" to CardKind.Eid,
        "DCEPCARD" to CardKind.Currency,
        "DIGITALKEY" to CardKind.CarKey,
        "TRADITIONALCARKEYCARD" to CardKind.CarKey,
        "ICCOADIGITALKEY" to CardKind.CarKey,
    )

internal fun parseWalletCards(
    records: List<WalletRecord>,
    useThemeArt: Boolean = false,
    themeLogoType: String = "light",
): List<WalletCardInfo> {
    val cards =
        buildList {
            for (record in records) {
                // BLECarKeyInfo explicitly excludes itself from the swiping screen.
                if (record.key == "BLECAR") continue
                val value =
                    try {
                        val tokens = JSONTokener(record.value)
                        val parsed = tokens.nextValue()
                        if (tokens.nextClean() != '\u0000') throw JSONException("Trailing card record content")
                        parsed
                    } catch (error: JSONException) {
                        if (record.key in knownKinds || record.value.contains("\"has_issue\"")) {
                            add(malformedCard(record.key, record.value, null))
                        }
                        continue
                    }

                when (value) {
                    is JSONObject -> {
                        parseWalletCard(record.key, value, useThemeArt, themeLogoType)?.let(::add)
                    }

                    is JSONArray -> {
                        for (index in 0 until value.length()) {
                            val card = value.opt(index)
                            if (card is JSONObject) {
                                parseWalletCard(record.key, card, useThemeArt, themeLogoType)?.let(::add)
                            } else if (record.key in knownKinds) {
                                add(malformedCard(record.key, card.toString(), null))
                            }
                        }
                    }

                    else -> {
                        if (record.key in knownKinds) add(malformedCard(record.key, record.value, null))
                    }
                }
            }
        }

    val duplicates =
        cards
            .groupingBy { it.id }
            .eachCount()
            .filterValues { it > 1 }
            .keys
    val occurrences = mutableMapOf<String, Int>()
    return cards.map { card ->
        if (card.id !in duplicates) return@map card
        val ordinal = occurrences.getOrDefault(card.id, 0) + 1
        occurrences[card.id] = ordinal
        card.copy(id = "${card.id}:$ordinal", problem = "卡片记录的独立标识重复，暂时无法修改")
    }
}

private fun parseWalletCard(
    type: String,
    json: JSONObject,
    useThemeArt: Boolean,
    themeLogoType: String,
): WalletCardInfo? {
    // CardInfoFactory uses PayableCardInfo for other keys. Ordinary provider settings
    // do not carry its issued-card marker and must not become card rows.
    val kind = knownKinds[type] ?: if (json.has("has_issue")) CardKind.Transit else return null

    try {
        if (kind == CardKind.Transit) {
            val issued = json.get("has_issue")
            if (issued !is Boolean) throw JSONException("Invalid has_issue")
            if (!issued) return null
        }
        if (kind == CardKind.Door) {
            val status = optionalString(json, "apply_status")
            if (status.isNotEmpty() && status != "ISSUED") return null
            if (json.has("card_vc_status") && json.getInt("card_vc_status") == 10) return null
        }
        if (type == "ICCOADIGITALKEY" && !isVisibleIccoaKey(json)) return null

        val aid = optionalString(json, "aid")
        if (aid.isBlank()) return null
        val name = cardName(type, json, kind)
        if (name.isBlank()) throw JSONException("Missing card name")
        val art =
            when (kind) {
                CardKind.Transit -> {
                    transitArt(json.optJSONObject("card_ui_info"), useThemeArt, themeLogoType)
                }

                CardKind.Bank -> {
                    val value = optionalString(json, "card_art")
                    if (value.isBlank()) CardArt.Native else CardArt.BankIdentifier(validBankArt(value))
                }

                CardKind.Door -> {
                    onlineArt(optionalString(json, "card_art"), CardArt.Native)
                }

                CardKind.QrBank -> {
                    onlineArt(optionalString(json, "bg_image"))
                }

                CardKind.Eid -> {
                    onlineArt(optionalString(json, "card_art"))
                }

                CardKind.Currency -> {
                    onlineArt(optionalString(json, "cardArt"))
                }

                CardKind.CarKey -> {
                    val source = if (type == "TRADITIONALCARKEYCARD") json else json.optJSONObject("product")
                    onlineArt(source?.let { optionalString(it, "cardArt") }.orEmpty())
                }

                CardKind.Other -> {
                    CardArt.Unknown
                }
            }
        return WalletCardInfo(cardId(type, cardIdentifier(json, kind) ?: aid), name, kind, art)
    } catch (error: JSONException) {
        return malformedCard(type, json.toString(), json, kind)
    } catch (error: URISyntaxException) {
        return malformedCard(type, json.toString(), json, kind, "卡面地址格式不受支持")
    }
}

private fun isVisibleIccoaKey(json: JSONObject): Boolean {
    val tags = json.optJSONObject("uiConfig")?.optJSONArray("tag") ?: return true
    if ((0 until tags.length()).any { tags.opt(it) == "NFC" }) return true
    // Product.parse stops at the first non-object RKE. A valid first object
    // proves the parsed list is nonempty, even if a later entry is malformed.
    return json.optJSONObject("product")?.optJSONArray("rkeFunctions")?.optJSONObject(0) != null
}

private fun transitArt(
    ui: JSONObject?,
    useThemeArt: Boolean,
    themeLogoType: String,
): CardArt {
    if (ui == null) return CardArt.Unknown
    if (useThemeArt) {
        val rules =
            when (val value = ui.opt("themeFgRules")) {
                null, JSONObject.NULL -> null
                is JSONObject -> value
                is String -> if (value.isEmpty()) null else JSONObject(value)
                else -> throw JSONException("Invalid theme foreground rules")
            }
        if (rules != null && optionalString(rules, themeLogoType).isNotEmpty()) return CardArt.Native
    }
    val personal = optionalString(ui, "personalCardFace")
    if (personal.isNotEmpty()) return onlineArt(personal)
    val animated = optionalString(ui, "gifUrl")
    if (animated.isNotEmpty()) {
        validOnlineUrl(animated)
        return CardArt.Animated
    }
    return onlineArt(optionalString(ui, "issuedListBgHd"))
}

private fun onlineArt(
    value: String,
    missing: CardArt = CardArt.Unknown,
): CardArt = if (value.isEmpty()) missing else CardArt.Online(validOnlineUrl(value))

private fun validOnlineUrl(value: String): String {
    val uri = URI(value)
    if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null) {
        throw JSONException("Invalid card image URL")
    }
    return value
}

private fun validBankArt(value: String): String {
    val uri = URI(value)
    val path = uri.path.orEmpty()
    if (uri.isAbsolute || uri.rawAuthority != null || uri.rawQuery != null || uri.rawFragment != null ||
        path.isBlank() || path.startsWith('/') || '\\' in path ||
        path.split('/').any { it.isEmpty() || it == "." || it == ".." } || path.any { it.isISOControl() }
    ) {
        throw JSONException("Invalid bank image identifier")
    }
    return value
}

private fun cardName(
    type: String,
    json: JSONObject,
    kind: CardKind,
): String =
    when {
        type == "ICCOADIGITALKEY" -> firstString(json, "customName", "friendlyName", "title")
        kind == CardKind.CarKey -> firstString(json, "name", "title")
        kind == CardKind.Bank || kind == CardKind.QrBank -> firstString(json, "title", "bank_card_product_name", "bank_name")
        kind == CardKind.Door -> firstString(json, "title", "door_card_product_name")
        else -> firstString(json, "title", "cardName")
    }

private fun malformedCard(
    type: String,
    serialized: String,
    json: JSONObject?,
    kind: CardKind = knownKinds[type] ?: CardKind.Transit,
    problem: String = "卡片记录格式不受支持，暂时无法修改",
): WalletCardInfo {
    val name =
        listOf("customName", "friendlyName", "title", "bank_card_product_name", "door_card_product_name", "name")
            .firstNotNullOfOrNull { key -> (json?.opt(key) as? String)?.takeIf { it.isNotBlank() } }
    return WalletCardInfo(cardId(type, cardIdentifier(json, kind) ?: serialized), name ?: "无法识别的卡片", kind, CardArt.Unknown, problem)
}

private fun cardIdentifier(
    json: JSONObject?,
    kind: CardKind,
): String? {
    val fields =
        when (kind) {
            CardKind.Bank, CardKind.QrBank -> listOf("bank_card_vc_reference_id", "aid")
            CardKind.Door -> listOf("door_card_vc_uid", "door_card_cid", "aid")
            CardKind.CarKey -> listOf("keyId", "aid", "uiIdentifier")
            else -> listOf("aid")
        }
    return fields.firstNotNullOfOrNull { key -> (json?.opt(key) as? String)?.takeIf { it.isNotBlank() } }
}

private fun cardId(
    type: String,
    identifier: String,
): String =
    MessageDigest.getInstance("SHA-256").digest("$type\u0000$identifier".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

private fun firstString(
    json: JSONObject,
    vararg keys: String,
): String {
    for (key in keys) {
        val value = optionalString(json, key)
        if (value.isNotBlank()) return value
    }
    return ""
}

private fun optionalString(
    json: JSONObject,
    key: String,
): String {
    if (!json.has(key) || json.isNull(key)) return ""
    val value = json.get(key)
    if (value !is String) throw JSONException("Invalid string field")
    return value
}
