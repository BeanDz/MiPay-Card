package dev.doji.mipaycard.data

import java.io.IOException

/** Parse content(1) rows without splitting JSON at commas, equals signs, or line breaks. */
internal fun parseWalletProviderRows(lines: List<String>): List<WalletRecord> {
    val output = lines.joinToString("\n").trim()
    if (Regex("(?m)^(Error\\b|Exception\\b|\\[ERROR\\]|(?:java|android)\\.[\\w.]+Exception:)").containsMatchIn(output)) {
        throw IOException("小米智能卡的内容提供器读取失败，请检查 root 权限后重试")
    }
    if (output == "No result found.") return emptyList()
    val headers = Regex("(?m)^Row: (\\d+) key=").findAll(output).toList()
    if (headers.isEmpty() || headers.first().range.first != 0) {
        throw IOException("无法读取小米智能卡的卡片记录，内容提供器未返回有效数据")
    }
    return headers.mapIndexed { index, header ->
        if (header.groupValues[1].toIntOrNull() != index) {
            throw IOException("小米智能卡的内容提供器返回了不完整的卡片记录")
        }
        val end = headers.getOrNull(index + 1)?.range?.first ?: output.length
        val row = output.substring(header.range.last + 1, end).trimEnd()
        val separator = row.indexOf(", value=")
        if (separator <= 0 || separator + 8 >= row.length) {
            throw IOException("小米智能卡的卡片记录缺少必要字段")
        }
        WalletRecord(row.substring(0, separator), row.substring(separator + 8))
    }
}
