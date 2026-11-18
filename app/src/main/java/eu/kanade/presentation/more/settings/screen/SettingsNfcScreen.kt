package eu.kanade.presentation.more.settings.screen

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.NfcPairUnlockDialog
import eu.kanade.presentation.components.NfcWriteDialog
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.data.nfc.NfcUri
import eu.kanade.tachiyomi.ui.reader.colorizer.ColorizerSettings
import kotlinx.collections.immutable.persistentListOf
import mihon.app.di.appGraph
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

object SettingsNfcScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_nfc

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val readerPreferences = remember { context.appGraph.readerPreferences }
        val securityPreferences = remember { context.appGraph.securityPreferences }
        val getCategories = remember { context.appGraph.getCategories }

        val categories by remember(getCategories) { getCategories.subscribe() }
            .collectAsState(initial = emptyList())
        val unlockTagHash by securityPreferences.nfcUnlockTagHash.collectAsState()

        var pendingWrite by remember { mutableStateOf<Pair<String, Uri>?>(null) }
        var showCategoryPicker by remember { mutableStateOf(false) }
        var showPairUnlock by remember { mutableStateOf(false) }

        pendingWrite?.let { (title, uri) ->
            NfcWriteDialog(
                title = title,
                uri = uri,
                onDismissRequest = { pendingWrite = null },
            )
        }

        if (showCategoryPicker) {
            NfcCategoryPickerDialog(
                categories = categories.filterNot(Category::isSystemCategory),
                onCategorySelected = { category ->
                    showCategoryPicker = false
                    pendingWrite = category.name to NfcUri.toUri(NfcUri.Action.Sync(category.id))
                },
                onDismissRequest = { showCategoryPicker = false },
            )
        }

        if (showPairUnlock) {
            NfcPairUnlockDialog(
                onPaired = { hash -> securityPreferences.nfcUnlockTagHash.set(hash) },
                onDismissRequest = { showPairUnlock = false },
            )
        }

        val continueTitle = stringResource(MR.strings.nfc_action_continue_last_read)
        val incognitoTitle = stringResource(MR.strings.nfc_action_incognito_toggle)
        val colorizerPresetTitle = stringResource(MR.strings.nfc_action_colorizer_current)
        val colorizerOffTitle = stringResource(MR.strings.nfc_action_colorizer_off)
        val nightTitle = stringResource(MR.strings.nfc_profile_night)
        val commuteTitle = stringResource(MR.strings.nfc_profile_commute)
        val homeTitle = stringResource(MR.strings.nfc_profile_home)
        val syncAllTitle = stringResource(MR.strings.nfc_sync_all)

        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.nfc_group_quick_actions),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.TextPreference(
                        title = continueTitle,
                        subtitle = stringResource(MR.strings.nfc_action_continue_last_read_summary),
                        onClick = {
                            pendingWrite = continueTitle to NfcUri.toUri(NfcUri.Action.ContinueLastRead)
                        },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = incognitoTitle,
                        subtitle = stringResource(MR.strings.nfc_action_incognito_toggle_summary),
                        onClick = {
                            pendingWrite = incognitoTitle to NfcUri.toUri(NfcUri.Action.Incognito())
                        },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = colorizerPresetTitle,
                        subtitle = stringResource(MR.strings.nfc_action_colorizer_current_summary),
                        onClick = {
                            val settings = ColorizerSettings(
                                serverUrl = readerPreferences.colorizerServerUrl.get(),
                                colorize = readerPreferences.colorizerColorize.get(),
                                upscale = readerPreferences.colorizerUpscale.get(),
                                denoise = readerPreferences.colorizerDenoise.get(),
                                upscaleFactor = readerPreferences.colorizerUpscaleFactor.get(),
                                denoiseSigma = readerPreferences.colorizerDenoiseSigma.get(),
                                device = readerPreferences.colorizerDevice.get(),
                            )
                            val action = NfcUri.fromColorizerSettings(
                                enabled = true,
                                settings = settings,
                                compare = readerPreferences.colorizerCompare.get(),
                            )
                            pendingWrite = colorizerPresetTitle to NfcUri.toUri(action)
                        },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = colorizerOffTitle,
                        subtitle = stringResource(MR.strings.nfc_action_colorizer_off_summary),
                        onClick = {
                            val action = NfcUri.Action.Colorizer(
                                enabled = false,
                                serverUrl = null,
                                colorize = null,
                                upscale = null,
                                denoise = null,
                                upscaleFactor = null,
                                denoiseSigma = null,
                                device = null,
                                compare = null,
                            )
                            pendingWrite = colorizerOffTitle to NfcUri.toUri(action)
                        },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.nfc_group_profiles),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.TextPreference(
                        title = nightTitle,
                        subtitle = stringResource(MR.strings.nfc_profile_night_summary),
                        onClick = {
                            pendingWrite = nightTitle to NfcUri.toUri(
                                NfcUri.Action.Profile(NfcUri.ReadingProfile.NIGHT),
                            )
                        },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = commuteTitle,
                        subtitle = stringResource(MR.strings.nfc_profile_commute_summary),
                        onClick = {
                            pendingWrite = commuteTitle to NfcUri.toUri(
                                NfcUri.Action.Profile(NfcUri.ReadingProfile.COMMUTE),
                            )
                        },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = homeTitle,
                        subtitle = stringResource(MR.strings.nfc_profile_home_summary),
                        onClick = {
                            pendingWrite = homeTitle to NfcUri.toUri(
                                NfcUri.Action.Profile(NfcUri.ReadingProfile.HOME),
                            )
                        },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.nfc_group_sync),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.TextPreference(
                        title = syncAllTitle,
                        subtitle = stringResource(MR.strings.nfc_sync_all_summary),
                        onClick = {
                            pendingWrite = syncAllTitle to NfcUri.toUri(NfcUri.Action.Sync(categoryId = null))
                        },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.nfc_sync_category),
                        subtitle = stringResource(MR.strings.nfc_sync_category_summary),
                        visible = categories.any { !it.isSystemCategory },
                        onClick = { showCategoryPicker = true },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.nfc_group_security),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.nfc_pair_unlock_title),
                        subtitle = stringResource(
                            if (unlockTagHash.isBlank()) {
                                MR.strings.nfc_pair_unlock_summary_unset
                            } else {
                                MR.strings.nfc_pair_unlock_summary_set
                            },
                        ),
                        onClick = { showPairUnlock = true },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.nfc_clear_unlock_key),
                        visible = unlockTagHash.isNotBlank(),
                        onClick = { securityPreferences.nfcUnlockTagHash.delete() },
                    ),
                ),
            ),
        )
    }
}

@Composable
private fun NfcCategoryPickerDialog(
    categories: List<Category>,
    onCategorySelected: (Category) -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(MR.strings.nfc_sync_category)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                categories.forEach { category ->
                    Text(
                        text = category.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCategorySelected(category) }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}
