package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.colorizer.ColorizerClient
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

private val upscaleFactors = listOf(2, 4)

private val devices = listOf("auto", "cuda:0", "cpu")

@Composable
internal fun ColumnScope.ColorizerPage(viewModel: ReaderSettingsViewModel) {
    val preferences = viewModel.preferences

    val enabled by preferences.colorizerEnabled.collectAsState()

    CheckboxItem(
        label = stringResource(MR.strings.pref_colorizer_enabled),
        pref = preferences.colorizerEnabled,
    )

    Text(
        text = stringResource(MR.strings.pref_colorizer_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
    )

    if (!enabled) return

    // Edited through local state so typing isn't delayed by the preference round trip
    var serverUrl by remember { mutableStateOf(preferences.colorizerServerUrl.get()) }
    OutlinedTextField(
        value = serverUrl,
        onValueChange = {
            serverUrl = it
            preferences.colorizerServerUrl.set(it.trim())
        },
        label = { Text(stringResource(MR.strings.pref_colorizer_server_url)) },
        placeholder = { Text("http://192.168.1.10:5000") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
    )

    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val connectedText = stringResource(MR.strings.pref_colorizer_connected)
    Button(
        onClick = {
            scope.launch {
                testing = true
                testResult = null
                testResult = try {
                    ColorizerClient.testConnection(serverUrl)
                    connectedText
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    e.message ?: e.javaClass.simpleName
                } finally {
                    testing = false
                }
            }
        },
        enabled = serverUrl.isNotBlank() && !testing,
        modifier = Modifier.padding(horizontal = 24.dp),
    ) {
        Text(stringResource(MR.strings.pref_colorizer_test_connection))
    }
    testResult?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
    }

    CheckboxItem(
        label = stringResource(MR.strings.pref_colorizer_colorize),
        pref = preferences.colorizerColorize,
    )
    CheckboxItem(
        label = stringResource(MR.strings.pref_colorizer_upscale),
        pref = preferences.colorizerUpscale,
    )
    CheckboxItem(
        label = stringResource(MR.strings.pref_colorizer_denoise),
        pref = preferences.colorizerDenoise,
    )

    val upscale by preferences.colorizerUpscale.collectAsState()
    if (upscale) {
        val upscaleFactor by preferences.colorizerUpscaleFactor.collectAsState()
        SettingsChipRow(MR.strings.pref_colorizer_upscale_factor) {
            upscaleFactors.forEach { factor ->
                FilterChip(
                    selected = upscaleFactor == factor,
                    onClick = { preferences.colorizerUpscaleFactor.set(factor) },
                    label = { Text("x$factor") },
                )
            }
        }
    }

    val denoise by preferences.colorizerDenoise.collectAsState()
    if (denoise) {
        val sigmaPref = preferences.colorizerDenoiseSigma
        val sigma by sigmaPref.collectAsState()
        SliderItem(
            value = sigma,
            valueRange = 5..50,
            steps = 8,
            label = stringResource(MR.strings.pref_colorizer_denoise_sigma),
            onChange = { sigmaPref.set(it) },
        )
    }

    val device by preferences.colorizerDevice.collectAsState()
    SettingsChipRow(MR.strings.pref_colorizer_device) {
        devices.forEach { name ->
            FilterChip(
                selected = device == name,
                onClick = { preferences.colorizerDevice.set(name) },
                label = { Text(name) },
            )
        }
    }

    CheckboxItem(
        label = stringResource(MR.strings.pref_colorizer_compare),
        pref = preferences.colorizerCompare,
    )
}
