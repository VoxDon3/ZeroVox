package com.orbit.zero

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import org.json.JSONObject

class DownloadWebActivity : ComponentActivity() {
    private lateinit var web: WebView
    private val enqueued = HashSet<String>()
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var prefs: Prefs
    private lateinit var api: Ps5Client

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        api = Ps5Client(prefs)
        val title = intent.getStringExtra("title").orEmpty()
        val url = intent.getStringExtra("url").orEmpty()
        val mode = intent.getStringExtra("mode").orEmpty()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF07090E.toInt())
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpx(14), dpx(14), dpx(14), dpx(6))
        }
        val heading = TextView(this).apply {
            text = title.ifBlank { getString(R.string.phone) }
            setTextColor(0xFFE8EDF6.toInt())
            textSize = 16f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        header.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(smallButton(getString(R.string.open_in_browser)) {
            val target = if (::web.isInitialized) web.url else url
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target ?: url))) }
        })
        header.addView(smallButton(getString(R.string.close)) { finish() })
        root.addView(header)

        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.loadsImagesAutomatically = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.userAgentString =
            "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        s.setSupportMultipleWindows(false)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
        }
        web.webChromeClient = object : WebChromeClient() {}
        web.setDownloadListener(DownloadListener { downloadUrl, userAgent, contentDisposition, _, _ ->
            if (!enqueued.add(downloadUrl)) return@DownloadListener
            when (mode) {
                "ps5" -> showStoragePicker(downloadUrl)
                else -> startLocalTransfer(downloadUrl, contentDisposition)
            }
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::web.isInitialized && web.canGoBack()) {
                    web.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (url.isBlank()) {
            Toast.makeText(this, getString(R.string.browser_required), Toast.LENGTH_SHORT).show()
        } else {
            web.loadUrl(url)
        }
    }

    /**
     * Phone download: the browser has captured the real file link (after the user
     * passes the site verification). Hand it to TransferService in "local" mode so
     * it downloads to Download/ZeroVox with live progress shown in Transfers.
     */
    private fun startLocalTransfer(downloadUrl: String, contentDisposition: String?) {
        val title = intent.getStringExtra("title").orEmpty()
        val nameFromIntent = intent.getStringExtra("filename").orEmpty()
        // Prefer the real file name from the download link so the saved path is
        // not polluted with the catalog game name or version.
        val realName = guessName(downloadUrl, contentDisposition)
        val name = if (!realName.contains('.') && nameFromIntent.contains('.')) {
            nameFromIntent
        } else {
            realName
        }
        val size = intent.getLongExtra("size", 0L)
        val cookie = CookieManager.getInstance().getCookie(downloadUrl).orEmpty()
        val ua = if (::web.isInitialized) web.settings.userAgentString else ""

        // Show the card instantly, before the first byte is fetched.
        TransferStore.reset(title.ifBlank { name }, name, size)
        TransferStore.update(state = "preparing")

        val svc = Intent(this, TransferService::class.java).apply {
            putExtra("mode", "local")
            putExtra("title", title)
            putExtra("url", downloadUrl)
            putExtra("filename", name)
            putExtra("size", size)
            putExtra("cookie", cookie)
            putExtra("userAgent", ua)
        }
        ContextCompat.startForegroundService(this, svc)
        setResult(RESULT_OK)
        finish()
    }

    private fun showStoragePicker(downloadUrl: String) {
        val dialog = android.app.Dialog(this)
        dialog.window?.setBackgroundDrawable(ColorDrawable(0x00000000))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassDrawable(28)
            setPadding(dpx(20), dpx(20), dpx(20), dpx(18))
        }
        col.addView(titleTextLocal(getString(R.string.choose_storage), 20f))
        col.addView(spacerView(12))

        val storageGroup = RadioGroup(this)
        storageGroup.orientation = LinearLayout.VERTICAL

        val internalRb = RadioButton(this).apply {
            text = getString(R.string.storage_internal)
            isChecked = true
        }
        val usbRb = RadioButton(this).apply {
            text = "USB0"
        }
        storageGroup.addView(internalRb)
        col.addView(storageGroup)
        col.addView(usbRb)

        col.addView(spacerView(18))

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(actionButtonLocal(getString(R.string.cancel), false) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        btnRow.addView(View(this), LinearLayout.LayoutParams(dpx(10), 1))
        btnRow.addView(actionButtonLocal(getString(R.string.download_ps5), true) {
            val wantUsb = usbRb.isChecked
            dialog.dismiss()
            io.execute {
                val list = Ps5Client.parseStorage(api.storage())
                runOnUiThread {
                    val storage = if (wantUsb) {
                        list.firstOrNull { it.id == "usb0" } ?: list.firstOrNull { !it.external }
                    } else {
                        list.firstOrNull { it.id == prefs.storageId } ?: list.firstOrNull { !it.external }
                    }
                    if (storage != null) {
                        val filename = downloadUrl.substringAfterLast('/').ifBlank { "download" }
                        val req = JSONObject()
                            .put("url", downloadUrl)
                            .put("filename", filename)
                            .put("size", 0)
                            .put("storageId", storage.id)
                        val r = api.call("POST", "/api/v1/downloads", req, auth = true)
                        runOnUiThread {
                            if (r.ok) {
                                toast(getString(R.string.added_to_queue))
                                setResult(RESULT_OK)
                                finish()
                            } else {
                                toast(getString(R.string.start_failed, r.message()))
                            }
                        }
                    } else {
                        runOnUiThread { toast(getString(R.string.storage_error)) }
                    }
                }
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(btnRow)
        dialog.setContentView(col)
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9f).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.decorView?.layoutDirection = resources.configuration.layoutDirection
    }

    private fun titleTextLocal(text: String, size: Float): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(0xFFE8EDF6.toInt())
            setPadding(dpx(0), dpx(0), dpx(0), dpx(6))
        }

    private fun spacerView(h: Int): View =
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpx(h))
        }

    private fun guessName(url: String, contentDisposition: String?): String {
        val cd = contentDisposition.orEmpty()
        var raw: String? = Regex("filename\\*\\s*=\\s*[^']*''([^;]+)", RegexOption.IGNORE_CASE)
            .find(cd)?.groupValues?.get(1)?.trim()
        if (raw.isNullOrBlank()) {
            raw = Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
                .find(cd)?.groupValues?.get(1)?.trim()
        }
        if (raw.isNullOrBlank()) {
            raw = runCatching { URLUtil.guessFileName(url, contentDisposition, null) }.getOrNull()
        }
        if (raw.isNullOrBlank()) raw = url.substringBefore('?').substringAfterLast('/')
        if (raw.isNullOrBlank()) raw = "download"
        val decoded = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        return decoded.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "download" }
    }

    private fun smallButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(0xFF53F2B4.toInt())
            background = null
            setPadding(dpx(10), dpx(4), dpx(10), dpx(4))
            setOnClickListener { onClick() }
        }

    private fun dpx(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun actionButtonLocal(label: String, primary: Boolean, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(if (primary) 0xFF53F2B4.toInt() else 0xFF8A96AC.toInt())
            background = null
            setPadding(dpx(12), dpx(6), dpx(12), dpx(6))
            setOnClickListener { onClick() }
        }
}
