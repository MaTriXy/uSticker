package com.crossbowffs.usticker

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.ProgressDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceFragment
import android.provider.DocumentsContract
import android.text.Html
import android.util.Log
import android.widget.Toast

class SettingsFragment : PreferenceFragment() {
    companion object {
        private const val REQUEST_SELECT_STICKER_DIR = 2333
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode != Activity.RESULT_OK || data == null) {
            return
        }

        if (requestCode != REQUEST_SELECT_STICKER_DIR) {
            return
        }

        val flags = Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (data.flags and flags != flags) {
            Klog.e("FLAG_GRANT_PERSISTABLE_URI_PERMISSION or FLAG_GRANT_READ_URI_PERMISSION not set")
            Toast.makeText(activity, R.string.failed_to_obtain_read_permissions, Toast.LENGTH_SHORT).show()
            return
        }

        try {
            StickerDir.set(activity, data.data!!)
        } catch (e: SecurityException) {
            showStacktraceDialog(e)
            return
        }

        importStickers()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.settings)

        findPreference("pref_import_stickers").setOnPreferenceClickListener {
            importStickers()
            true
        }

        findPreference("pref_change_sticker_dir").setOnPreferenceClickListener {
            selectStickerDir(REQUEST_SELECT_STICKER_DIR)
            true
        }

        findPreference("pref_about_help").setOnPreferenceClickListener {
            showHelpDialog()
            true
        }

        findPreference("pref_about_developer").setOnPreferenceClickListener {
            startBrowserActivity("https://twitter.com/crossbowffs")
            true
        }

        findPreference("pref_about_github").setOnPreferenceClickListener {
            startBrowserActivity("https://github.com/apsun/uSticker")
            true
        }

        findPreference("pref_about_version").apply {
            setSummary("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            setOnPreferenceClickListener {
                showChangelogDialog()
                true
            }
        }
    }

    private fun startBrowserActivity(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }

    private fun getHtmlString(resId: Int): CharSequence {
        return Html.fromHtml(getString(resId))
    }

    private fun showHelpDialog() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.help)
            .setMessage(getHtmlString(R.string.help_text))
            .setPositiveButton(R.string.got_it, null)
            .show()
    }

    private fun showChangelogDialog() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.changelog)
            .setMessage(getHtmlString(R.string.changelog_text))
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(null, text)
        clipboard.setPrimaryClip(clip)
    }

    private fun showStacktraceDialog(e: Throwable) {
        val stacktrace = Log.getStackTraceString(e)
        AlertDialog.Builder(activity)
            .setTitle(R.string.import_failed_title)
            .setMessage(getString(R.string.import_failed_message) + stacktrace)
            .setNeutralButton(R.string.copy) { _, _ ->
                copyToClipboard(stacktrace)
                Toast.makeText(activity, R.string.stacktrace_copied, Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun onImportSuccess(dialog: Dialog, numStickers: Int) {
        dialog.dismiss()
        Klog.i("Successfully imported $numStickers stickers")
        val message = getString(R.string.import_success_toast, numStickers)
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    private fun onNeedInitStickerDir() {
        Toast.makeText(activity, R.string.init_sticker_dir, Toast.LENGTH_SHORT).show()
        selectStickerDir(REQUEST_SELECT_STICKER_DIR)
    }

    private fun onImportFailed(dialog: Dialog, err: Exception) {
        Klog.e("Failed to import stickers", err)
        dialog.dismiss()

        if (err is SecurityException) {
            onNeedInitStickerDir()
            return
        }

        showStacktraceDialog(err)
    }

    private fun selectStickerDir(requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val stickerDir = StickerDir.get(activity)
            if (stickerDir != null) {
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, stickerDir)
            }
        }
        startActivityForResult(intent, requestCode)
    }

    private fun importStickers() {
        val stickerDir = StickerDir.get(activity)
        if (stickerDir == null) {
            Klog.i("Sticker directory not configured")
            onNeedInitStickerDir()
            return
        }

        val dialog = ProgressDialog(activity).apply {
            setIndeterminate(true)
            setCancelable(false)
            setMessage(getString(R.string.importing_stickers))
            show()
        }

        StickerScanner(activity.contentResolver).executeWithCallback(stickerDir) { scanResult ->
            when (scanResult) {
                is Result.Err -> onImportFailed(dialog, scanResult.err)
                is Result.Ok -> FirebaseIndexUpdater().executeWithCallback(scanResult.value) { updateResult ->
                    when (updateResult) {
                        is Result.Err -> onImportFailed(dialog, updateResult.err)
                        is Result.Ok -> onImportSuccess(dialog, updateResult.value)
                    }
                }
            }
        }
    }
}
