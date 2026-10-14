package dev.doji.mipaycard

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.doji.mipaycard.data.CardFace
import dev.doji.mipaycard.data.CardKind
import dev.doji.mipaycard.data.WalletCard
import kotlin.math.roundToInt

private enum class CardExpansionPhase {
    Stacked,
    Moving,
    Expanded,
}

private val cardKindLabels =
    mapOf(
        CardKind.Transit to R.string.card_kind_transit,
        CardKind.Bank to R.string.card_kind_bank,
        CardKind.QrBank to R.string.card_kind_qr_bank,
        CardKind.Door to R.string.card_kind_door,
        CardKind.Eid to R.string.card_kind_eid,
        CardKind.Currency to R.string.card_kind_currency,
        CardKind.CarKey to R.string.card_kind_car_key,
    )

@Composable
internal fun CardTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors =
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            if (dark) darkColorScheme() else lightColorScheme()
        }
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CardScreen(model: CardViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editingKey by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var cardOrder by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val cardsById = state.cards.associateBy { it.id }
    val orderedIds = cardOrder.filter { it in cardsById } + state.cards.map { it.id }.filter { it !in cardOrder }
    val orderedCards = orderedIds.mapNotNull(cardsById::get)
    val busy = state.task != CardTask.Idle

    fun collapseCard() {
        val key = selectedKey ?: return
        cardOrder = listOf(key) + orderedIds.filter { it != key }
        selectedKey = null
    }

    BackHandler(enabled = selectedKey != null, onBack = ::collapseCard)

    LaunchedEffect(state.cards, state.hasLoaded) {
        if (!state.hasLoaded) return@LaunchedEffect
        cardOrder = orderedIds
        if (state.cards.none { it.id == selectedKey }) selectedKey = null
    }
    val photoPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val key = editingKey
            editingKey = null
            if (uri != null && key != null) model.prepareReplacement(key, uri)
        }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            model.dismissMessage()
        }
    }

    Scaffold(
        modifier =
            if (selectedKey != null) {
                Modifier.clickable(
                    interactionSource = null,
                    indication = null,
                    onClickLabel = stringResource(R.string.collapse),
                    onClick = ::collapseCard,
                )
            } else {
                Modifier
            },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        snackbarHost = { SnackbarHost(snackbar, Modifier.navigationBarsPadding()) },
    ) { insets ->
        Column(Modifier.padding(insets).consumeWindowInsets(insets)) {
            PullToRefreshBox(
                isRefreshing = state.task == CardTask.Scanning,
                onRefresh = model::refresh,
                modifier = Modifier.fillMaxSize(),
                indicator = {},
            ) {
                if (state.cards.isNotEmpty()) {
                    CardStack(
                        cards = orderedCards,
                        selectedKey = selectedKey,
                        restoringCardId = state.restoringCardId,
                        enabled = !busy,
                        onSelect = { card ->
                            val wasSelected = selectedKey == card.id
                            collapseCard()
                            if (!wasSelected) selectedKey = card.id
                        },
                        onChange = {
                            editingKey = it.id
                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        onRestore = { model.restore(it.id) },
                    )
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        if (state.hasLoaded && !busy) {
                            EmptyCards(onRefresh = model::refresh)
                        } else if (!state.hasLoaded && !busy) {
                            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(stringResource(R.string.load_required))
                                FilledTonalButton(onClick = model::refresh) { Text(stringResource(R.string.retry)) }
                            }
                        }
                    }
                }
            }
        }
    }

    state.replacement?.let { replacement ->
        AlertDialog(
            onDismissRequest = model::dismissPreview,
            title = { Text(stringResource(R.string.preview_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(
                        replacement.bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.card_preview),
                        modifier = Modifier.fillMaxWidth().aspectRatio(replacement.image.width.toFloat() / replacement.image.height),
                        contentScale = ContentScale.Fit,
                    )
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = model::applyReplacement, enabled = !busy) {
                    Text(stringResource(R.string.apply))
                }
            },
            dismissButton = {
                TextButton(onClick = model::dismissPreview, enabled = !busy) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = model::dismissError,
            title = { Text(stringResource(R.string.operation_failed)) },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = model::dismissError) { Text(stringResource(R.string.close)) }
            },
        )
    }
}

@Composable
private fun CardStack(
    cards: List<WalletCard>,
    selectedKey: String?,
    restoringCardId: String?,
    enabled: Boolean,
    onSelect: (WalletCard) -> Unit,
    onChange: (WalletCard) -> Unit,
    onRestore: (WalletCard) -> Unit,
) {
    val scrollState = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        LookaheadScope {
            Layout(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState, reverseScrolling = true)
                        .clipToBounds()
                        .padding(horizontal = 20.dp),
                content = {
                    cards.forEach { card ->
                        key(card.id) {
                            StackedCard(
                                card = card,
                                expanded = card.id == selectedKey,
                                restoring = card.id == restoringCardId,
                                protectNavigationBar = cards.size == 1,
                                lookaheadScope = this@LookaheadScope,
                                enabled = enabled,
                                onSelect = { onSelect(card) },
                                onChange = { onChange(card) },
                                onRestore = { onRestore(card) },
                            )
                        }
                    }
                },
            ) { measurables, constraints ->
                val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
                val selectedIndex = cards.indexOfFirst { it.id == selectedKey }
                val remainingIndices = cards.indices.filter { it != selectedIndex }
                val exposedHeights =
                    remainingIndices.map { index ->
                        val fraction = if (index == remainingIndices.last()) 0.5f else 0.2f
                        (placeables[index].height * fraction).roundToInt()
                    }
                val pileHeight = exposedHeights.sum()
                val selected = placeables.getOrNull(selectedIndex)
                val gap = if (selected != null && remainingIndices.isNotEmpty()) 24.dp.roundToPx() else 0
                val topSpace = if (selected != null) 48.dp.roundToPx() else 0
                val height = maxOf(viewportHeight.roundToPx(), pileHeight + gap + (selected?.height ?: 0) + topSpace)
                layout(constraints.maxWidth, height) {
                    var y = height - pileHeight
                    remainingIndices.forEachIndexed { pileIndex, index ->
                        placeables[index].placeRelative(0, y, zIndex = pileIndex.toFloat())
                        y += exposedHeights[pileIndex]
                    }
                    selected?.placeRelative(0, height - pileHeight - gap - selected.height, zIndex = cards.size.toFloat())
                }
            }
        }
    }
}

@Composable
private fun StackedCard(
    card: WalletCard,
    expanded: Boolean,
    restoring: Boolean,
    protectNavigationBar: Boolean,
    lookaheadScope: LookaheadScope,
    enabled: Boolean,
    onSelect: () -> Unit,
    onChange: () -> Unit,
    onRestore: () -> Unit,
) {
    var phase by remember(expanded) {
        mutableStateOf(if (expanded) CardExpansionPhase.Moving else CardExpansionPhase.Stacked)
    }
    val controlsVisible = phase == CardExpansionPhase.Expanded
    val transition = updateTransition(controlsVisible, label = "cardExpansion")
    val expansion =
        transition.animateFloat(
            transitionSpec = { spring(dampingRatio = CARD_SPRING_DAMPING, stiffness = Spring.StiffnessMediumLow) },
            label = "cardControls",
        ) { if (it) 1f else 0f }
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(expanded, phase) {
        if (!expanded || phase != CardExpansionPhase.Expanded) return@LaunchedEffect
        bringIntoView.bringIntoView()
    }
    val editable = card.face as? CardFace.Editable
    val controlsModifier =
        Modifier
            .graphicsLayer { alpha = expansion.value.coerceIn(0f, 1f) }
            .then(if (controlsVisible) Modifier else Modifier.clearAndSetSemantics {})
    Layout(
        modifier = Modifier.bringIntoViewRequester(bringIntoView),
        content = {
            Column(
                controlsModifier
                    .then(
                        if (restoring) {
                            Modifier.graphicsLayer { alpha = 0f }.clearAndSetSemantics {}
                        } else {
                            Modifier
                        },
                    ).fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(card.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(cardKindLabels.getValue(card.kind)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CardFaceImage(
                card,
                Modifier
                    .animateCardPosition(lookaheadScope) {
                        if (expanded && phase == CardExpansionPhase.Moving) phase = CardExpansionPhase.Expanded
                    }.shakeWhileRestoring(restoring)
                    .clickable(
                        enabled = enabled,
                        interactionSource = null,
                        indication = null,
                        role = Role.Button,
                        onClick = onSelect,
                    ),
            )
            Column(
                modifier =
                    controlsModifier
                        .then(if (protectNavigationBar) Modifier.navigationBarsPadding() else Modifier)
                        .padding(top = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (card.face as? CardFace.Unavailable)?.let { Text(it.reason) }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    if (editable != null) {
                        ChangeCardButton(
                            enabled = enabled && controlsVisible,
                            restoring = restoring,
                            onChange = onChange,
                            onRestore = if (editable.hasBackup) onRestore else null,
                            modifier = Modifier.shakeWhileRestoring(restoring),
                        )
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val childConstraints = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val header = measurables[0].measure(childConstraints)
        val face = measurables[1].measure(childConstraints)
        val actions = measurables[2].measure(childConstraints)
        val targetHeight = face.height + if (expanded) header.height + actions.height else 0
        layout(face.width, constraints.constrainHeight(targetHeight)) {
            val progress = expansion.value
            val faceY = if (expanded) header.height else 0
            header.placeRelative(0, faceY - (header.height * progress).roundToInt())
            actions.placeRelative(0, faceY + face.height - (actions.height * (1f - progress)).roundToInt())
            face.placeRelative(0, faceY, zIndex = 1f)
        }
    }
}

@Composable
private fun CardFaceImage(
    card: WalletCard,
    modifier: Modifier = Modifier,
) {
    val preview =
        when (val face = card.face) {
            is CardFace.Editable -> face.image.preview
            is CardFace.Unavailable -> face.preview
        }
    val shape = RoundedCornerShape(16.dp)
    val imageModifier =
        modifier
            .fillMaxWidth()
            .aspectRatio(preview?.let { it.width.toFloat() / it.height } ?: (960f / 606f))
            .clip(shape)
    if (preview != null) {
        Image(
            preview.asImageBitmap(),
            contentDescription = card.name,
            modifier = imageModifier,
            contentScale = ContentScale.Fit,
        )
    } else {
        Box(
            imageModifier
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .semantics { contentDescription = card.name },
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(card.name, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.card_image_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EmptyCards(onRefresh: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.empty_description), style = MaterialTheme.typography.bodyMedium)
        FilledTonalButton(onClick = onRefresh) { Text(stringResource(R.string.refresh)) }
    }
}
