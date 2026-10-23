package eu.kanade.tachiyomi.ui.reader.colorizer

import android.content.Context
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okio.Buffer
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Sends reader pages to the user's colorizer server and caches the results on disk.
 *
 * If anything goes wrong (no server, network error, server error) the original page is returned, so
 * reading never breaks because of the colorizer.
 */
object PageColorizer {

    /**
     * @property buffer the image to show: the processed page, or the original if it wasn't processed.
     * @property original the untouched page bytes, only set when the page was processed and the caller
     * asked to keep them (for the before/after slider).
     */
    class Loaded(val buffer: Buffer, val original: ByteArray?)

    private const val MAX_CACHE_BYTES = 512L * 1024 * 1024
    private const val TOAST_INTERVAL_MILLIS = 30_000L
    private const val HEADER_BYTES = 64

    // Limits how many pages are on the server at once, so flipping quickly doesn't flood it
    private val permits = Semaphore(2)

    // Pages the server answered without an image (e.g. "Already colored"), for this app session
    private val nothingToReturn: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var lastToastAt = 0L

    /**
     * Reads the page from [openStream] and, when the colorizer is enabled, returns the processed version.
     */
    suspend fun load(
        context: Context,
        preferences: ReaderPreferences,
        openStream: () -> InputStream,
        keepOriginal: Boolean,
    ): Loaded {
        suspend fun readPlain(): Loaded {
            val buffer = withContext(Dispatchers.IO) { openStream().use { Buffer().readFrom(it) } }
            return Loaded(buffer, null)
        }

        if (!preferences.colorizerEnabled.get()) return readPlain()

        val settings = ColorizerSettings(
            serverUrl = preferences.colorizerServerUrl.get(),
            colorize = preferences.colorizerColorize.get(),
            upscale = preferences.colorizerUpscale.get(),
            denoise = preferences.colorizerDenoise.get(),
            upscaleFactor = preferences.colorizerUpscaleFactor.get(),
            denoiseSigma = preferences.colorizerDenoiseSigma.get(),
            device = preferences.colorizerDevice.get(),
        )
        if (!settings.hasWork) return readPlain()

        val appContext = context.applicationContext
        if (settings.serverUrl.isBlank()) {
            showNotice(appContext, appContext.stringResource(MR.strings.colorizer_no_server))
            return readPlain()
        }

        val originalBytes = withContext(Dispatchers.IO) { openStream().use { it.readBytes() } }
        suspend fun untouched() = Loaded(withContext(Dispatchers.IO) { Buffer().write(originalBytes) }, null)

        // Animated images can't be processed page by page. Only the header is needed to tell.
        val header = Buffer().write(originalBytes, 0, minOf(originalBytes.size, HEADER_BYTES))
        if (ImageUtil.isAnimatedAndSupported(header)) return untouched()

        return try {
            val processed = withContext(Dispatchers.IO) { processToFile(appContext, originalBytes, settings) }
            if (processed == null) {
                untouched()
            } else {
                val buffer = withContext(Dispatchers.IO) { processed.inputStream().use { Buffer().readFrom(it) } }
                Loaded(buffer, if (keepOriginal) originalBytes else null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            val reason = e.message ?: e.javaClass.simpleName
            showNotice(appContext, appContext.stringResource(MR.strings.colorizer_error, reason))
            untouched()
        }
    }

    /**
     * Returns the cached processed file for this page and settings, asking the server first if needed.
     * Null means the server had nothing to return (e.g. the page is already colored).
     */
    private suspend fun processToFile(context: Context, original: ByteArray, settings: ColorizerSettings): File? {
        val dir = File(context.cacheDir, "colorizer").apply { mkdirs() }
        val name = sha256(original) + "_" + settings.signature
        val cached = File(dir, "$name.img")

        if (name in nothingToReturn) return null
        if (cached.exists()) {
            cached.setLastModified(System.currentTimeMillis())
            return cached
        }

        return permits.withPermit {
            // Another request for the same page may have finished while this one was waiting
            if (cached.exists()) return@withPermit cached

            val temp = File(dir, "$name.tmp${System.nanoTime()}")
            try {
                if (ColorizerClient.process(original, settings, temp) && temp.renameTo(cached)) {
                    prune(dir)
                    cached
                } else {
                    // Remember (until the app closes) so the page isn't sent again and again
                    nothingToReturn.add(name)
                    null
                }
            } finally {
                temp.delete()
            }
        }
    }

    private fun prune(dir: File) {
        val files = dir.listFiles { file -> file.name.endsWith(".img") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_CACHE_BYTES) break
            total -= file.length()
            file.delete()
        }
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private suspend fun showNotice(context: Context, message: String) {
        val now = System.currentTimeMillis()
        if (now - lastToastAt < TOAST_INTERVAL_MILLIS) return
        lastToastAt = now
        withContext(Dispatchers.Main) { context.toast(message) }
    }
}
