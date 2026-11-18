package eu.kanade.tachiyomi.data.nfc

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable

object NfcTagWriter {

    private const val READER_FLAGS =
        NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_NFC_BARCODE

    sealed interface WriteResult {
        data object Success : WriteResult
        data object NotWritable : WriteResult
        data class TagTooSmall(val neededBytes: Int, val maxBytes: Int) : WriteResult
        data object UnsupportedTag : WriteResult
        data class Error(val message: String) : WriteResult
    }

    fun getAdapter(context: Context): NfcAdapter? = NfcAdapter.getDefaultAdapter(context)

    fun isAvailable(context: Context): Boolean = getAdapter(context) != null

    fun isEnabled(context: Context): Boolean = getAdapter(context)?.isEnabled == true

    fun enableReaderMode(activity: Activity, onTag: (Tag) -> Unit): Boolean {
        val adapter = getAdapter(activity) ?: return false
        if (!adapter.isEnabled) return false
        adapter.enableReaderMode(activity, { tag -> onTag(tag) }, READER_FLAGS, null)
        return true
    }

    fun disableReaderMode(activity: Activity) {
        runCatching { getAdapter(activity)?.disableReaderMode(activity) }
    }

    fun writeUri(tag: Tag, uri: Uri): WriteResult {
        val message = NdefMessage(arrayOf(NdefRecord.createUri(uri)))
        val size = message.toByteArray().size

        return try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.use { tech ->
                    tech.connect()
                    if (!tech.isWritable) {
                        return WriteResult.NotWritable
                    }
                    if (tech.maxSize < size) {
                        return WriteResult.TagTooSmall(neededBytes = size, maxBytes = tech.maxSize)
                    }
                    tech.writeNdefMessage(message)
                }
                WriteResult.Success
            } else {
                val formatable = NdefFormatable.get(tag) ?: return WriteResult.UnsupportedTag
                formatable.use { tech ->
                    tech.connect()
                    tech.format(message)
                }
                WriteResult.Success
            }
        } catch (e: Throwable) {
            WriteResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }
}
