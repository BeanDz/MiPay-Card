package dev.doji.mipaycard.data

internal sealed interface WalletDensityObservation {
    data class Verified(
        val densityDpi: Int,
    ) : WalletDensityObservation

    data object Missing : WalletDensityObservation

    data object Invalid : WalletDensityObservation
}

internal data class WalletDensityEvidence(
    val signature: String,
    val densityDpi: Int,
)

/** An absent wallet activity does not erase evidence bound to the unchanged environment. */
internal fun reconcileWalletDensity(
    observation: WalletDensityObservation,
    signature: String,
    previous: WalletDensityEvidence?,
): WalletDensityEvidence? =
    when (observation) {
        is WalletDensityObservation.Verified -> WalletDensityEvidence(signature, observation.densityDpi)
        WalletDensityObservation.Missing -> previous?.takeIf { it.signature == signature }
        WalletDensityObservation.Invalid -> null
    }

/** These verified activities share AutoDensity's display configuration and have no opt-out. */
internal fun walletDensityObservation(lines: List<String>): WalletDensityObservation {
    val densities = mutableSetOf<Int>()
    var eligibleActivity = false
    var foundEligibleActivity = false
    var foundMainConfiguration = false
    for (line in lines) {
        if (line.trimStart().startsWith("ACTIVITY ")) {
            if (eligibleActivity && !foundMainConfiguration) return WalletDensityObservation.Invalid
            val component = line.trimStart().substringAfter("ACTIVITY ").substringBefore(' ')
            val activity =
                if (component.startsWith("com.miui.tsmclient/")) {
                    component.substringAfter('/')
                } else {
                    ""
                }
            val fullName = if (activity.startsWith('.')) "com.miui.tsmclient$activity" else activity
            eligibleActivity = fullName in densityActivities
            foundEligibleActivity = foundEligibleActivity || eligibleActivity
            foundMainConfiguration = false
        }
        if (!eligibleActivity || !line.trimStart().startsWith("mLastConfigurationFromResources=")) continue
        if (!Regex("\\bdisplay=0\\b").containsMatchIn(line)) continue
        val density =
            Regex("\\b([1-9][0-9]*)dpi\\b")
                .find(line)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull() ?: return WalletDensityObservation.Invalid
        foundMainConfiguration = true
        densities.add(density)
    }
    if (!foundEligibleActivity) return WalletDensityObservation.Missing
    if (eligibleActivity && !foundMainConfiguration) return WalletDensityObservation.Invalid
    val density = densities.singleOrNull() ?: return WalletDensityObservation.Invalid
    return WalletDensityObservation.Verified(density)
}

private val densityActivities =
    setOf(
        "com.miui.tsmclient.ui.quick.DoubleClickActivity",
        "com.miui.tsmclient.ui.CardListActivity",
        "com.miui.tsmclient.ui.CardHolderEntranceActivity",
    )
