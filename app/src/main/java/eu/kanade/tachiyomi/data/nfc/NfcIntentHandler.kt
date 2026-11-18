package eu.kanade.tachiyomi.data.nfc

import android.content.Context
import android.content.Intent
import cafe.adriel.voyager.navigator.Navigator
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.library.LibraryUpdateWorker
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.colorizer.ColorizerClient
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.i18n.MR

@Inject
@SingleIn(AppScope::class)
class NfcIntentHandler(
    private val basePreferences: BasePreferences,
    private val readerPreferences: ReaderPreferences,
    private val getNextChapters: GetNextChapters,
    private val getCategories: GetCategories,
    private val historyRepository: HistoryRepository,
) {

    fun isIncognitoLaunchIntent(intent: Intent?): Boolean {
        val uri = NfcUri.extractUriFromIntent(intent) ?: return false
        return NfcUri.parse(uri) is NfcUri.Action.Incognito
    }

    fun handleIntent(
        context: Context,
        intent: Intent,
        navigator: Navigator,
        scope: CoroutineScope,
    ): Boolean {
        val uri = NfcUri.extractUriFromIntent(intent) ?: return false
        val action = NfcUri.parse(uri) ?: return false

        when (action) {
            is NfcUri.Action.Colorizer -> handleColorizer(context, action, scope)
            is NfcUri.Action.Manga -> handleManga(context, action, navigator, scope)
            is NfcUri.Action.Profile -> handleProfile(context, action, scope)
            is NfcUri.Action.Incognito -> handleIncognito(context, action)
            is NfcUri.Action.Search -> handleSearch(action, navigator)
            is NfcUri.Action.Sync -> handleSync(context, action, scope)
            NfcUri.Action.ContinueLastRead -> handleContinueLastRead(context, scope)
        }
        return true
    }

    private fun handleColorizer(
        context: Context,
        action: NfcUri.Action.Colorizer,
        scope: CoroutineScope,
    ) {
        readerPreferences.colorizerEnabled.set(action.enabled)
        action.serverUrl?.let { readerPreferences.colorizerServerUrl.set(it.trim()) }
        action.colorize?.let { readerPreferences.colorizerColorize.set(it) }
        action.upscale?.let { readerPreferences.colorizerUpscale.set(it) }
        action.denoise?.let { readerPreferences.colorizerDenoise.set(it) }
        action.upscaleFactor?.let { readerPreferences.colorizerUpscaleFactor.set(it) }
        action.denoiseSigma?.let { readerPreferences.colorizerDenoiseSigma.set(it) }
        action.device?.let { readerPreferences.colorizerDevice.set(it) }
        action.compare?.let { readerPreferences.colorizerCompare.set(it) }

        if (!action.enabled) {
            context.toast(context.stringResource(MR.strings.nfc_colorizer_disabled))
            return
        }

        val url = readerPreferences.colorizerServerUrl.get()
        context.toast(context.stringResource(MR.strings.nfc_colorizer_applied))
        if (url.isBlank()) return

        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { ColorizerClient.testConnection(url) }
                context.stringResource(MR.strings.pref_colorizer_connected)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                context.stringResource(MR.strings.colorizer_error, e.message ?: e.javaClass.simpleName)
            }
            context.toast(result)
        }
    }

    private fun handleManga(
        context: Context,
        action: NfcUri.Action.Manga,
        navigator: Navigator,
        scope: CoroutineScope,
    ) {
        if (!action.resume) {
            navigator.popUntilRoot()
            scope.launch { HomeScreen.openTab(HomeScreen.Tab.Library(action.mangaId)) }
            return
        }

        scope.launch {
            val nextChapter = withContext(Dispatchers.IO) {
                getNextChapters.await(action.mangaId, onlyUnread = true).firstOrNull()
                    ?: getNextChapters.await(action.mangaId, onlyUnread = false).lastOrNull()
            }
            if (nextChapter != null) {
                context.startActivity(ReaderActivity.newIntent(context, action.mangaId, nextChapter.id))
            } else {
                navigator.popUntilRoot()
                HomeScreen.openTab(HomeScreen.Tab.Library(action.mangaId))
            }
        }
    }

    private fun handleProfile(
        context: Context,
        action: NfcUri.Action.Profile,
        scope: CoroutineScope,
    ) {
        when (action.profile) {
            NfcUri.ReadingProfile.NIGHT -> {
                readerPreferences.readerTheme.set(1) // Black background
                readerPreferences.customBrightness.set(true)
                readerPreferences.customBrightnessValue.set(-30)
                readerPreferences.colorFilter.set(true)
                readerPreferences.colorFilterValue.set(NIGHT_WARM_FILTER_ARGB)
                readerPreferences.colorizerEnabled.set(false)
                scope.launch { HomeScreen.openTab(HomeScreen.Tab.History) }
                context.toast(context.stringResource(MR.strings.nfc_profile_applied_night))
            }
            NfcUri.ReadingProfile.COMMUTE -> {
                basePreferences.downloadedOnly.set(true)
                readerPreferences.colorizerEnabled.set(false)
                basePreferences.highQualityRenderer.set(false)
                context.toast(context.stringResource(MR.strings.nfc_profile_applied_commute))
            }
            NfcUri.ReadingProfile.HOME -> {
                basePreferences.downloadedOnly.set(false)
                readerPreferences.customBrightness.set(false)
                readerPreferences.colorFilter.set(false)
                basePreferences.highQualityRenderer.set(true)
                context.toast(context.stringResource(MR.strings.nfc_profile_applied_home))
            }
        }
    }

    private fun handleIncognito(
        context: Context,
        action: NfcUri.Action.Incognito,
    ) {
        val newState = when (action.mode) {
            NfcUri.IncognitoMode.TOGGLE -> !basePreferences.incognitoMode.get()
            NfcUri.IncognitoMode.ON -> true
            NfcUri.IncognitoMode.OFF -> false
        }
        basePreferences.incognitoMode.set(newState)
        val msg = if (newState) MR.strings.nfc_incognito_enabled else MR.strings.nfc_incognito_disabled
        context.toast(context.stringResource(msg))
    }

    private fun handleSearch(
        action: NfcUri.Action.Search,
        navigator: Navigator,
    ) {
        navigator.popUntilRoot()
        navigator.push(GlobalSearchScreen(action.query))
    }

    private fun handleSync(
        context: Context,
        action: NfcUri.Action.Sync,
        scope: CoroutineScope,
    ) {
        scope.launch {
            val category = action.categoryId?.let { id ->
                withContext(Dispatchers.IO) { getCategories.await().find { it.id == id } }
            }
            val started = LibraryUpdateWorker.startNow(context.workManager, category)
            val message = when {
                !started -> context.stringResource(MR.strings.update_already_running)
                category != null -> context.stringResource(MR.strings.nfc_sync_started_category, category.name)
                else -> context.stringResource(MR.strings.nfc_sync_started_all)
            }
            context.toast(message)
        }
    }

    private fun handleContinueLastRead(
        context: Context,
        scope: CoroutineScope,
    ) {
        scope.launch {
            val target = withContext(Dispatchers.IO) {
                val nextChapter = getNextChapters.await(onlyUnread = true).firstOrNull()
                if (nextChapter != null) {
                    nextChapter.mangaId to nextChapter.id
                } else {
                    historyRepository.getLastHistory()?.let { it.mangaId to it.chapterId }
                }
            }
            if (target != null) {
                context.startActivity(ReaderActivity.newIntent(context, target.first, target.second))
            } else {
                context.toast(context.stringResource(MR.strings.nfc_continue_no_history))
                HomeScreen.openTab(HomeScreen.Tab.History)
            }
        }
    }

    companion object {
        // Warm amber tint (20% alpha, #FF9800) for night reading
        private const val NIGHT_WARM_FILTER_ARGB = 0x33FF9800
    }
}
