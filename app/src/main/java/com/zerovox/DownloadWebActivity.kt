package com.zerovox

import android.content.Intent
import android.graphics.Typeface
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
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.RadioGroup
import android.widget.RadioButton
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.Executors
import org.json.JSONObject

class DownloadWebActivity : ComponentActivity() {
    private lateinit var web: WebView
    private val enqueued = HashSet<String>()
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var prefs: Prefs
    private lateinit var api: Ps5Client
    private val bridgeToken = UUID.randomUUID().toString().replace("-", "")
    private var capturedLink = false
    private var capturedName = ""

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

        web.addJavascriptInterface(DownloadBridge(), "ZeroVoxBridge")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.isForMainFrame) {
                    val target = request.url.toString()
                    if (isLikelyDownload(target) && !capturedLink) {
                        handleCapturedLink(target)
                        return true
                    }
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                val host = runCatching { Uri.parse(url.orEmpty()).host.orEmpty() }.getOrDefault("")
                if (host.endsWith("akirabox.com") || host.endsWith("akirabox.to")) {
                    installDownloadWatcher()
                }
            }
        }
        web.webChromeClient = object : WebChromeClient() {}
        web.setDownloadListener(DownloadListener { downloadUrl, userAgent, contentDisposition, _, contentLength ->
            if (!enqueued.add(downloadUrl)) return@DownloadListener
            when (mode) {
                "ps5" -> showStoragePicker(downloadUrl, contentLength, contentDisposition)
                else -> startLocalTransfer(downloadUrl, contentDisposition, contentLength)
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
    private fun startLocalTransfer(
        downloadUrl: String,
        contentDisposition: String?,
        contentLength: Long = 0L,
        suggestedName: String = "",
    ) {
        val title = intent.getStringExtra("title").orEmpty()
        val nameFromIntent = intent.getStringExtra("filename").orEmpty()
        // Prefer the real file name from the download link/page title so the
        // saved path is not polluted with the catalog game name or version.
        val realName = guessName(downloadUrl, contentDisposition)
        val name = when {
            hasDownloadExt(realName) -> realName
            hasDownloadExt(suggestedName) -> sanitizeName(suggestedName)
            hasDownloadExt(nameFromIntent) -> sanitizeName(nameFromIntent)
            else -> realName
        }
        val intentSize = intent.getLongExtra("size", 0L)
        val size = if (intentSize > 0) intentSize else contentLength
        val cookie = cookieHeaderFor(downloadUrl)
        val ua = if (::web.isInitialized) web.settings.userAgentString else ""
        val origin = if (::web.isInitialized) web.url.orEmpty() else ""

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
            putExtra("origin", origin)
        }
        ContextCompat.startForegroundService(this, svc)
        setResult(RESULT_OK)
        finish()
    }

    private fun showStoragePicker(
        downloadUrl: String,
        contentLength: Long = 0L,
        contentDisposition: String? = null,
        suggestedName: String = "",
    ) {
        if (!prefs.paired) {
            toast(getString(R.string.pair_required))
            finish()
            return
        }
        val dialog = android.app.Dialog(this)
        dialog.window?.setBackgroundDrawable(ColorDrawable(0x00000000))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassDrawable(28)
            setPadding(dpx(20), dpx(18), dpx(20), dpx(16))
        }
        col.addView(titleTextLocal(getString(R.string.choose_storage), 20f))
        col.addView(spacerView(10))

        // One radio per storage — the whole group is one choice for the user.
        val uncheckedColor = 0xFF8A96AC.toInt()
        val checkedColor = 0xFF53F2B4.toInt()
        val radioTint = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(checkedColor, uncheckedColor)
        )
        val storageGroup = RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(storageGroup)

        val info = TextView(this).apply {
            text = getString(R.string.storage_loading)
            setTextColor(0xFF8A96AC.toInt())
            textSize = 13f
            setPadding(dpx(2), dpx(8), dpx(2), 0)
        }
        col.addView(info)
        col.addView(spacerView(16))

        fun sendToStorage(storageId: String, cookie: String, ua: String, referer: String) {
            io.execute {
                val guessed = guessName(downloadUrl, contentDisposition)
                val filename = when {
                    hasDownloadExt(guessed) -> guessed
                    hasDownloadExt(suggestedName) -> sanitizeName(suggestedName)
                    else -> guessed
                }
                val title = intent.getStringExtra("title").orEmpty()
                val password = intent.getStringExtra("password").orEmpty()
                val intentSize = intent.getLongExtra("size", 0L)
                // Prefer catalog-known size; fall back to browser Content-Length.
                val size = if (intentSize > 0) intentSize else contentLength
                val req = JSONObject()
                    .put("url", downloadUrl)
                    .put("filename", filename)
                    .put("title", title)
                    .put("password", password)
                    .put("size", size)
                    .put("storageId", storageId)
                    .put("cookie", cookie)
                    .put("userAgent", ua)
                    .put("referer", referer)
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
            }
        }

        val confirmBtn = actionButtonLocal(getString(R.string.confirm_transfer), true) {
            // WebView access must stay on the main thread.
            val ua = if (::web.isInitialized) web.settings.userAgentString else ""
            val referer = if (::web.isInitialized) web.url.orEmpty() else ""
            val cookie = cookieHeaderFor(downloadUrl)
            dialog.dismiss()
            val checkedId = storageGroup.checkedRadioButtonId
            val storageId = if (checkedId != -1) {
                val rb = storageGroup.findViewById<RadioButton>(checkedId)
                (rb?.tag as? Storage)?.id ?: prefs.storageId.ifBlank { "data" }
            } else {
                prefs.storageId.ifBlank { "data" }
            }
            sendToStorage(storageId, cookie, ua, referer)
        }
        confirmBtn.isEnabled = false

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(
            actionButtonLocal(getString(R.string.cancel), false) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        btnRow.addView(View(this), LinearLayout.LayoutParams(dpx(10), 1))
        btnRow.addView(
            confirmBtn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        col.addView(btnRow)
        dialog.setContentView(col)
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9f).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.decorView?.layoutDirection = resources.configuration.layoutDirection

        // Read the real storage list as soon as the dialog opens.
        io.execute {
            val list = Ps5Client.parseStorage(api.storage())
            runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                storageGroup.removeAllViews()
                fun addRow(s: Storage, preferredId: String, checkWhenAbsent: Boolean): Boolean {
                    val label = if (s.freeBytes > 0) {
                        val name = if (s.external) s.id.uppercase() else getString(R.string.storage_internal)
                        "$name · ${Ps5Client.formatBytes(s.freeBytes)} ${getString(R.string.storage_free)}"
                    } else {
                        if (s.external) s.id.uppercase() else getString(R.string.storage_internal)
                    }
                    val rb = RadioButton(this).apply {
                        text = label
                        textSize = 14f
                        setTextColor(0xFFE8EDF6.toInt())
                        setPadding(dpx(4), dpx(10), dpx(4), dpx(10))
                        buttonTintList = radioTint
                        tag = s
                    }
                    storageGroup.addView(rb)
                    if (s.id == preferredId) { rb.isChecked = true; return true }
                    if (checkWhenAbsent) { rb.isChecked = true }
                    return false
                }
                val preferredId = prefs.storageId.ifBlank { "data" }
                var matched = false
                if (list.isNullOrEmpty()) {
                    val internal = Storage("data", getString(R.string.storage_internal), "/data", 0, 0, false)
                    addRow(internal, preferredId, checkWhenAbsent = true)
                    info.text = getString(R.string.storage_unknown)
                } else {
                    val prefPresent = list.any { it.id == preferredId }
                    for ((i, s) in list.withIndex()) {
                        val checkFallback = !matched && !prefPresent && i == 0
                        if (addRow(s, preferredId, checkWhenAbsent = checkFallback)) matched = true
                    }
                    info.text = ""
                }
                if (storageGroup.checkedRadioButtonId == -1) {
                    (storageGroup.getChildAt(0) as? RadioButton)?.isChecked = true
                }
                confirmBtn.isEnabled = true
            }
        }
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

    private inner class DownloadBridge {
        @JavascriptInterface
        fun onDownloadLink(link: String, name: String, token: String) {
            if (token != bridgeToken) return
            runOnUiThread {
                if (name.isNotBlank() && capturedName.isBlank()) capturedName = name
                handleCapturedLink(link)
            }
        }
    }

    private fun handleCapturedLink(link: String) {
        val target = link.trim()
        if (capturedLink || !target.startsWith("http")) return
        capturedLink = true
        if (modeIsPs5()) {
            showStoragePicker(target, 0L, null, capturedName)
        } else {
            startLocalTransfer(target, null, 0L, capturedName)
        }
    }

    private fun modeIsPs5(): Boolean = intent.getStringExtra("mode") == "ps5"

    private fun installDownloadWatcher() {
        val token = bridgeToken
        val js = """
            (function(){
              if (window.__zvWatcher) return;
              window.__zvWatcher = true;
              var token = '$token';
              function pageName(){
                try {
                  var t = (document.title || '').trim();
                  t = t.split(' · ')[0].split(' | ')[0].trim();
                  if (/\.[A-Za-z0-9]{2,6}$/.test(t)) return t;
                } catch(e) {}
                return '';
              }
              function report(u){
                if (!u) return;
                u = String(u);
                if (!/^https?:\/\//i.test(u)) return;
                try { ZeroVoxBridge.onDownloadLink(u, pageName(), token); } catch(e) {}
              }
              function check(){
                try {
                  var a = document.getElementById('download');
                  if (!a) return;
                  var h = a.getAttribute('href') || '';
                  if (h && h !== '#' && /^https?:\/\//i.test(h)) report(h);
                } catch(e) {}
              }
              try {
                new MutationObserver(check).observe(document.documentElement, {
                  subtree: true, childList: true, attributes: true, attributeFilter: ['href']
                });
              } catch(e) {}
              document.addEventListener('click', function(e){
                try {
                  var a = e.target && e.target.closest ? e.target.closest('#download') : null;
                  if (!a) return;
                  var h = a.getAttribute('href') || '';
                  if (/^https?:\/\//i.test(h)) {
                    e.preventDefault();
                    e.stopPropagation();
                    report(h);
                  }
                } catch(err) {}
              }, true);
              setInterval(check, 1500);
              check();
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    private fun isLikelyDownload(url: String): Boolean {
        val s = url.lowercase()
        if (s.startsWith("blob:") || s.startsWith("data:")) return false
        if (s.contains("/download") || s.contains("token=")) return true
        return Regex("\\.(pkg|ffpfsc|ffp|exfat|zip|rar|7z|iso|bin|tar|gz|001|002|003)(\\?|#|$)")
            .containsMatchIn(s)
    }

    private val downloadExts = setOf(
        "pkg", "ffpfsc", "ffp", "exfat", "zip", "rar", "7z", "iso",
        "bin", "tar", "gz", "001", "002", "003"
    )

    private fun hasDownloadExt(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in downloadExts

    private fun sanitizeName(raw: String): String {
        val decoded = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        return decoded.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
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
        return sanitizeName(raw).ifBlank { "download" }
    }

    private fun cookieHeaderFor(target: String): String {
        val cm = CookieManager.getInstance()
        val direct = cm.getCookie(target).orEmpty()
        val pageUrl = if (::web.isInitialized) web.url.orEmpty() else ""
        val page = if (pageUrl.isNotBlank() && pageUrl != target) {
            cm.getCookie(pageUrl).orEmpty()
        } else {
            ""
        }
        return when {
            direct.isBlank() -> page
            page.isBlank() -> direct
            else -> "$direct; $page"
        }
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
