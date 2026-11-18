package eu.kanade.tachiyomi.ui.reader.colorizer

import android.annotation.SuppressLint
import android.util.Base64
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.jsonMime
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import org.json.JSONObject
import tachiyomi.core.common.util.system.ImageUtil
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * What the user chose in the reader's Colorizer settings.
 */
data class ColorizerSettings(
    val serverUrl: String,
    val colorize: Boolean,
    val upscale: Boolean,
    val denoise: Boolean,
    val upscaleFactor: Int,
    val denoiseSigma: Int,
    val device: String,
) {
    val hasWork: Boolean get() = colorize || upscale || denoise

    /**
     * Identifies the settings that change the output image, used to key the on-disk result cache.
     * The server URL and device don't change the result, so they aren't part of it.
     */
    val signature: String
        get() = "c${colorize.toInt()}u${upscale.toInt()}d${denoise.toInt()}f${upscaleFactor}s$denoiseSigma"

    private fun Boolean.toInt() = if (this) 1 else 0
}

/**
 * Talks to the manga colorizer server (the same API the desktop client uses).
 *
 * The server normally uses a self-signed HTTPS certificate, so this client accepts any certificate.
 * It is only ever used for the server address the user typed in, never for sources or trackers.
 */
@SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
object ColorizerClient {

    private val http: OkHttpClient by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.MINUTES)
            .readTimeout(5, TimeUnit.MINUTES)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun baseUrl(serverUrl: String): HttpUrl {
        var url = serverUrl.trim().trimEnd('/')
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            url = "http://$url"
        }
        return url.toHttpUrl()
    }

    /**
     * Checks that the server answers like the colorizer (GET / returns status "running").
     * Throws with a readable message when it doesn't.
     */
    suspend fun testConnection(serverUrl: String) {
        val request = Request.Builder().url(baseUrl(serverUrl)).get().build()
        http.newCall(request).awaitSuccess().use { response ->
            val body = response.body.string()
            if (!body.contains("running")) throw IOException("Unexpected server response")
        }
    }

    /**
     * Sends [image] to the server and writes the processed image to [out].
     *
     * @return true if [out] now holds the processed image, false if the server returned no image
     * (for example when it decided the page is already colored).
     */
    suspend fun process(image: ByteArray, settings: ColorizerSettings, out: File): Boolean {
        val type = ImageUtil.findImageType { image.inputStream() }
        val mime = type?.mime ?: "image/png"
        val extension = type?.extension ?: "png"

        val payload = JSONObject().apply {
            put("imgName", "page.$extension")
            put("imgData", "data:$mime;base64," + Base64.encodeToString(image, Base64.NO_WRAP))
            put("imgWidth", -1)
            put("imgHeight", -1)
            put("colorize", settings.colorize)
            put("upscale", settings.upscale)
            put("denoise", settings.denoise)
            put("denoiseSigma", settings.denoiseSigma)
            put("upscaleFactor", settings.upscaleFactor)
            put("cache", false)
            put("mangaTitle", "")
            put("mangaChapter", "")
            put("device", settings.device)
        }

        val request = Request.Builder()
            .url(baseUrl(settings.serverUrl).newBuilder().addPathSegment("colorize-image-data").build())
            .post(payload.toString().toRequestBody(jsonMime))
            .build()

        return http.newCall(request).awaitSuccess().use { response ->
            // The response holds the whole processed image as base64 inside JSON. It can be tens of MB, so
            // it is decoded straight from the stream to a file instead of being parsed into a String.
            writeImageField(response.body.source(), out)
        }
    }

    private const val IMAGE_FIELD = "\"colorImgData\""
    private const val CHUNK = 8192 // must be a multiple of 4 so each chunk is valid base64 on its own

    private fun writeImageField(source: BufferedSource, out: File): Boolean {
        val keyIndex = source.indexOf(IMAGE_FIELD.encodeUtf8())
        if (keyIndex < 0) return false
        source.skip(keyIndex + IMAGE_FIELD.length)

        // Skip ':' and whitespace up to the value
        var next = source.readByte().toInt().toChar()
        while (next == ':' || next.isWhitespace()) {
            next = source.readByte().toInt().toChar()
        }
        if (next != '"') return false // null: nothing to use

        // Optional "data:image/png;base64," prefix
        if (source.rangeEquals(0, "data:".encodeUtf8())) {
            val comma = source.indexOf(','.code.toByte())
            if (comma < 0) return false
            source.skip(comma + 1)
        }

        val chunk = ByteArray(CHUNK)
        var size = 0
        var wrote = false
        out.outputStream().buffered().use { sink ->
            fun flush(isLast: Boolean) {
                if (size == 0) return
                if (!isLast && size % 4 != 0) return
                sink.write(Base64.decode(chunk, 0, size, Base64.DEFAULT))
                size = 0
                wrote = true
            }

            while (true) {
                var b = source.readByte()
                if (b == '"'.code.toByte()) break
                if (b == '\\'.code.toByte()) {
                    b = source.readByte()
                    if (b == 'u'.code.toByte()) source.skip(4) // unicode escapes are never base64 data
                    // "\/" is a slash; any other escape (\n, \r, ...) is not part of the base64 data
                    if (b != '/'.code.toByte()) continue
                }
                chunk[size++] = b
                if (size == CHUNK) flush(isLast = false)
            }
            flush(isLast = true)
        }
        return wrote && out.length() > 0
    }
}
