package dev.doji.mipaycard

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.doji.mipaycard.data.CachedImage
import dev.doji.mipaycard.data.CardFace
import dev.doji.mipaycard.data.WalletCard
import dev.doji.mipaycard.data.WalletRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

internal enum class CardTask {
    Idle,
    Scanning,
    Preparing,
    Applying,
    Restoring,
}

internal data class ReplacementPreview(
    val cardId: String,
    val image: CachedImage,
    val bitmap: Bitmap,
)

internal data class CardScreenState(
    val cards: List<WalletCard> = emptyList(),
    val hasLoaded: Boolean = false,
    val task: CardTask = CardTask.Idle,
    val restoringCardId: String? = null,
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
            loadCards()
        }

    fun prepareReplacement(
        cardId: String,
        uri: Uri,
    ) {
        val card = state.value.cards.find { it.id == cardId } ?: return
        val face = card.face as? CardFace.Editable ?: return
        launchOperation(CardTask.Preparing) {
            val bitmap = repository.prepareReplacement(face.image, uri)
            mutableState.update {
                it.copy(replacement = ReplacementPreview(card.id, face.image, bitmap))
            }
        }
    }

    fun applyReplacement() {
        val replacement = state.value.replacement ?: return
        launchOperation(CardTask.Applying) {
            val image = repository.replace(replacement.cardId, replacement.image, replacement.bitmap)
            mutableState.update {
                it.copy(
                    cards =
                        it.cards.map { card ->
                            if (card.id == replacement.cardId) card.copy(face = CardFace.Editable(image, true)) else card
                        },
                    replacement = null,
                    message = getApplication<Application>().getString(R.string.replacement_success),
                )
            }
        }
    }

    fun restore(cardId: String) {
        val card = state.value.cards.find { it.id == cardId } ?: return
        val face = card.face as? CardFace.Editable ?: return
        launchOperation(CardTask.Restoring, restoringCardId = cardId) {
            val image = repository.restore(cardId, face.image)
            mutableState.update {
                it.copy(
                    cards =
                        it.cards.map { current ->
                            if (current.id == cardId) current.copy(face = CardFace.Editable(image, true)) else current
                        },
                    message = getApplication<Application>().getString(R.string.restore_success),
                )
            }
        }
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

    private suspend fun loadCards() {
        val cards = repository.scan()
        mutableState.update { it.copy(cards = cards, hasLoaded = true) }
    }

    private fun launchOperation(
        task: CardTask,
        restoringCardId: String? = null,
        operation: suspend () -> Unit,
    ) {
        if (state.value.task != CardTask.Idle) return
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
