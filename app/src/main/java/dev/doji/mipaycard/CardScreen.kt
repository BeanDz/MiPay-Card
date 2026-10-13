package dev.doji.mipaycard

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.doji.mipaycard.data.CardFace
import dev.doji.mipaycard.data.CardKind
import dev.doji.mipaycard.data.WalletCard

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
    var restoreKey by rememberSaveable { mutableStateOf<String?>(null) }
    val busy = state.task != CardTask.Idle
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
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { insets ->
        Column(Modifier.padding(insets)) {
            if (busy && state.task != CardTask.Scanning) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            PullToRefreshBox(
                isRefreshing = state.task == CardTask.Scanning,
                onRefresh = model::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.cards.isEmpty() && state.hasLoaded && !busy) {
                        item {
                            EmptyCards(onRefresh = model::refresh)
                        }
                    }
                    if (!state.hasLoaded && !busy) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(stringResource(R.string.load_required))
                                FilledTonalButton(onClick = model::refresh) { Text(stringResource(R.string.retry)) }
                            }
                        }
                    }
                    items(state.cards, key = { card -> card.id }) { card ->
                        CardItem(
                            card = card,
                            enabled = !busy,
                            onChange = {
                                editingKey = card.id
                                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            onRestore = { restoreKey = card.id },
                        )
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
    restoreKey?.let { key ->
        AlertDialog(
            onDismissRequest = { restoreKey = null },
            title = { Text(stringResource(R.string.restore_title)) },
            confirmButton = {
                TextButton(onClick = {
                    restoreKey = null
                    model.restore(key)
                }, enabled = !busy) {
                    Text(stringResource(R.string.restore_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { restoreKey = null }) {
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
private fun CardItem(
    card: WalletCard,
    enabled: Boolean,
    onChange: () -> Unit,
    onRestore: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val moreDescription = stringResource(R.string.card_more, card.name)
    val kindLabel = stringResource(cardKindLabels.getValue(card.kind))
    val editable = card.face as? CardFace.Editable
    val preview =
        when (val face = card.face) {
            is CardFace.Editable -> face.image.preview
            is CardFace.Unavailable -> face.preview
        }
    val iconColor = MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(card.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (editable?.hasBackup == true) {
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        enabled = enabled,
                        modifier = Modifier.semantics { contentDescription = moreDescription },
                    ) {
                        Canvas(Modifier.size(24.dp)) {
                            for (position in listOf(0.25f, 0.5f, 0.75f)) {
                                drawCircle(iconColor, radius = 2.dp.toPx(), center = Offset(size.width * position, size.height / 2))
                            }
                        }
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.restore)) },
                            onClick = {
                                showMenu = false
                                onRestore()
                            },
                            enabled = enabled,
                        )
                    }
                }
            }
        }
        val imageModifier =
            Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .aspectRatio(preview?.let { it.width.toFloat() / it.height } ?: (960f / 606f))
                .clip(RoundedCornerShape(14.dp))
        if (preview != null) {
            Image(
                preview.asImageBitmap(),
                contentDescription = card.name,
                modifier = imageModifier,
                contentScale = ContentScale.Fit,
            )
        } else {
            Box(
                imageModifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.card_image_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    kindLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (card.face is CardFace.Unavailable) {
                    Text(
                        card.face.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (editable != null) {
                TextButton(onClick = onChange, enabled = enabled) {
                    Text(stringResource(R.string.change))
                }
            }
        }
    }
}

@Composable
private fun EmptyCards(onRefresh: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.empty_description), style = MaterialTheme.typography.bodyMedium)
        FilledTonalButton(onClick = onRefresh) { Text(stringResource(R.string.refresh)) }
    }
}
