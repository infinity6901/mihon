package eu.kanade.tachiyomi.ui.security

import android.os.Bundle
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import eu.kanade.tachiyomi.data.nfc.NfcTagWriter
import eu.kanade.tachiyomi.data.nfc.NfcUri
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.base.delegate.SecureActivityDelegate
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil.startAuthentication
import eu.kanade.tachiyomi.util.system.toast
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR

/**
 * Blank activity with a BiometricPrompt, also supporting an optional paired NFC unlock key.
 */
class UnlockActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startAuthentication(
            stringResource(MR.strings.unlock_app_title, stringResource(MR.strings.app_name)),
            confirmationRequired = false,
            callback = object : AuthenticatorUtil.AuthenticationCallback() {
                override fun onAuthenticationError(
                    activity: FragmentActivity?,
                    errorCode: Int,
                    errString: CharSequence,
                ) {
                    super.onAuthenticationError(activity, errorCode, errString)
                    logcat(LogPriority.ERROR) { errString.toString() }
                    finishAffinity()
                }

                override fun onAuthenticationSucceeded(
                    activity: FragmentActivity?,
                    result: BiometricPrompt.AuthenticationResult,
                ) {
                    super.onAuthenticationSucceeded(activity, result)
                    SecureActivityDelegate.unlock()
                    finish()
                }
            },
        )
    }

    override fun onResume() {
        super.onResume()
        val expectedHash = appGraph.securityPreferences.nfcUnlockTagHash.get()
        if (expectedHash.isNotBlank()) {
            NfcTagWriter.enableReaderMode(this) { tag ->
                val hash = NfcUri.hashTagUid(tag) ?: return@enableReaderMode
                if (hash == expectedHash) {
                    runOnUiThread {
                        SecureActivityDelegate.unlock()
                        toast(stringResource(MR.strings.nfc_unlocked))
                        finish()
                    }
                }
            }
        }
    }

    override fun onPause() {
        NfcTagWriter.disableReaderMode(this)
        super.onPause()
    }
}
