package eu.kanade.tachiyomi.data.nfc

import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import eu.kanade.tachiyomi.ui.reader.colorizer.ColorizerSettings
import java.security.MessageDigest

object NfcUri {

    const val SCHEME = "mihon"
    const val HOST = "nfc"

    enum class ReadingProfile(val key: String) {
        NIGHT("night"),
        COMMUTE("commute"),
        HOME("home"),
        ;

        companion object {
            fun fromKey(key: String?): ReadingProfile? = entries.find { it.key.equals(key, ignoreCase = true) }
        }
    }

    enum class IncognitoMode(val key: String) {
        TOGGLE("toggle"),
        ON("on"),
        OFF("off"),
        ;

        companion object {
            fun fromKey(key: String?): IncognitoMode =
                entries.find { it.key.equals(key, ignoreCase = true) } ?: TOGGLE
        }
    }

    sealed interface Action {
        data class Colorizer(
            val enabled: Boolean,
            val serverUrl: String?,
            val colorize: Boolean?,
            val upscale: Boolean?,
            val denoise: Boolean?,
            val upscaleFactor: Int?,
            val denoiseSigma: Int?,
            val device: String?,
            val compare: Boolean?,
        ) : Action

        data class Manga(
            val mangaId: Long,
            val resume: Boolean,
        ) : Action

        data class Profile(
            val profile: ReadingProfile,
        ) : Action

        data class Incognito(
            val mode: IncognitoMode = IncognitoMode.TOGGLE,
        ) : Action

        data class Search(
            val query: String,
        ) : Action

        data class Sync(
            val categoryId: Long?,
        ) : Action

        data object ContinueLastRead : Action
    }

    fun toUri(action: Action): Uri {
        val builder = Uri.Builder()
            .scheme(SCHEME)
            .authority(HOST)

        when (action) {
            is Action.Colorizer -> {
                builder.appendPath("colorizer")
                builder.appendQueryParameter("on", if (action.enabled) "1" else "0")
                action.serverUrl?.takeIf { it.isNotBlank() }?.let { builder.appendQueryParameter("url", it) }
                action.colorize?.let { builder.appendQueryParameter("c", if (it) "1" else "0") }
                action.upscale?.let { builder.appendQueryParameter("u", if (it) "1" else "0") }
                action.denoise?.let { builder.appendQueryParameter("d", if (it) "1" else "0") }
                action.upscaleFactor?.let { builder.appendQueryParameter("up", it.toString()) }
                action.denoiseSigma?.let { builder.appendQueryParameter("sig", it.toString()) }
                action.device?.takeIf { it.isNotBlank() }?.let { builder.appendQueryParameter("dev", it) }
                action.compare?.let { builder.appendQueryParameter("cmp", if (it) "1" else "0") }
            }
            is Action.Manga -> {
                builder.appendPath("manga")
                builder.appendQueryParameter("id", action.mangaId.toString())
                builder.appendQueryParameter("resume", if (action.resume) "1" else "0")
            }
            is Action.Profile -> {
                builder.appendPath("profile")
                builder.appendQueryParameter("name", action.profile.key)
            }
            is Action.Incognito -> {
                builder.appendPath("incognito")
                if (action.mode != IncognitoMode.TOGGLE) {
                    builder.appendQueryParameter("mode", action.mode.key)
                }
            }
            is Action.Search -> {
                builder.appendPath("search")
                builder.appendQueryParameter("q", action.query)
            }
            is Action.Sync -> {
                builder.appendPath("sync")
                action.categoryId?.let { builder.appendQueryParameter("cat", it.toString()) }
            }
            Action.ContinueLastRead -> {
                builder.appendPath("continue")
            }
        }

        return builder.build()
    }

    fun fromColorizerSettings(
        enabled: Boolean,
        settings: ColorizerSettings,
        compare: Boolean,
    ): Action.Colorizer {
        return Action.Colorizer(
            enabled = enabled,
            serverUrl = settings.serverUrl.takeIf { it.isNotBlank() },
            colorize = settings.colorize,
            upscale = settings.upscale,
            denoise = settings.denoise,
            upscaleFactor = settings.upscaleFactor,
            denoiseSigma = settings.denoiseSigma,
            device = settings.device,
            compare = compare,
        )
    }

    fun parse(uri: Uri?): Action? {
        if (uri == null || uri.scheme != SCHEME || uri.host != HOST) return null
        val path = uri.pathSegments.firstOrNull() ?: return null

        return when (path) {
            "colorizer" -> Action.Colorizer(
                enabled = uri.getBooleanParam("on") ?: true,
                serverUrl = uri.getQueryParameter("url"),
                colorize = uri.getBooleanParam("c"),
                upscale = uri.getBooleanParam("u"),
                denoise = uri.getBooleanParam("d"),
                upscaleFactor = uri.getQueryParameter("up")?.toIntOrNull(),
                denoiseSigma = uri.getQueryParameter("sig")?.toIntOrNull(),
                device = uri.getQueryParameter("dev"),
                compare = uri.getBooleanParam("cmp"),
            )
            "manga" -> {
                val id = uri.getQueryParameter("id")?.toLongOrNull() ?: return null
                val resume = uri.getBooleanParam("resume") ?: true
                Action.Manga(mangaId = id, resume = resume)
            }
            "profile" -> {
                val profile = ReadingProfile.fromKey(uri.getQueryParameter("name")) ?: return null
                Action.Profile(profile)
            }
            "incognito" -> Action.Incognito(
                mode = IncognitoMode.fromKey(uri.getQueryParameter("mode")),
            )
            "search" -> {
                val query = uri.getQueryParameter("q")?.trim().orEmpty()
                if (query.isEmpty()) null else Action.Search(query)
            }
            "sync" -> Action.Sync(
                categoryId = uri.getQueryParameter("cat")?.toLongOrNull(),
            )
            "continue" -> Action.ContinueLastRead
            else -> null
        }
    }

    fun extractUriFromIntent(intent: Intent?): Uri? {
        if (intent == null) return null

        if (intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED) {
            val messages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            }
            messages?.forEach { raw ->
                val message = raw as? NdefMessage ?: return@forEach
                for (record in message.records) {
                    val uri = record.toUri()
                    if (uri != null && uri.scheme == SCHEME && uri.host == HOST) {
                        return uri
                    }
                }
            }
        }

        val data = intent.data
        return if (data != null && data.scheme == SCHEME && data.host == HOST) data else null
    }

    fun hashTagUid(tag: Tag): String? {
        val id = tag.id ?: return null
        if (id.isEmpty()) return null
        return sha256Hex(id)
    }

    fun extractTagUidHash(intent: Intent?): String? {
        if (intent == null) return null
        val idBytes = intent.getByteArrayExtra(NfcAdapter.EXTRA_ID)
            ?: extractTag(intent)?.id
            ?: return null
        if (idBytes.isEmpty()) return null
        return sha256Hex(idBytes)
    }

    private fun extractTag(intent: Intent): Tag? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    private fun Uri.getBooleanParam(key: String): Boolean? {
        return when (getQueryParameter(key)?.lowercase()) {
            "1", "true" -> true
            "0", "false" -> false
            else -> null
        }
    }
}
