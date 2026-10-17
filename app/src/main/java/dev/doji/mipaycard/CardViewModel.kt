package dev.doji.mipaycard

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.doji.mipaycard.data.CardFace
import dev.doji.mipaycard.data.RootWalletFiles
import dev.doji.mipaycard.data.WalletCard
import dev.doji.mipaycard.data.WalletRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

internal enum class CardTask {
    Idle,
    Scanning,
    Working,
}

internal data class ReplacementPreview(
    val card: WalletCard,
    val bitmap: Bitmap,
)

internal data class CardScreenState(
    val cards: List<WalletCard> = emptyList(),
    val hasLoaded: Boolean = false,
    val task: CardTask = CardTask.Idle,
    val restoringCardId: String? = null,
    val restorePreviousCard: WalletCard? = null,
    val replacement: ReplacementPreview? = null,
    val error: String? = null,
    val message: String? = null,
)

internal class CardViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository = WalletRepository(application)
    private val mutableState = MutableStateFlow(CardScreenState())
    val state = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() =
        launchOperation(CardTask.Scanning) {
            val cards = repository.scan()
            mutableState.update { it.copy(cards = cards, hasLoaded = true) }
        }

    fun previewNotification(aid: String) =
        launchOperation(CardTask.Working) {
            withContext(Dispatchers.IO) {
                RootWalletFiles.previewNotification(aid)
            }
        }

    fun prepareReplacement(
        cardId: String,
        uri: Uri,
    ) {
        val card = state.value.cards.find { it.id == cardId } ?: return
        val face = card.face as? CardFace.Editable ?: return
        launchOperation(CardTask.Working) {
            val bitmap = repository.prepareReplacement(face.image, uri)
            mutableState.update {
                it.copy(replacement = ReplacementPreview(card, bitmap))
            }
        }
    }

    fun applyReplacement(replacement: ReplacementPreview) {
        launchOperation(CardTask.Working) {
            val image = repository.replace(replacement.card, replacement.bitmap)
            mutableState.update {
                it.copy(
                    cards =
                        it.cards.map { card ->
                            if (card.id == replacement.card.id) card.copy(face = CardFace.Editable(image, true)) else card
                        },
                    replacement = null,
                    message = getApplication<Application>().getString(R.string.replacement_success),
                )
            }
        }
    }

    fun restore(card: WalletCard) {
        launchOperation(CardTask.Working, restoringCardId = card.id) {
            val image = repository.restore(card)
            mutableState.update {
                it.copy(
                    cards =
                        it.cards.map { current ->
                            if (current.id == card.id) current.copy(face = CardFace.Editable(image, true)) else current
                        },
                    restorePreviousCard = card,
                )
            }
        }
    }

    fun finishRestoreAnimation() {
        mutableState.update { it.copy(restorePreviousCard = null) }
    }

    fun dismissPreview() {
        if (state.value.task != CardTask.Idle) return
        mutableState.update { it.copy(replacement = null) }
    }

    fun dismissError() {
        mutableState.update { it.copy(error = null) }
    }

    fun dismissMessage() {
        mutableState.update { it.copy(message = null) }
    }

    private fun launchOperation(
        task: CardTask,
        restoringCardId: String? = null,
        operation: suspend () -> Unit,
    ) {
        if (state.value.task != CardTask.Idle || state.value.restorePreviousCard != null) return
        mutableState.update { it.copy(task = task, restoringCardId = restoringCardId, error = null) }
        viewModelScope.launch {
            try {
                operation()
            } catch (error: IOException) {
                reportError(error)
            } catch (error: SecurityException) {
                reportError(error)
            } finally {
                mutableState.update { it.copy(task = CardTask.Idle, restoringCardId = null) }
            }
        }
    }

    private fun reportError(error: Exception) {
        mutableState.update {
            it.copy(
                error = error.message ?: getApplication<Application>().getString(R.string.operation_failed),
            )
        }
    }
}
