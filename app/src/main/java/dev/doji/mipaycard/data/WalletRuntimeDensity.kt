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

/** Read the wallet's actual resource density without depending on activity class names. */
internal fun walletDensityObservation(lines: List<String>): WalletDensityObservation {
    var density: Int? = null
    var walletActivity = false
    var foundResourceConfiguration = false
    for (line in lines) {
        if (line.trimStart().startsWith("ACTIVITY ")) {
            if (walletActivity && !foundResourceConfiguration) return WalletDensityObservation.Invalid
            val component = line.trimStart().substringAfter("ACTIVITY ").substringBefore(' ')
            walletActivity = component.substringBefore('/') == WALLET_PACKAGE && component.substringAfter('/', "").isNotBlank()
            foundResourceConfiguration = false
        }
        if (!walletActivity || !line.trimStart().startsWith("mLastConfigurationFromResources=")) continue
        val display =
            Regex("\\bdisplay=([0-9]+)\\b")
                .find(line)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull() ?: return WalletDensityObservation.Invalid
        foundResourceConfiguration = true
        if (display != 0) continue
        val observedDensity =
            Regex("\\b([1-9][0-9]*)dpi\\b")
                .find(line)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull() ?: return WalletDensityObservation.Invalid
        if (density != null && density != observedDensity) return WalletDensityObservation.Invalid
        density = observedDensity
    }
    if (walletActivity && !foundResourceConfiguration) return WalletDensityObservation.Invalid
    if (density == null) return WalletDensityObservation.Missing
    return WalletDensityObservation.Verified(density)
}
