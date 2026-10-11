package dev.doji.mipaycard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class WalletProviderRowsTest {
    @Test
    fun preservesJsonSeparatorsAndLineBreaks() {
        val first = "{\"title\":\"A, value=B=C\",\n\"has_issue\":true}"
        val second = "[{\"title\":\"Example bank\"}]"
        assertEquals(
            listOf(WalletRecord("TRANSIT", first), WalletRecord("BANKCARD", second)),
            parseWalletProviderRows(listOf("Row: 0 key=TRANSIT, value=$first", "Row: 1 key=BANKCARD, value=$second")),
        )
    }

    @Test
    fun rejectsProviderErrorEvenAfterRows() {
        for (error in listOf(
            "Error while accessing provider:example",
            "Error while executing command: query",
            "[ERROR] Invalid projection",
        )) {
            assertThrows(IOException::class.java) {
                parseWalletProviderRows(listOf("Row: 0 key=BANKCARD, value=[]", error))
            }
        }
    }

    @Test
    fun rejectsMissingRowsAndFields() {
        for (output in listOf("", "Row: 1 key=BANKCARD, value=[]", "Row: 0 key=BANKCARD", "Row: 0 key=, value=[]")) {
            assertThrows(IOException::class.java) { parseWalletProviderRows(listOf(output)) }
        }
    }

    @Test
    fun acceptsExplicitEmptyQueryResult() {
        assertEquals(emptyList<WalletRecord>(), parseWalletProviderRows(listOf("No result found.")))
    }
}
