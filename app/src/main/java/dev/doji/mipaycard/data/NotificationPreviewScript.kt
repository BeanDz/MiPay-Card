package dev.doji.mipaycard.data

/** RF_ON previews the active card without fabricating a transaction or changing the active card. */
internal fun notificationPreviewScript(aid: String): String =
    """
    test "${'$'}(settings get secure key_simple_card_swipe_switch)" = 1 || exit 51
    active_record=${'$'}(settings get secure TSM_CARD_ACTIVATE_RECORD) || exit 52
    test "${'$'}{active_record%%,*}" = ${quoteShell(aid)} || exit 52
    output=${'$'}(am startservice -n $WALLET_PACKAGE/.service.NfcEventMonitorService -a com.miui.nfc.action.RF_ON 2>&1) || exit 53
    case "${'$'}output" in
        *Error:*|*Exception*) exit 53 ;;
    esac
    """.trimIndent()
