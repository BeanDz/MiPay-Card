package dev.doji.mipaycard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WalletRuntimeDensityTest {
    @Test
    fun retainsVerifiedDensityAfterWriterStopsTheWallet() {
        val signature = "wallet-4180:main-display-520dpi"
        val running =
            walletDensityObservation(
                listOf(
                    "ACTIVITY com.miui.tsmclient/.ui.CardHolderEntranceActivity token",
                    "mLastConfigurationFromResources={497dpi display=0}",
                ),
            )
        val recorded = reconcileWalletDensity(running, signature, null)
        val afterStop = walletDensityObservation(listOf("No activities match com.miui.tsmclient/"))

        assertEquals(WalletDensityObservation.Missing, afterStop)
        assertEquals(WalletDensityEvidence(signature, 497), reconcileWalletDensity(afterStop, signature, recorded))
    }

    @Test
    fun updatesStoredEvidenceWhenWalletReportsNewDensity() {
        val signature = "wallet-4180:main-display-520dpi"
        val previous = WalletDensityEvidence(signature, 497)
        val observation =
            walletDensityObservation(
                listOf(
                    "ACTIVITY com.miui.tsmclient/.ui.quick.DoubleClickActivity token",
                    "mLastConfigurationFromResources={520dpi display=0}",
                ),
            )

        assertEquals(WalletDensityEvidence(signature, 520), reconcileWalletDensity(observation, signature, previous))
    }

    @Test
    fun invalidWalletConfigurationDiscardsStoredEvidence() {
        val signature = "wallet-4180:main-display-520dpi"
        val previous = WalletDensityEvidence(signature, 497)
        val configurations =
            listOf(
                listOf("mLastConfigurationFromResources={invalid display=0}"),
                listOf(
                    "mLastConfigurationFromResources={497dpi display=0}",
                    "mLastConfigurationFromResources={520dpi display=0}",
                ),
                listOf("mCurrentConfig={520dpi display=0}"),
            )
        for (configuration in configurations) {
            val observation =
                walletDensityObservation(
                    listOf("ACTIVITY com.miui.tsmclient/.ui.CardListActivity token") + configuration,
                )

            assertEquals(WalletDensityObservation.Invalid, observation)
            assertNull(reconcileWalletDensity(observation, signature, previous))
        }
    }

    @Test
    fun changedWalletOrDisplaySignatureRequiresFreshEvidence() {
        val previous = WalletDensityEvidence("wallet-4180:main-display-520dpi", 497)
        for (signature in listOf("wallet-4181:main-display-520dpi", "wallet-4180:main-display-560dpi")) {
            assertNull(reconcileWalletDensity(WalletDensityObservation.Missing, signature, previous))
            assertEquals(
                WalletDensityEvidence(signature, 510),
                reconcileWalletDensity(WalletDensityObservation.Verified(510), signature, previous),
            )
        }
    }

    @Test
    fun acceptsPersistedEvidenceAfterEditorRestartWithoutWalletActivity() {
        val signature = "wallet-4180:main-display-520dpi"
        val restored = WalletDensityEvidence(signature, 497)

        assertEquals(restored, reconcileWalletDensity(walletDensityObservation(emptyList()), signature, restored))
        assertNull(reconcileWalletDensity(walletDensityObservation(emptyList()), signature, null))
    }

    @Test
    fun partialEligibleActivityEvidenceDoesNotPermitFallback() {
        val lines =
            listOf(
                "ACTIVITY com.miui.tsmclient/.ui.CardListActivity first",
                "mLastConfigurationFromResources={497dpi display=0}",
                "ACTIVITY com.miui.tsmclient/.ui.CardHolderEntranceActivity second",
                "mCurrentConfig={520dpi display=0}",
                "ACTIVITY example.other/.MainActivity third",
            )

        assertEquals(WalletDensityObservation.Invalid, walletDensityObservation(lines))
    }

    @Test
    fun selectsWalletResourcesInsteadOfGlobalOrForeignActivity() {
        assertEquals(
            WalletDensityObservation.Verified(497),
            walletDensityObservation(
                listOf(
                    "  ACTIVITY example.other/.ui.CardIntroActivity token pid=1",
                    "    mLastConfigurationFromResources={450dpi display=0 extraData=Bundle[{}]}",
                    "  ACTIVITY com.miui.tsmclient/.ui.quick.DoubleClickActivity token pid=1",
                    "    mCurrentConfig={520dpi display=0}",
                    "    mLastConfigurationFromResources={497dpi display=0 extraData=Bundle[{}]}",
                ),
            ),
        )
    }

    @Test
    fun acceptsFullyQualifiedActivityName() {
        assertEquals(
            WalletDensityObservation.Verified(497),
            walletDensityObservation(
                listOf(
                    "ACTIVITY com.miui.tsmclient/com.miui.tsmclient.ui.quick.DoubleClickActivity token",
                    "mLastConfigurationFromResources={497dpi display=0}",
                ),
            ),
        )
    }

    @Test
    fun rejectsMissingResourceEvidence() {
        for (config in listOf(
            "mCurrentConfig={520dpi display=0}",
            "mLastConfigurationFromResources={497dpi}",
            "mLastConfigurationFromResources={invalid display=0}",
        )) {
            assertEquals(
                WalletDensityObservation.Invalid,
                walletDensityObservation(
                    listOf(
                        "ACTIVITY com.miui.tsmclient/.ui.quick.DoubleClickActivity token",
                        config,
                    ),
                ),
            )
        }
    }

    @Test
    fun acceptsCurrentAndFutureWalletActivityNames() {
        for (activity in listOf(".ui.CardHolderAndroidVEntranceActivity", ".ui.MiPayEntranceActivity", ".ui.FutureCardActivity")) {
            assertEquals(
                WalletDensityObservation.Verified(497),
                walletDensityObservation(
                    listOf(
                        "ACTIVITY com.miui.tsmclient/$activity token",
                        "mCurrentConfig={520dpi display=0}",
                        "mLastConfigurationFromResources={497dpi display=0}",
                    ),
                ),
            )
        }
    }

    @Test
    fun ignoresKnownSecondaryDisplayResources() {
        val secondary =
            listOf(
                "ACTIVITY com.miui.tsmclient/.ui.FutureCardActivity token",
                "mLastConfigurationFromResources={429dpi display=1}",
            )

        assertEquals(WalletDensityObservation.Missing, walletDensityObservation(secondary))
        assertEquals(
            WalletDensityObservation.Verified(497),
            walletDensityObservation(
                secondary +
                    listOf(
                        "ACTIVITY com.miui.tsmclient/.ui.MiPayEntranceActivity token",
                        "mLastConfigurationFromResources={497dpi display=0}",
                    ),
            ),
        )
    }

    @Test
    fun rejectsConflictsAndIncompleteEvidenceFromUnfamiliarWalletActivities() {
        val known =
            listOf(
                "ACTIVITY com.miui.tsmclient/.ui.CardHolderAndroidVEntranceActivity token",
                "mLastConfigurationFromResources={497dpi display=0}",
            )
        for (configuration in listOf(
            "mLastConfigurationFromResources={520dpi display=0}",
            "mLastConfigurationFromResources={invalid display=0}",
            "mLastConfigurationFromResources={497dpi}",
            "mCurrentConfig={497dpi display=0}",
        )) {
            val unfamiliar = listOf("ACTIVITY com.miui.tsmclient/.ui.FutureCardActivity token", configuration)

            assertEquals(WalletDensityObservation.Invalid, walletDensityObservation(known + unfamiliar))
            assertEquals(WalletDensityObservation.Invalid, walletDensityObservation(unfamiliar + known))
        }
    }

    @Test
    fun rejectsConflictingSwipingResourceDensities() {
        assertEquals(
            WalletDensityObservation.Invalid,
            walletDensityObservation(
                listOf(
                    "ACTIVITY com.miui.tsmclient/.ui.quick.DoubleClickActivity first",
                    "mLastConfigurationFromResources={497dpi display=0}",
                    "ACTIVITY com.miui.tsmclient/.ui.quick.DoubleClickActivity second",
                    "mLastConfigurationFromResources={520dpi display=0}",
                ),
            ),
        )
    }

    @Test
    fun acceptsSharedDensityFromPersistentWalletLists() {
        assertEquals(
            WalletDensityObservation.Verified(497),
            walletDensityObservation(
                listOf(
                    "ACTIVITY com.miui.tsmclient/.ui.CardListActivity token",
                    "mLastConfigurationFromResources={497dpi display=0}",
                    "ACTIVITY com.miui.tsmclient/.ui.CardHolderEntranceActivity token",
                    "mLastConfigurationFromResources={497dpi display=0}",
                ),
            ),
        )
    }

    @Test
    fun rejectsForeignPackageAndConflictingListDensity() {
        assertEquals(
            WalletDensityObservation.Missing,
            walletDensityObservation(
                listOf(
                    "ACTIVITY example.other/com.miui.tsmclient.ui.CardListActivity token",
                    "mLastConfigurationFromResources={497dpi display=0}",
                    "ACTIVITY example.com.miui.tsmclient/.ui.CardListActivity token",
                    "mLastConfigurationFromResources={497dpi display=0}",
                ),
            ),
        )
        assertEquals(
            WalletDensityObservation.Invalid,
            walletDensityObservation(
                listOf(
                    "ACTIVITY com.miui.tsmclient/.ui.quick.DoubleClickActivity token",
                    "mLastConfigurationFromResources={497dpi display=0}",
                    "ACTIVITY com.miui.tsmclient/.ui.CardListActivity token",
                    "mLastConfigurationFromResources={520dpi display=0}",
                ),
            ),
        )
    }
}
