package eu.kanade.presentation.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.nfc.NfcTagWriter
import eu.kanade.tachiyomi.data.nfc.NfcUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun NfcWriteDialog(
    title: String,
    uri: Uri,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()

    val nfcAvailable = remember(context) { NfcTagWriter.isAvailable(context) }
    val nfcEnabled = remember(context) { NfcTagWriter.isEnabled(context) }

    var status by remember { mutableStateOf<NfcWriteStatus>(NfcWriteStatus.Waiting) }

    DisposableEffect(activity, uri, nfcAvailable, nfcEnabled) {
        if (activity != null && nfcAvailable && nfcEnabled) {
            NfcTagWriter.enableReaderMode(activity) { tag ->
                scope.launch(Dispatchers.IO) {
                    val result = NfcTagWriter.writeUri(tag, uri)
                    status = when (result) {
                        NfcTagWriter.WriteResult.Success -> NfcWriteStatus.Success
                        NfcTagWriter.WriteResult.NotWritable -> NfcWriteStatus.NotWritable
                        is NfcTagWriter.WriteResult.TagTooSmall ->
                            NfcWriteStatus.TooSmall(result.neededBytes, result.maxBytes)
                        NfcTagWriter.WriteResult.UnsupportedTag -> NfcWriteStatus.Unsupported
                        is NfcTagWriter.WriteResult.Error -> NfcWriteStatus.Error(result.message)
                    }
                }
            }
        }
        onDispose {
            if (activity != null) {
                NfcTagWriter.disableReaderMode(activity)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = title) },
        text = {
            Column {
                val message = when {
                    !nfcAvailable -> stringResource(MR.strings.nfc_not_supported)
                    !nfcEnabled -> stringResource(MR.strings.nfc_disabled_hint)
                    else -> when (val s = status) {
                        NfcWriteStatus.Waiting -> stringResource(MR.strings.nfc_tap_to_write)
                        NfcWriteStatus.Success -> stringResource(MR.strings.nfc_write_success)
                        NfcWriteStatus.NotWritable -> stringResource(MR.strings.nfc_error_not_writable)
                        is NfcWriteStatus.TooSmall ->
                            stringResource(MR.strings.nfc_error_too_small, s.needed, s.capacity)
                        NfcWriteStatus.Unsupported -> stringResource(MR.strings.nfc_error_unsupported)
                        is NfcWriteStatus.Error -> stringResource(MR.strings.nfc_error_generic, s.message)
                    }
                }
                Text(text = message)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uri.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            if (nfcAvailable && !nfcEnabled) {
                TextButton(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
                    },
                ) {
                    Text(text = stringResource(MR.strings.nfc_open_settings))
                }
            } else {
                TextButton(onClick = onDismissRequest) {
                    Text(
                        text = stringResource(
                            if (status == NfcWriteStatus.Success) MR.strings.action_ok else MR.strings.action_cancel,
                        ),
                    )
                }
            }
        },
        dismissButton = if (nfcAvailable && !nfcEnabled) {
            {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            }
        } else {
            null
        },
    )
}

@Composable
fun NfcPairUnlockDialog(
    onPaired: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()

    val nfcAvailable = remember(context) { NfcTagWriter.isAvailable(context) }
    val nfcEnabled = remember(context) { NfcTagWriter.isEnabled(context) }
    var paired by remember { mutableStateOf(false) }

    DisposableEffect(activity, nfcAvailable, nfcEnabled) {
        if (activity != null && nfcAvailable && nfcEnabled) {
            NfcTagWriter.enableReaderMode(activity) { tag ->
                val hash = NfcUri.hashTagUid(tag) ?: return@enableReaderMode
                scope.launch(Dispatchers.Main) {
                    onPaired(hash)
                    paired = true
                }
            }
        }
        onDispose {
            if (activity != null) {
                NfcTagWriter.disableReaderMode(activity)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(MR.strings.nfc_pair_unlock_title)) },
        text = {
            val message = when {
                !nfcAvailable -> stringResource(MR.strings.nfc_not_supported)
                !nfcEnabled -> stringResource(MR.strings.nfc_disabled_hint)
                paired -> stringResource(MR.strings.nfc_pair_unlock_success)
                else -> stringResource(MR.strings.nfc_pair_unlock_prompt)
            }
            Text(text = message)
        },
        confirmButton = {
            if (nfcAvailable && !nfcEnabled) {
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }) {
                    Text(text = stringResource(MR.strings.nfc_open_settings))
                }
            } else {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(if (paired) MR.strings.action_ok else MR.strings.action_cancel))
                }
            }
        },
    )
}

private sealed interface NfcWriteStatus {
    data object Waiting : NfcWriteStatus
    data object Success : NfcWriteStatus
    data object NotWritable : NfcWriteStatus
    data class TooSmall(val needed: Int, val capacity: Int) : NfcWriteStatus
    data object Unsupported : NfcWriteStatus
    data class Error(val message: String) : NfcWriteStatus
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun NfcMangaModeDialog(
    mangaId: Long,
    mangaTitle: String,
    onDismissRequest: () -> Unit,
) {
    var selected by remember { mutableStateOf<Pair<String, Uri>?>(null) }

    val target = selected
    if (target != null) {
        NfcWriteDialog(
            title = target.first,
            uri = target.second,
            onDismissRequest = onDismissRequest,
        )
        return
    }

    val resumeTitle = stringResource(MR.strings.nfc_manga_mode_resume)
    val openTitle = stringResource(MR.strings.nfc_manga_mode_open)
    val shareTitle = stringResource(MR.strings.nfc_manga_mode_share)

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(MR.strings.nfc_manga_dialog_title)) },
        text = {
            Column {
                NfcModeOption(
                    title = resumeTitle,
                    subtitle = stringResource(MR.strings.nfc_manga_mode_resume_summary),
                    onClick = {
                        selected = mangaTitle to NfcUri.toUri(NfcUri.Action.Manga(mangaId, resume = true))
                    },
                )
                NfcModeOption(
                    title = openTitle,
                    subtitle = stringResource(MR.strings.nfc_manga_mode_open_summary),
                    onClick = {
                        selected = mangaTitle to NfcUri.toUri(NfcUri.Action.Manga(mangaId, resume = false))
                    },
                )
                NfcModeOption(
                    title = shareTitle,
                    subtitle = stringResource(MR.strings.nfc_manga_mode_share_summary, mangaTitle),
                    onClick = {
                        selected = mangaTitle to NfcUri.toUri(NfcUri.Action.Search(mangaTitle))
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
private fun NfcModeOption(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
