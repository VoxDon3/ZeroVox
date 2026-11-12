package com.zerovox

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.net.Uri
import android.content.ContentValues
import android.provider.MediaStore
import android.util.Log
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.TextViewCompat
import android.util.TypedValue

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import coil.load
import org.json.JSONObject
import java.util.concurrent.Executors
import java.io.File
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    companion object {
        private var restoreTab: String? = null
    }

    private lateinit var prefs: Prefs
    private lateinit var api: Ps5Client
    private lateinit var cheatApi: CheatRunnerClient
    private val io = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    private val webDownloadLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            setTab("downloads")
        }
    }

    private lateinit var content: FrameLayout
    private lateinit var navBar: LinearLayout
    private lateinit var statusOrb: ImageView
    private lateinit var statusName: ImageView
    private val navItems = LinkedHashMap<String, TextView>()

    private var tab = "catalog"
    private var catalogAdapter: CatalogAdapter? = null
    private var catalogCount: TextView? = null
    private var catalogEmpty: TextView? = null
    private var catalogList: RecyclerView? = null
    private var catalogRetry: Button? = null
    private var catalogLoading = false
    private var catalogLoadingId: String? = null
    private var catalogLoadToken = 0
    private val catalogSearchHandler = Handler(Looper.getMainLooper())
    private val catalogSearchRunnable = Runnable { applyCatalogFilter() }
    private var query = ""
    private var platform = "all"
    private var refreshRunnable: Runnable? = null
    private var refreshing = false
    private var connectionCheckRunning = false
    private val coverLookupKeys = mutableSetOf<String>()
    private var setupAfterPair = "catalog"

    private var filesStorage = "data"
    private var filesPath = ""

    private var cheatGames: List<CheatGame> = emptyList()
    private var cheatsQuery = ""
    private var cheatsLoading = false
    private var cheatsError: String? = null
    private var cheatsRetries = 0
    private var cheatsSections: LinearLayout? = null
    private var cheatsStatus: TextView? = null
    private var cheatsRefresh: Button? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val startupPrefs = getSharedPreferences("zerovox_prefs", MODE_PRIVATE)
        setTheme(if (startupPrefs.getBoolean("lightTheme", false)) R.style.Theme_ZeroVox_Light else R.style.Theme_ZeroVox)
        installCrashLogger()
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        TransferStore.init(this)
        CatalogRepo.setCurrentCatalog(prefs.catalogId)
        Palette.applyTheme(prefs.lightTheme)
        Haptics.enabled = prefs.haptics
        api = Ps5Client(prefs)
        cheatApi = CheatRunnerClient(prefs)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        requestNotificationPermission()
        ensureStorageAccess()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                confirmExit()
            }
        })
        buildShell()
        val target = restoreTab
        restoreTab = null
        when {
            target == "downloads" -> showDownloads()
            target == "console" -> showConsole()
            target == "cheats" -> showCheats()
            target == "setup" -> showSetup()
            !prefs.paired -> showSetup()
            else -> showCatalog()
        }
        loadCatalog(false)
        io.execute {
            CatalogRepo.loadHomebrew(this) {
                runOnUiThread { if (tab == "downloads") showDownloads() }
            }
        }
        bootstrapAgent()
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrashLog(thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(thread: Thread, throwable: Throwable) {
        val sb = StringBuilder()
        sb.append("thread=${thread.name}\n")
        var t: Throwable? = throwable
        var depth = 0
        while (t != null && depth < 12) {
            sb.append("==== ${t.javaClass.name}: ${t.message}\n")
            for (element in t.stackTrace) sb.append("\tat $element\n")
            val cause = t.cause
            if (cause === t) break
            t = cause
            depth++
        }
        val text = sb.toString()
        Log.e("ZeroVoxCrash", text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "zerovox_crash.txt")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        } else {
            runCatching {
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "zerovox_crash.txt"
                ).writeText(text)
            }
        }
    }

    private fun bootstrapAgent() {
        // Inject the agent on every app open when the host is set, even after pairing
        // (e.g. the PS5 rebooted and the agent died). ensureRunning is a no-op when
        // the agent is already listening on 33333.
        if (!prefs.hostConfigured || !prefs.autoLoadAgent) return
        io.execute {
            val host = prefs.host
            val started = AgentLoader.ensureRunning(this@MainActivity, host)
            runOnUiThread {
                if (started && !prefs.paired) {
                    val r = api.autoPair()
                    runOnUiThread {
                        if (r.ok) {
                            val token = r.json()?.optString("token") ?: ""
                            if (token.length == 64) {
                                prefs.token = token
                                refreshConnectionPill()
                                loadStorage { setTab("catalog") }
                    }
                } else if (started) {
                    refreshConnectionPill()
                }
            }
        }
    }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::statusOrb.isInitialized) refreshConnectionPill()
    }

    private fun confirmExit() {
        val dialog = Dialog(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpx(20), dpx(20), dpx(20), dpx(18))
            background = glassDrawable(26)
        }
        col.addView(titleText(getString(R.string.exit_title), 22f))
        col.addView(spacer(10))
        col.addView(bodyText(getString(R.string.exit_message), 15f))
        col.addView(spacer(18))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(actionButton(getString(R.string.cancel), false) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(View(this), LinearLayout.LayoutParams(dpx(10), 1))
        row.addView(actionButton(getString(R.string.exit_confirm), true) {
            dialog.dismiss()
            finish()
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row)

        dialog.setContentView(col)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.88f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.decorView?.layoutDirection = resources.configuration.layoutDirection
    }

    private fun confirmDeleteJob(job: Job) {
        val dialog = Dialog(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassDrawable(26)
            setPadding(dpx(20), dpx(20), dpx(20), dpx(18))
        }
        col.addView(titleText(getString(R.string.confirm_delete_title), 20f))
        col.addView(spacer(10))
        col.addView(bodyText(getString(R.string.confirm_delete_message, job.title), 15f))
        col.addView(spacer(18))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(actionButton(getString(R.string.confirm_delete_cancel), false) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(View(this), LinearLayout.LayoutParams(dpx(10), 1))
        row.addView(actionButton(getString(R.string.confirm_delete_confirm), true) {
            dialog.dismiss()
            runJobAction(job, "remove")
            toast(getString(R.string.deleted, job.title))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row)

        dialog.setContentView(col)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.85f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.decorView?.layoutDirection = resources.configuration.layoutDirection
    }

    private fun confirmDeletePhone(d: PhoneDownload) {
        val dialog = Dialog(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassDrawable(26)
            setPadding(dpx(20), dpx(20), dpx(20), dpx(18))
        }
        col.addView(titleText(getString(R.string.confirm_delete_title), 20f))
        col.addView(spacer(10))
        col.addView(bodyText(getString(R.string.confirm_delete_message, d.filename), 15f))
        col.addView(spacer(18))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(actionButton(getString(R.string.confirm_delete_cancel), false) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(View(this), LinearLayout.LayoutParams(dpx(10), 1))
        row.addView(actionButton(getString(R.string.confirm_delete_confirm), true) {
            dialog.dismiss()
            val uri = d.uri
            when {
                uri.startsWith("file://") -> {
                    runCatching {
                        val path = Uri.parse(uri).path
                        if (!path.isNullOrBlank()) File(path).delete()
                    }
                }
                uri.isNotBlank() -> {
                    runCatching { contentResolver.delete(Uri.parse(uri), null, null) }
                }
                d.path.isNotBlank() -> {
                    runCatching {
                        val name = d.filename
                        val dir = File(
                            Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS
                            ),
                            "ZeroVox"
                        )
                        File(dir, name).delete()
                    }
                }
            }
            /* Purge the DownloadManager database record so a failed download
             * disappears from the list instead of staying stuck. */
            if (d.downloadId >= 0) {
                runCatching {
                    val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    dm.remove(d.downloadId)
                }
            }
            toast(getString(R.string.deleted, d.filename))
            setTab("downloads")
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row)

        dialog.setContentView(col)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.85f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.decorView?.layoutDirection = resources.configuration.layoutDirection
    }

    private fun confirmDeleteTransfer(t: TransferState) {
        val dialog = Dialog(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassDrawable(26)
            setPadding(dpx(20), dpx(20), dpx(20), dpx(18))
        }
        col.addView(titleText(getString(R.string.confirm_delete_title), 20f))
        col.addView(spacer(10))
        col.addView(
            bodyText(getString(R.string.confirm_delete_message, t.title.ifBlank { t.filename }), 15f)
        )
        col.addView(spacer(18))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(actionButton(getString(R.string.confirm_delete_cancel), false) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(View(this), LinearLayout.LayoutParams(dpx(10), 1))
        row.addView(actionButton(getString(R.string.confirm_delete_confirm), true) {
            dialog.dismiss()
            if (t.running) {
                TransferStore.cancelRequested = true
                TransferStore.pauseRequested = false
            } else if (t.state == "paused" || (t.mode == "local" && t.state !in setOf("complete", "sent"))) {
                deletePartialTransfer(t)
            } else if (t.state == "complete" && t.uri.isNotBlank()) {
                deleteDownloadedFile(t.uri, t.filename)
            }
            TransferStore.clear()
            toast(getString(R.string.deleted, t.title.ifBlank { t.filename }))
            setTab("downloads")
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row)

        dialog.setContentView(col)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.85f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.decorView?.layoutDirection = resources.configuration.layoutDirection
    }

    private fun deletePartialTransfer(t: TransferState) {
        val uriStr = t.uri
        if (uriStr.startsWith("content:")) {
            runCatching { contentResolver.delete(Uri.parse(uriStr), null, null) }
            return
        }
        val file = when {
            uriStr.startsWith("file:") -> runCatching { Uri.parse(uriStr).path?.let { java.io.File(it) } }.getOrNull()
            t.path.isNotBlank() -> java.io.File(t.path)
            else -> null
        }
        if (file != null) runCatching { file.delete() }
    }

    private fun deleteDownloadedFile(uri: String, filename: String) {
        when {
            uri.startsWith("file://") -> runCatching {
                val path = Uri.parse(uri).path
                if (!path.isNullOrBlank()) File(path).delete()
            }
            uri.isNotBlank() -> runCatching { contentResolver.delete(Uri.parse(uri), null, null) }
            filename.isNotBlank() -> runCatching {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "ZeroVox"
                )
                File(dir, filename).delete()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRefresh()
        io.shutdownNow()
    }


    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
        }
    }

    private fun storageAccessGranted(): Boolean {
        // Android 13+ needs no storage permission for the app's own files in
        // Download/ZeroVox (they are accessed through MediaStore).
        if (Build.VERSION.SDK_INT >= 33) return true
        val read = checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val write = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
            return read && write
        }
        return read
    }

    private fun ensureStorageAccess() {
        if (storageAccessGranted()) return
        // Ask with the normal runtime-permission dialog, exactly like the
        // notification permission, instead of opening the settings screen.
        val perms = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPermissions(perms, 10)
    }

    @SuppressLint("SetTextI18n")
    private fun buildShell() {
        val isDark = Palette.dark
        val outer = FrameLayout(this).apply {
            setBackgroundResource(if (isDark) R.drawable.bg_app else R.drawable.bg_app_light)
        }
        val backdrop = ImageView(this).apply {
            setImageResource(R.drawable.bg_zero_vox)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = if (isDark) 0.72f else 0.18f
        }
        outer.addView(
            backdrop,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        val scrim = View(this).apply { setBackgroundResource(if (isDark) R.drawable.bg_scrim else R.drawable.bg_scrim_light) }
        outer.addView(
            scrim,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(
            root,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dpx(12), dpx(8), dpx(8), dpx(8))
        }
        val brandZero = TextView(this).apply {
            text = "ZERO"
            textSize = 15f
            setTextColor(Palette.neon)
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            letterSpacing = 0.08f
        }
        val brandVox = TextView(this).apply {
            text = "VOX"
            textSize = 15f
            setTextColor(Palette.text)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            letterSpacing = 0.08f
            setPadding(dpx(4), 0, 0, 0)
        }
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dpx(8), dpx(5), dpx(8), dpx(5))
            background = glassDrawable(34)
        }
        statusOrb = ImageView(this).apply {
            setImageResource(R.drawable.orb_checking)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dpx(24), dpx(24))
        }
        statusName = ImageView(this).apply {
            setImageResource(R.drawable.name_checking)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dpx(72), dpx(18)).apply {
                leftMargin = dpx(3)
            }
        }
        pill.addView(statusOrb)
        pill.addView(statusName)
        val settingsButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_settings)
            setColorFilter(Palette.text)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = getString(R.string.settings_title)
            setPadding(dpx(4), dpx(4), dpx(4), dpx(4))
            pressAnimation()
            setOnClickListener { showSettingsDialog() }
        }
        val logoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        logoRow.addView(brandZero)
        logoRow.addView(brandVox)
        header.addView(
            pill,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                leftMargin = dpx(6)
                rightMargin = dpx(10)
            }
        )
        header.addView(logoRow, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(
            settingsButton,
            LinearLayout.LayoutParams(dpx(36), dpx(36)).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        )
        root.addView(header)
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = glassDrawable(22)
            setPadding(dpx(6), dpx(6), dpx(6), dpx(6))
            visibility = View.GONE
        }
        root.addView(navBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dpx(16), dpx(4), dpx(16), dpx(14))
        })
        addNavItem("catalog", getString(R.string.nav_catalog))
        addNavItem("downloads", getString(R.string.nav_downloads))
        addNavItem("files", getString(R.string.nav_files))
        addNavItem("console", getString(R.string.nav_console))
        addNavItem("cheats", getString(R.string.nav_cheats))

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(0, bars.top, 0, maxOf(bars.bottom, ime.bottom))
            insets
        }
        setContentView(outer)
        window.decorView.layoutDirection = resources.configuration.layoutDirection
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !Palette.dark
            isAppearanceLightNavigationBars = !Palette.dark
        }
        updateConnectionPill()
    }

    private fun addNavItem(key: String, label: String) {
        val item = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 12f
            maxLines = 1
            isSingleLine = true
            includeFontPadding = false
            setPadding(dpx(2), 0, dpx(2), 0)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            pressAnimation()
            setOnClickListener { setTab(key) }
        }
        // Shrink to fit a single line; every cell has the same fixed height so
        // no item (e.g. "الترانسفير") is taller or split across two lines.
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            item, 8, 12, 1, TypedValue.COMPLEX_UNIT_SP
        )
        navItems[key] = item
        navBar.addView(item, LinearLayout.LayoutParams(0, dpx(46), 1f))
    }

    private fun setTab(key: String) {
        tab = key
        stopRefresh()
        for ((k, v) in navItems) {
            val selected = k == key
            v.background = chipDrawable(selected)
            v.setTextColor(if (selected) Palette.neon else Palette.textSecondary)
        }
        when (key) {
            "catalog" -> showCatalog()
            "downloads" -> showDownloads()
            "files" -> showFiles()
            "console" -> showConsole()
            "cheats" -> showCheats()
        }
    }

    private fun updateConnectionPill() {
        setConnectionPill(
            getString(if (prefs.paired) R.string.status_connected else R.string.status_unpaired),
            if (prefs.paired) Palette.success else Palette.danger,
            prefs.paired,
        )
    }

    private fun setConnectionPill(text: String, color: Int, connected: Boolean) {
        val checking = text == getString(R.string.status_checking)
        val orbRes = if (connected) R.drawable.orb_connected
                     else if (checking) R.drawable.orb_checking
                     else R.drawable.orb_unpaired
        val nameRes = if (connected) R.drawable.name_connected
                      else if (checking) R.drawable.name_checking
                      else R.drawable.name_unpaired
        statusOrb.setImageResource(orbRes)
        statusName.setImageResource(nameRes)
    }

    private fun refreshConnectionPill() {
        // The paired state is the source of truth for the pill. It must turn
        // green immediately after pairing and stay green even if a transient
        // health check fails (e.g. the console is busy or the network blips).
        updateConnectionPill()
        if (!prefs.paired) return
        if (connectionCheckRunning) return
        connectionCheckRunning = true
        io.execute {
            runCatching { api.system() }
            runOnUiThread {
                connectionCheckRunning = false
                updateConnectionPill()
            }
        }
    }

    private fun scrollContent(): LinearLayout {
        content.removeAllViews()
        val swipe = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(Palette.neon)
            setProgressBackgroundColorSchemeColor(0xFF0D1119.toInt())
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpx(20), dpx(6), dpx(20), dpx(28))
        }
        scroll.addView(col, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        swipe.addView(scroll, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        content.addView(swipe, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        swipe.setOnRefreshListener {
            swipe.postDelayed({
                swipe.isRefreshing = false
                refreshCurrentTab()
            }, 350)
        }
        return col
    }

    private fun refreshCurrentTab() {
        when (tab) {
            "catalog" -> loadCatalog(true)
            "downloads" -> showDownloads()
            "files" -> showFiles()
            "console" -> showConsole()
            "cheats" -> showCheats()
            else -> setTab(tab)
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------ setup

    @SuppressLint("SetTextI18n")
    private fun showAppmetaDump() {
        val dialog = Dialog(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassDrawable(26)
            setPadding(dpx(14), dpx(16), dpx(14), dpx(14))
        }
        col.addView(titleText("Debug · /user/appmeta", 19f))
        col.addView(spacer(10))
        val status = bodyText("")
        status.setTextColor(Palette.neon)
        col.addView(status)
        col.addView(spacer(10))
        val btn = actionButton("Close", true) { dialog.dismiss() }
        col.addView(btn)
        dialog.setContentView(col)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94f).toInt(),
            WindowManager.LayoutParams.MATCH_PARENT
        )
        dialog.window?.decorView?.layoutDirection = resources.configuration.layoutDirection
        status.text = "fetching…"
        io.execute {
            val r = api.call("GET", "/api/v1/debug/appmeta")
            runOnUiThread {
                val tv = TextView(this).apply {
                    text = if (r.ok) r.body else "error: " + r.message() + "\n" + r.body
                    setTextColor(0xFFE8EDF6.toInt())
                    textSize = 9.5f
                    typeface = Typeface.MONOSPACE
                    setTextIsSelectable(true)
                }
                val sv = ScrollView(this).apply {
                    addView(tv)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                    )
                }
                col.addView(sv, 1)
                col.removeView(btn)
                col.addView(btn)
            }
        }
    }

    private fun showSetup(afterPair: String = "catalog") {
        setupAfterPair = afterPair
        tab = "setup"
        stopRefresh()
        navBar.visibility = View.VISIBLE
        for ((_, v) in navItems) {
            v.background = chipDrawable(false)
            v.setTextColor(Palette.textSecondary)
        }
        val col = scrollContent()

        col.addView(titleText(getString(R.string.setup_title), 27f))
        col.addView(bodyText(getString(R.string.setup_subtitle)))
        col.addView(spacer(12))
        col.addView(actionButton(getString(R.string.browse_catalog), false) { showCatalog() })
        col.addView(spacer(8))
        col.addView(actionButton("Debug · dump /user/appmeta", false) { showAppmetaDump() })
        col.addView(spacer(18))

        val card = glassCard(20)
        card.addView(accentText(getString(R.string.label_console_address)))
        card.addView(spacer(6))

        val host = glassInput(getString(R.string.hint_ip)).apply {
            setText(prefs.host)
        }
        card.addView(host)
        card.addView(spacer(8))

        card.addView(spacer(12))

        val code = codeInput()
        card.addView(accentText(getString(R.string.label_pair_code)))
        card.addView(spacer(6))
        card.addView(code)
        card.addView(spacer(10))

        val status = bodyText(getString(R.string.setup_hint))
        status.setTextColor(Palette.neon)
        card.addView(status)
        card.addView(spacer(14))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val showCode = actionButton(getString(R.string.setup_show_code), false) {
            prefs.host = host.text.toString().trim()
            status.text = getString(R.string.setup_show_requested)
            io.execute {
                val r = api.showPairingCode()
                runOnUiThread {
                    status.text = if (r.ok) getString(R.string.setup_show_success)
                    else getString(R.string.setup_show_error, r.message())
                }
            }
        }
        val pair = actionButton(getString(R.string.pair_button), true) {
            val h = host.text.toString().trim()
            val c = code.text.toString().trim()
            if (h.isBlank() || c.length != 6) {
                status.text = getString(R.string.pair_invalid)
                return@actionButton
            }
            prefs.host = h
            status.text = getString(R.string.pairing)
            io.execute {
                val r = api.pair(c)
                val token = r.json()?.optString("token") ?: ""
                runOnUiThread {
                    if (r.ok && token.length == 64) {
                        prefs.token = token
                        refreshConnectionPill()
                        status.text = getString(R.string.pair_success)
                        loadStorage {
                            if (tab == "setup") setTab(setupAfterPair)
                        }
                    } else {
                        status.text = getString(R.string.pair_failed, r.message())
                    }
                }
            }
        }
        row.addView(pair, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(View(this), LinearLayout.LayoutParams(dpx(10), 1))
        row.addView(showCode, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row)
        col.addView(card)
        col.addView(spacer(16))

        val help = softCard(16)
        help.addView(accentText(getString(R.string.setup_tips_title)))
        help.addView(spacer(6))
        help.addView(bodyText(getString(R.string.setup_tips_body)))
        col.addView(help)
        col.addView(spacer(16))
    }

    // ---------------------------------------------------------------- catalog

    private fun showCatalog() {
        tab = "catalog"
        navBar.visibility = View.VISIBLE
        for ((k, v) in navItems) {
            val selected = k == "catalog"
            v.background = chipDrawable(selected)
            v.setTextColor(if (selected) Palette.neon else Palette.textSecondary)
        }
        content.removeAllViews()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpx(16), dpx(2), dpx(16), dpx(4))
        }
        val search = glassInput(getString(R.string.search_hint)).apply {
            setText(query)
            setSelection(text.length)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                    query = s?.toString() ?: ""
                    catalogSearchHandler.removeCallbacks(catalogSearchRunnable)
                    catalogSearchHandler.postDelayed(catalogSearchRunnable, 200)
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(search)
        root.addView(spacer(8))

        val catalogRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val catalogChipViews = LinkedHashMap<String, TextView>()
        fun styleCatalogChips() {
            for ((id, tv) in catalogChipViews) {
                val selected = id == CatalogRepo.currentCatalogId
                tv.background = chipDrawable(selected)
                tv.setTextColor(if (selected) Palette.neon else Palette.textSecondary)
            }
        }
        for (c in CatalogRepo.catalogs) {
            val tv = TextView(this).apply {
                text = c.name
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dpx(14), dpx(7), dpx(14), dpx(7))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = chipDrawable(c.id == CatalogRepo.currentCatalogId)
                setTextColor(
                    if (c.id == CatalogRepo.currentCatalogId) Palette.neon else Palette.textSecondary
                )
                pressAnimation()
                setOnClickListener {
                    if (CatalogRepo.currentCatalogId == c.id) return@setOnClickListener
                    CatalogRepo.setCurrentCatalog(c.id)
                    prefs.catalogId = c.id
                    styleCatalogChips()
                    loadCatalog(false)
                }
            }
            catalogChipViews[c.id] = tv
            catalogRow.addView(
                tv,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.rightMargin = dpx(6) }
            )
        }
        val catalogScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(catalogRow)
        }
        root.addView(
            catalogScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        catalogChipViews[CatalogRepo.currentCatalogId]?.let { selected ->
            catalogScroll.post {
                val target = (selected.left - dpx(16)).coerceAtLeast(0)
                catalogScroll.smoothScrollTo(target, 0)
            }
        }
        root.addView(spacer(8))

        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val chipViews = LinkedHashMap<String, TextView>()
        fun chip(key: String, label: String) {
            val tv = TextView(this).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dpx(14), dpx(7), dpx(14), dpx(7))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = chipDrawable(platform == key)
                setTextColor(if (platform == key) Palette.neon else Palette.textSecondary)
                pressAnimation()
                setOnClickListener {
                    platform = key
                    for ((k, v) in chipViews) {
                        v.background = chipDrawable(k == key)
                        v.setTextColor(if (k == key) Palette.neon else Palette.textSecondary)
                    }
                    applyCatalogFilter()
                }
            }
            chipViews[key] = tv
            chips.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                it.rightMargin = dpx(6)
            })
        }
        chip("all", getString(R.string.filter_all))
        chip("ps5", getString(R.string.download_ps5))
        chip("ps4", "PS4")
        root.addView(chips)
        root.addView(spacer(6))

        catalogCount = bodyText("")
        root.addView(catalogCount)
        root.addView(spacer(6))

        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(0, 0, 0, dpx(18))
        }
        catalogAdapter = CatalogAdapter(ArrayList()) { game -> showOptions(game) }
        list.adapter = catalogAdapter
        catalogList = list
        val catalogSwipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) > abs(dy) * 1.5f && abs(dx) > dpx(70) &&
                    abs(velocityX) > abs(velocityY)
                ) {
                    runOnUiThread { switchCatalog(if (dx < 0) 1 else -1) }
                    return true
                }
                return false
            }
        })
        list.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                catalogSwipe.onTouchEvent(e)
                return false
            }
        })
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        catalogEmpty = bodyText(getString(R.string.loading_catalog)).apply {
            gravity = Gravity.CENTER
            setPadding(dpx(20), dpx(30), dpx(20), dpx(30))
            setOnTouchListener { _, event ->
                catalogSwipe.onTouchEvent(event)
                false
            }
        }
        root.addView(
            catalogEmpty,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        catalogRetry = actionButton(getString(R.string.catalog_refresh), true) { loadCatalog(true) }
        catalogRetry?.visibility = View.GONE
        root.addView(
            catalogRetry,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also {
                it.gravity = Gravity.CENTER_HORIZONTAL
                it.bottomMargin = dpx(16)
            }
        )

        val swipe = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(Palette.neon)
            setProgressBackgroundColorSchemeColor(0xFF0D1119.toInt())
            addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setOnChildScrollUpCallback { _, _ -> list.canScrollVertically(-1) }
            setOnRefreshListener {
                postDelayed({
                    isRefreshing = false
                    loadCatalog(true)
                }, 350)
            }
        }
        content.addView(swipe, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        applyCatalogFilter()
        if (CatalogRepo.games.isEmpty() && catalogLoadingId != CatalogRepo.currentCatalogId) {
            loadCatalog(false)
        }
    }

    private fun applyCatalogFilter() {
        val all = CatalogRepo.games
        val q = query.trim().lowercase()
        val filtered = all.asSequence()
            .filter { platform == "all" || it.platform == platform }
            .filter {
                q.isEmpty() || it.title.lowercase().contains(q) ||
                    it.titleId.lowercase().contains(q) || it.gameId.lowercase().contains(q)
            }
            .toList()
        catalogAdapter?.submit(filtered)
        catalogCount?.text = if (all.isEmpty()) getString(R.string.catalog_count_none)
        else resources.getQuantityString(R.plurals.catalog_count, all.size, filtered.size, all.size)
        val empty = filtered.isEmpty()
        catalogList?.visibility = if (empty) View.GONE else View.VISIBLE
        catalogEmpty?.visibility = if (empty) View.VISIBLE else View.GONE
        val loading = catalogLoading && all.isEmpty()
        catalogEmpty?.text = when {
            all.isEmpty() && loading -> getString(R.string.loading_catalog)
            all.isEmpty() && CatalogRepo.lastError != null ->
                getString(R.string.catalog_error_detail, CatalogRepo.lastError)
            all.isEmpty() -> getString(R.string.catalog_empty)
            else -> getString(R.string.no_matches)
        }
        catalogRetry?.visibility = if (all.isEmpty() && !loading) View.VISIBLE else View.GONE
    }

    private fun loadCatalog(force: Boolean) {
        val catalogId = CatalogRepo.currentCatalogId
        if (catalogLoadingId == catalogId && !force) return
        val token = ++catalogLoadToken
        catalogLoadingId = catalogId
        catalogLoading = true
        if (tab == "catalog") applyCatalogFilter()
        io.execute {
            CatalogRepo.load(this@MainActivity, force, catalogId) {
                runOnUiThread {
                    if (token == catalogLoadToken && tab == "catalog" &&
                        CatalogRepo.currentCatalogId == catalogId
                    ) {
                        applyCatalogFilter()
                    }
                }
            }
            runOnUiThread {
                if (token != catalogLoadToken) return@runOnUiThread
                catalogLoading = false
                catalogLoadingId = null
                if (tab == "catalog" && CatalogRepo.currentCatalogId == catalogId) {
                    applyCatalogFilter()
                }
            }
        }
    }

    private fun switchCatalog(direction: Int) {
        val list = CatalogRepo.catalogs
        if (list.size < 2) return
        val current = list.indexOfFirst { it.id == CatalogRepo.currentCatalogId }
            .let { if (it < 0) 0 else it }
        val next = (current + direction + list.size) % list.size
        val target = list[next]
        if (target.id == CatalogRepo.currentCatalogId) return
        CatalogRepo.setCurrentCatalog(target.id)
        prefs.catalogId = target.id
        showCatalog()
    }

    // ---------------------------------------------------------------- options

    private fun showOptions(game: Game) {
        val dialog = Dialog(this)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassDrawable(28)
            setPadding(dpx(18), dpx(18), dpx(18), dpx(18))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col)
        wrap.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.72f).toInt(),
        ))

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(titleText(game.title, 19f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(actionButton(getString(R.string.close), false) { dialog.dismiss() })
        col.addView(head)
        col.addView(bodyText("${game.titleId} · ${if (game.platform == "ps5") "PS5" else "PS4"} · ${game.region}"))
        col.addView(spacer(12))

        if (game.options.isEmpty()) {
            col.addView(bodyText(getString(R.string.no_options)))
        }
        for (option in game.options) {
            val card = softCard(14)
            val title = "${option.format} · ${option.provider.ifBlank { option.source }}"
            card.addView(titleText(title, 15f))
            card.addView(bodyText("${sizeLabel(option.sizeBytes)} · ${option.filename}"))
            card.addView(spacer(8))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val ps5 = actionButton(getString(R.string.download_ps5), true) {
                dialog.dismiss()
                if (!prefs.paired) {
                    toast(getString(R.string.pair_required))
                    showSetup()
                    return@actionButton
                }
                openWebForDownload(option, "ps5", game.cover)
            }
            val phone = actionButton(getString(R.string.phone), false) {
                dialog.dismiss()
                openWebForDownload(option, "phone", game.cover)
            }
            phone.isEnabled = option.hasLink
            phone.alpha = if (option.hasLink) 1f else 0.4f
            row.addView(ps5, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(View(this), LinearLayout.LayoutParams(dpx(8), 1))
            row.addView(phone, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(row)
            col.addView(card)
            col.addView(spacer(10))
        }

        val note = bodyText(getString(R.string.firmware_note))
        note.setTextColor(Palette.textDim)
        col.addView(note)

        dialog.setContentView(wrap)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
        dialog.window?.decorView?.layoutDirection = resources.configuration.layoutDirection
    }

    private fun loadStorage(onDone: (List<Storage>) -> Unit) {
        io.execute {
            val list = Ps5Client.parseStorage(api.storage())
            runOnUiThread {
                if (list.isNotEmpty() && list.none { it.id == prefs.storageId }) {
                    prefs.storageId = list.maxByOrNull { it.freeBytes }?.id ?: list[0].id
                }
                onDone(list)
            }
        }
    }

    private fun chooseStorage(title: String, onPick: (Storage) -> Unit) {
        io.execute {
            val list = Ps5Client.parseStorage(api.storage())
            runOnUiThread {
                if (list.isEmpty()) {
                    toast(getString(R.string.storage_error))
                    return@runOnUiThread
                }
                if (list.size == 1) {
                    onPick(list[0])
                    return@runOnUiThread
                }
                val dialog = Dialog(this)
                dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                val col = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = glassDrawable(26)
                    setPadding(dpx(20), dpx(20), dpx(20), dpx(18))
                }
                col.addView(titleText(title, 20f))
                col.addView(spacer(12))
                for (storage in list) {
                    col.addView(
                        actionButton(
                            getString(
                                R.string.storage_item,
                                storage.label,
                                Ps5Client.formatBytes(storage.freeBytes)
                            ),
                            storage.id == prefs.storageId
                        ) {
                            dialog.dismiss()
                            onPick(storage)
                        },
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    )
                    col.addView(spacer(8))
                }
                col.addView(spacer(4))
                col.addView(
                    actionButton(getString(R.string.cancel), false) { dialog.dismiss() },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
                dialog.setContentView(col)
                dialog.show()
                dialog.window?.setLayout(
                    (resources.displayMetrics.widthPixels * 0.9f).toInt(),
                    WindowManager.LayoutParams.WRAP_CONTENT
                )
                dialog.window?.decorView?.layoutDirection = resources.configuration.layoutDirection
            }
        }
    }

    private fun startConsoleDownload(option: GameOption, storage: Storage) {
        io.execute {
            val r = api.startDownload(option, storage.id)
            runOnUiThread {
                if (r.ok) {
                    toast(getString(R.string.added_to_queue))
                    setTab("downloads")
                } else {
                    toast(getString(R.string.start_failed, r.message()))
                }
            }
        }
    }

    private fun openWebForDownload(option: GameOption, mode: String, cover: String = "") {
        val url = option.browserUrl.ifBlank { option.url }
        if (url.isBlank()) {
            toast(getString(R.string.browser_required))
            return
        }
        val intent = Intent(this, DownloadWebActivity::class.java).apply {
            putExtra("url", url)
            putExtra("title", option.title)
            putExtra("mode", mode)
            putExtra("filename", option.filename)
            putExtra("size", option.sizeBytes)
            putExtra("password", option.password)
            putExtra("cover", cover)
        }
        webDownloadLauncher.launch(intent)
    }

    private fun storageIdFor(mode: String): String {
        return if (mode == "ps5") prefs.storageId else "phone"
    }

    private fun startSendToPs5(d: PhoneDownload, storage: Storage) {
        if (!prefs.paired) {
            toast(getString(R.string.pair_required))
            showSetup()
            return
        }
        if (d.uri.isBlank()) return
        val cover = findCatalogGame("", "", d.filename)?.cover.orEmpty()
        val intent = Intent(this, TransferService::class.java).apply {
            putExtra("mode", "send")
            putExtra("title", d.filename)
            putExtra("uri", d.uri)
            putExtra("filename", d.filename)
            putExtra("size", d.size)
            putExtra("storageId", storage.id)
            putExtra("cover", cover)
        }
        ContextCompat.startForegroundService(this, intent)
        toast(getString(R.string.send_started, d.filename))
        setTab("downloads")
    }

    private fun startSendToPs5FromTransfer(t: TransferState) {
        if (!prefs.paired) {
            toast(getString(R.string.pair_required))
            showSetup("downloads")
            return
        }
        if (t.uri.isBlank()) {
            toast(getString(R.string.storage_error))
            return
        }
        if (TransferStore.current.running) {
            toast(getString(R.string.transfer_in_progress))
            return
        }
        val intent = Intent(this, TransferService::class.java).apply {
            putExtra("mode", "send")
            putExtra("title", t.title)
            putExtra("uri", t.uri)
            putExtra("filename", t.filename)
            putExtra("size", t.total)
            putExtra("storageId", prefs.storageId)
            putExtra("cover", t.cover)
        }
        ContextCompat.startForegroundService(this, intent)
        toast(getString(R.string.send_started, t.filename))
        setTab("downloads")
    }

    private fun startPhoneTransfer(option: GameOption, storage: Storage) {
        if (!option.phoneAvailable) {
            toast(getString(R.string.browser_required))
            return
        }
        if (TransferStore.current.running) {
            toast(getString(R.string.transfer_in_progress))
            setTab("downloads")
            return
        }
        val intent = Intent(this, TransferService::class.java).apply {
            putExtra("title", option.title)
            putExtra("url", option.url)
            putExtra("filename", option.filename)
            putExtra("size", option.sizeBytes)
            putExtra("storageId", storage.id)
            putExtra("content", option.content.toString())
        }
        ContextCompat.startForegroundService(this, intent)
        toast(getString(R.string.transfer_started, option.title))
        setTab("downloads")
    }

    private fun startPhoneDownload(option: GameOption) {
        if (TransferStore.current.running) {
            toast(getString(R.string.transfer_in_progress))
            setTab("downloads")
            return
        }
        val intent = Intent(this, TransferService::class.java).apply {
            putExtra("mode", "local")
            putExtra("title", option.title)
            putExtra("url", option.url)
            putExtra("filename", fileNameFor(option))
            putExtra("size", option.sizeBytes)
            putExtra("sha256", option.sha256)
        }
        ContextCompat.startForegroundService(this, intent)
        toast(getString(R.string.transfer_started_phone, option.title))
        setTab("downloads")
    }

    private fun startDownloadWeb(option: GameOption) {
        val url = option.browserUrl.ifBlank { option.url }
        if (url.isBlank()) {
            toast(getString(R.string.browser_required))
            return
        }
        val intent = Intent(this, DownloadWebActivity::class.java).apply {
            putExtra("title", option.title)
            putExtra("url", url)
        }
        webDownloadLauncher.launch(intent)
    }

    private fun sizeLabel(bytes: Long): String =
        if (bytes > 0) Ps5Client.formatBytes(bytes) else "—"

    private fun etaText(received: Long, total: Long, speed: Double): String {
        if (speed <= 0.0 || total <= received) return ""
        val secs = ((total - received) / speed).toLong().coerceAtLeast(1)
        val text = when {
            secs >= 86400 -> "${secs / 86400}d ${(secs % 86400) / 3600}h"
            secs >= 3600 -> "${secs / 3600}h ${(secs % 3600) / 60}m"
            secs >= 60 -> "${secs / 60}m ${secs % 60}s"
            else -> "${secs}s"
        }
        return " · " + getString(R.string.eta_label, text)
    }

    private fun fileNameFor(option: GameOption): String {
        val fromUrl = option.url
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('/')
        return when {
            option.filename.contains('.') -> option.filename
            fromUrl.contains('.') -> fromUrl
            option.filename.isNotBlank() -> option.filename
            else -> option.titleId
        }
    }

    private fun openBrowser(url: String) {
        if (url.isBlank()) {
            toast(getString(R.string.browser_required))
            return
        }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }.onFailure {
            toast(getString(R.string.browser_required))
        }
    }

    // -------------------------------------------------------------- downloads

    private fun showDownloads() {
        tab = "downloads"
        navBar.visibility = View.VISIBLE
        for ((k, v) in navItems) {
            val selected = k == "downloads"
            v.background = chipDrawable(selected)
            v.setTextColor(if (selected) Palette.neon else Palette.textSecondary)
        }
        val col = scrollContent()
        col.addView(titleText(getString(R.string.downloads_title), 24f))
        col.addView(spacer(10))

        if (!storageAccessGranted()) {
            val perm = softCard(14)
            perm.addView(bodyText(getString(R.string.storage_access_hint), 13f))
            perm.addView(spacer(8))
            perm.addView(
                actionButton(getString(R.string.storage_access_button), true) {
                    ensureStorageAccess()
                }
            )
            col.addView(perm)
            col.addView(spacer(10))
        }

        val local = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(local)
        renderLocal(local)
        if (CatalogRepo.games.isEmpty()) {
            io.execute {
                CatalogRepo.load(this, false) {
                    runOnUiThread {
                        if (tab == "downloads") renderLocal(local)
                    }
                }
            }
        }

        if (!prefs.paired) {
            startRefresh { if (tab == "downloads") renderLocal(local) }
            return
        }

        val ps5Section = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ps5Section)

        fun refresh() {
            if (tab != "downloads") return
            renderLocal(local)
            if (!refreshing) {
                refreshing = true
                io.execute {
                    val dr = api.downloads()
                    val ir = api.installations()
                    val jobs = Ps5Client.parseJobs(dr)
                    val receipts = Ps5Client.parseReceipts(ir)
                    val netError = when {
                        !dr.ok && !ir.ok -> "${dr.message()} · ${ir.message()}"
                        !dr.ok -> dr.message()
                        !ir.ok -> ir.message()
                        else -> null
                    }
                    runOnUiThread {
                        refreshing = false
                        if (tab != "downloads") return@runOnUiThread
                        ps5Section.removeAllViews()
                        if (jobs.isNotEmpty()) {
                            ps5Section.addView(hairline())
                            val qHeader = LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = android.view.Gravity.CENTER_VERTICAL
                            }
                            qHeader.addView(
                                accentText(getString(R.string.ps5_queue)),
                                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            )
                            val hasFinished = jobs.any { !it.active && (it.complete || it.status == "error" || it.status == "cancelled") }
                            if (hasFinished) {
                                qHeader.addView(actionButton(getString(R.string.action_clear_finished), false) {
                                    io.execute {
                                        val cr = api.clearFinishedDownloads()
                                        runOnUiThread {
                                            if (!cr.ok) toast(getString(R.string.action_failed, cr.message()))
                                            if (tab == "downloads") showDownloads()
                                        }
                                    }
                                })
                            }
                            ps5Section.addView(qHeader)
                            val queue = LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.VERTICAL
                            }
                            ps5Section.addView(queue)
                            renderJobs(queue, jobs)
                        }
                        if (receipts.any { it.packages.isNotEmpty() }) {
                            ps5Section.addView(hairline())
                            ps5Section.addView(accentText(getString(R.string.install_on_ps5)))
                            val installs = LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.VERTICAL
                            }
                            ps5Section.addView(installs)
                            renderReceipts(installs, receipts)
                        }
                    }
                }
            }
        }

        renderLocal(local)
        refresh()
        startRefresh { refresh() }
    }

    private fun addLockedSection(parent: LinearLayout, title: String, message: String) {
        parent.addView(accentText(title))
        parent.addView(bodyText(message).apply { setTextColor(Palette.textDim) })
        parent.addView(spacer(12))
    }

    private fun renderLocal(container: LinearLayout) {
        container.removeAllViews()
        val t = TransferStore.current
        val showTransfer = when {
            t.state == "idle" -> false
            t.running || t.state == "paused" -> true
            t.state == "complete" && t.uri.isNotBlank() ->
                PhoneDownloads.exists(this, t.uri, t.filename)
            else -> true
        }
        if (showTransfer) {
            addTransferCard(container, t)
        }
        for (d in PhoneDownloads.query(this)) {
            if (showTransfer && d.filename == t.filename) continue
            addPhoneCard(container, d)
        }
    }

    private fun findCatalogGame(title: String, titleId: String, filename: String): Game? {
        return findCatalogGameIn(CatalogRepo.games, title, titleId, filename)
            ?: findCatalogGameIn(CatalogRepo.homebrewGames, title, titleId, filename)
    }

    private fun findCatalogGameIn(games: List<Game>, title: String, titleId: String, filename: String): Game? {
        if (games.isEmpty()) return null
        val ids = LinkedHashSet<String>()
        if (titleId.isNotBlank()) ids.add(titleId.uppercase())
        Regex("(CUSA|PPSA)\\d{5}", RegexOption.IGNORE_CASE)
            .find(filename)?.value?.let { ids.add(it.uppercase()) }
        Regex("(?i)(?<![A-Z])(PSA)(\\d{5})").find(filename)?.let { ids.add("PPSA${it.groupValues[2]}") }
        Regex("(?i)(?<![A-Z])(USA)(\\d{5})").find(filename)?.let { ids.add("CUSA${it.groupValues[2]}") }
        for (id in ids) games.firstOrNull { it.titleId.equals(id, true) }?.let { return it }
        games.firstOrNull { g -> g.options.any { it.filename.equals(filename, true) } }?.let { return it }
        val t = title.ifBlank { filename.substringBeforeLast('.') }.trim().lowercase()
        val fn = filename.lowercase()
        if (t.isNotBlank()) {
            games.firstOrNull { it.title.lowercase() == t }?.let { return it }
            games.firstOrNull { g ->
                val gt = g.title.lowercase()
                gt.isNotBlank() && (t.contains(gt) || gt.contains(t))
            }?.let { return it }
        }
        return games.firstOrNull { g -> g.gameId.isNotBlank() && fn.contains(g.gameId.lowercase()) }
    }

    private fun gameCover(url: String?, width: Int, height: Int): ImageView {
        return ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = glassDrawable(10, Palette.coverTop, Palette.coverBottom, mid = Palette.coverMid)
            if (!url.isNullOrBlank() && url.startsWith("http")) loadCover(url)
            layoutParams = LinearLayout.LayoutParams(dpx(width), dpx(height))
        }
    }

    private fun ImageView.loadCover(url: String) {
        load(url) {
            crossfade(true)
            listener(onError = { _, _ ->
                val fixed = url.replace("%20", "")
                if (fixed != url) load(fixed) { crossfade(true) }
            })
        }
    }

    private fun compactDelete(onClick: () -> Unit): Button = Button(this).apply {
        text = "✕"
        setTextColor(Palette.danger)
        textSize = 15f
        isAllCaps = false
        minWidth = dpx(34)
        minimumWidth = dpx(34)
        minHeight = dpx(32)
        minimumHeight = dpx(32)
        setPadding(dpx(6), 0, dpx(6), 0)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpx(16).toFloat()
            setColor(0x22FF5C7A)
            setStroke(dpx(1).coerceAtLeast(1), 0x88FF5C7A.toInt())
        }
        includeFontPadding = false
        stateListAnimator = null
        pressAnimation()
        setOnClickListener { onClick() }
    }

    private fun addPhoneCard(container: LinearLayout, d: PhoneDownload) {
        val game = findCatalogGame("", "", d.filename)
        val card = softCard(10)
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dpx(8) }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cover = gameCover(game?.cover, 52, 68)
        (cover.layoutParams as LinearLayout.LayoutParams).marginEnd = dpx(10)
        row.addView(cover)

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(
            titleText(d.filename, 14f).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        top.addView(compactDelete { confirmDeletePhone(d) })
        body.addView(top)

        val format = d.filename.substringAfterLast('.', "").uppercase()
        val titleId = Regex("(CUSA|PPSA)\\d{5}", RegexOption.IGNORE_CASE)
            .find(d.filename)?.value?.uppercase()
        val stateLabel = when (d.state) {
            "complete" -> getString(R.string.download_state_complete)
            "failed" -> getString(R.string.download_state_failed)
            else -> getString(R.string.download_state_running)
        }
        val meta = buildString {
            if (format.isNotBlank()) append(format)
            if (d.size > 0) {
                if (isNotEmpty()) append(" · ")
                append(Ps5Client.formatBytes(d.size))
            }
            if (!titleId.isNullOrBlank()) {
                if (isNotEmpty()) append(" · ")
                append(titleId)
            }
            if (isNotEmpty()) append(" · ")
            append(stateLabel)
        }
        body.addView(bodyText(meta, 11f).apply {
            setTextColor(if (d.state == "failed") Palette.danger else Palette.textSecondary)
        })

        val known = d.size > 0
        if (d.running) {
            body.addView(progressBar(indeterminate = !known).apply {
                if (known) progress = d.percent
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dpx(5)
                ).also { it.topMargin = dpx(6) }
            })
            body.addView(bodyText(
                if (known) {
                    "${Ps5Client.formatBytes(d.received)} / ${Ps5Client.formatBytes(d.size)} · ${d.percent}%"
                } else {
                    Ps5Client.formatBytes(d.received) + " …"
                },
                11f
            ))
        }
        if (d.state == "failed" && d.message.isNotBlank()) {
            body.addView(bodyText(d.message, 11f).apply { setTextColor(Palette.danger) })
        }
        if (d.state == "complete" && d.uri.isNotBlank()) {
            body.addView(actionButton(getString(R.string.send_to_ps5), true) {
                if (!prefs.paired) {
                    toast(getString(R.string.pair_required))
                    showSetup()
                    return@actionButton
                }
                if (TransferStore.current.running) {
                    toast(getString(R.string.transfer_in_progress))
                    setTab("downloads")
                    return@actionButton
                }
                chooseStorage(getString(R.string.send_to_ps5)) { storage ->
                    startSendToPs5(d, storage)
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dpx(8) })
        }

        row.addView(body, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row)
        container.addView(card)
    }


    private fun resumeLocalTransfer() {
        val t = TransferStore.current
        if (t.url.isBlank()) return
        val intent = Intent(this, TransferService::class.java).apply {
            putExtra("mode", "local")
            putExtra("resume", true)
            putExtra("title", t.title)
            putExtra("url", t.url)
            putExtra("filename", t.filename)
            putExtra("size", t.total)
            putExtra("sha256", t.sha256)
            putExtra("cookie", t.cookie)
            putExtra("userAgent", t.userAgent)
            putExtra("origin", t.origin)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun resolveTransferCover(t: TransferState) {
        val key = t.title + "|" + t.filename
        if (t.cover.isNotBlank() || !coverLookupKeys.add(key)) return
        io.execute {
            val cover = CatalogRepo.findCachedCover(this@MainActivity, t.title, t.filename)
            if (cover.isNotBlank()) {
                TransferStore.update(cover = cover)
                TransferStore.persistNow()
                runOnUiThread { if (tab == "downloads") showDownloads() }
            }
        }
    }

    private fun addTransferCard(container: LinearLayout, t: TransferState) {
        val game = findCatalogGame(t.title, "", t.filename)
        val coverUrl = t.cover.ifBlank { game?.cover.orEmpty() }
        when {
            coverUrl.isBlank() -> resolveTransferCover(t)
            t.cover.isBlank() -> {
                TransferStore.update(cover = coverUrl)
                TransferStore.persistNow()
            }
        }
        val card = softCard(10)
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dpx(8) }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cover = gameCover(coverUrl, 52, 68)
        (cover.layoutParams as LinearLayout.LayoutParams).marginEnd = dpx(10)
        row.addView(cover)

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(
            titleText(t.title.ifBlank { t.filename }, 14f).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        top.addView(compactDelete { confirmDeleteTransfer(t) })
        body.addView(top)
        val stateLabel = when (t.state) {
            "preparing" -> getString(R.string.state_preparing)
            "downloading" -> getString(R.string.state_downloading)
            "verifying" -> getString(R.string.state_verifying)
            "sent" -> getString(R.string.state_sent)
            "complete" -> getString(R.string.state_complete)
            "error" -> getString(R.string.state_error)
            "cancelled" -> getString(R.string.state_cancelled)
            "paused" -> getString(R.string.state_paused)
            else -> t.state
        }
        body.addView(bodyText(stateLabel, 11f).apply {
            setTextColor(if (t.state == "error") Palette.danger else Palette.textSecondary)
        })
        if (t.total > 0) {
            body.addView(progressBar().apply {
                progress = t.percent
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dpx(5)
                ).also { it.topMargin = dpx(6) }
            })
            body.addView(bodyText(
                "${Ps5Client.formatBytes(t.received)} / ${Ps5Client.formatBytes(t.total)} · ${t.percent}%" +
                    (if (t.speed > 0) " · ${Ps5Client.formatBytes(t.speed.toLong())}/s" else "") +
                    etaText(t.received, t.total, t.speed),
                11f
            ))
        } else if (t.running) {
            body.addView(progressBar(indeterminate = true).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dpx(5)
                ).also { it.topMargin = dpx(6) }
            })
            body.addView(bodyText(
                Ps5Client.formatBytes(t.received) +
                    if (t.speed > 0) " · ${Ps5Client.formatBytes(t.speed.toLong())}/s" else "",
                11f
            ))
        }
        if (t.state == "error" && t.message.isNotBlank()) {
            body.addView(bodyText(getString(R.string.error_detail, t.message), 11f).apply {
                setTextColor(Palette.danger)
            })
        } else if (t.state == "sent") {
            body.addView(bodyText(getString(R.string.transfer_sent_note), 11f).apply {
                setTextColor(Palette.textDim)
            })
        } else if (t.state == "complete") {
            val where = t.path.ifBlank { t.message }
            if (where.isNotBlank()) {
                body.addView(bodyText(where, 11f).apply {
                    setTextColor(Palette.textDim)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                })
            }
        } else if (!t.running && t.state != "paused" && t.message.isNotBlank()) {
            body.addView(bodyText(t.message, 11f).apply { setTextColor(Palette.textDim) })
        }

        if (t.running || t.state == "paused") {
            val canPauseResume = t.mode == "local" && t.url.isNotBlank()
            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dpx(8) }
            }
            if (canPauseResume) {
                val pauseLabel = if (t.state == "paused") "▶  " + getString(R.string.resume_transfer)
                                 else "⏸  " + getString(R.string.pause_transfer)
                btnRow.addView(
                    actionButton(pauseLabel, t.state == "paused") {
                        if (t.state == "paused") {
                            resumeLocalTransfer()
                        } else {
                            TransferStore.pause()
                        }
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
                btnRow.addView(View(this), LinearLayout.LayoutParams(dpx(8), 1))
            }
            btnRow.addView(
                actionButton(getString(R.string.cancel_transfer), false) {
                    if (t.running) {
                        TransferStore.cancelRequested = true
                        TransferStore.pauseRequested = false
                        toast(getString(R.string.cancelling))
                    } else {
                        deletePartialTransfer(t)
                        TransferStore.clear()
                        toast(getString(R.string.notif_cancelled))
                        if (tab == "downloads") showDownloads()
                    }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            body.addView(btnRow)
        } else if (t.state == "complete" && t.uri.isNotBlank()) {
            body.addView(actionButton(getString(R.string.send_to_ps5), true) {
                startSendToPs5FromTransfer(t)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dpx(8) })
        }

        row.addView(body, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row)
        container.addView(card)
    }

    private fun renderJobs(container: LinearLayout, jobs: List<Job>) {
        container.removeAllViews()
        if (jobs.isEmpty()) {
            container.addView(bodyText(getString(R.string.queue_empty)))
            return
        }
        for (job in jobs) {
            val game = findCatalogGame(job.title, job.titleId, job.filename)
            val card = softCard(10)
            card.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dpx(8) }

            val outer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val cover = gameCover(game?.cover, 52, 68)
            (cover.layoutParams as LinearLayout.LayoutParams).marginEnd = dpx(10)
            outer.addView(cover)

            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            header.addView(
                titleText(job.title, 14f).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            header.addView(compactDelete { confirmDeleteJob(job) })
            body.addView(header)
            body.addView(
                bodyText(
                    "${Ps5Client.statusLabel(this, job.status)} · ${job.format}" +
                        if (job.titleId.isNotBlank()) " · ${job.titleId}" else "",
                    11f
                ).apply {
                    setTextColor(if (job.status == "error") Palette.danger else Palette.textSecondary)
                }
            )
            if (job.total > 0) {
                body.addView(progressBar().apply {
                    progress = job.percent
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dpx(5)
                    ).also { it.topMargin = dpx(6) }
                })
                body.addView(
                    bodyText(
                        "${Ps5Client.formatBytes(job.received)} / ${Ps5Client.formatBytes(job.total)} · ${job.percent}%" +
                            (if (job.speed > 0) " · ${Ps5Client.formatBytes(job.speed.toLong())}/s" else "") +
                            etaText(job.received, job.total, job.speed),
                        11f
                    )
                )
            } else if (job.active) {
                body.addView(progressBar(indeterminate = true).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dpx(5)
                    ).also { it.topMargin = dpx(6) }
                })
                body.addView(
                    bodyText(
                        Ps5Client.formatBytes(job.received) +
                            if (job.speed > 0) " · ${Ps5Client.formatBytes(job.speed.toLong())}/s" else "",
                        11f
                    )
                )
            }
            if (job.error.isNotBlank()) {
                body.addView(bodyText(job.error, 11f).apply { setTextColor(Palette.danger) })
            }
            if (job.extractedPath.isNotBlank()) {
                body.addView(
                    bodyText(getString(R.string.job_saved_to, job.extractedPath), 11f).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(Palette.textSecondary)
                    }
                )
            }
            outer.addView(body, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(outer)
            card.addView(spacer(6))

            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun addAction(label: String, primary: Boolean, action: String) {
                if (actions.childCount > 0) {
                    actions.addView(View(this), LinearLayout.LayoutParams(dpx(8), 1))
                }
                actions.addView(
                    actionButton(label, primary) { runJobAction(job, action) },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
            }

            when {
                job.active -> addAction(getString(R.string.action_pause), false, "pause")
                job.status == "error" -> addAction(getString(R.string.action_retry), true, "retry")
                job.status == "paused" || job.status == "cancelled" ->
                    addAction(getString(R.string.action_resume), true, "resume")
            }
            if (job.complete) {
                val pkg = job.installable
                if (pkg != null) addAction(getString(R.string.install_on_ps5), true, "install-${pkg.id}")
                else addAction(getString(R.string.action_check_files), true, "check-packages")
                addAction(getString(R.string.action_remove), false, "remove")
            } else if (job.status != "cancelled") {
                addAction(getString(R.string.action_cancel), false, "cancel")
            }
            if (actions.childCount > 0) card.addView(actions)
            container.addView(card)
        }
    }

    private fun runJobAction(job: Job, action: String) {
        io.execute {
            val r = api.jobAction(job.id, action)
            runOnUiThread {
                if (!r.ok) toast(getString(R.string.action_failed, r.message()))
                else if (action.startsWith("install-")) toast(getString(R.string.install_started))
                if (tab == "downloads") showDownloads()
            }
        }
    }

    private fun renderReceipts(container: LinearLayout, receipts: List<Receipt>) {
        container.removeAllViews()
        val withPackages = receipts.filter { it.packages.isNotEmpty() }
        if (withPackages.isEmpty()) {
            container.addView(bodyText(getString(R.string.no_packages)))
            return
        }
        for (receipt in withPackages) {
            val card = softCard(10)
            card.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dpx(8) }
            card.addView(titleText(receipt.title.ifBlank { getString(R.string.ps5_package) }, 14f))
            if (receipt.message.isNotBlank()) {
                card.addView(bodyText(receipt.message).apply { setTextColor(Palette.textDim) })
            }
            for (pkg in receipt.packages) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dpx(6), 0, dpx(2))
                }
                val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                info.addView(
                    bodyText(pkg.filename.ifBlank { pkg.id }).apply {
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    }
                )
                info.addView(
                    accentText(Ps5Client.packageStateLabel(this, pkg.state), 11f).apply {
                        setTextColor(if (pkg.state == "error") Palette.danger else Palette.neon)
                    }
                )
                row.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                if (pkg.state == "ready" || pkg.state == "error") {
                    row.addView(actionButton(getString(R.string.install), true) { runInstall(receipt, pkg) })
                }
                card.addView(row)
            }
            container.addView(card)
        }
    }

    private fun runInstall(receipt: Receipt, pkg: PackageInfo) {
        io.execute {
            val r = api.installPackage(receipt.id, pkg.id)
            runOnUiThread {
                if (r.ok) toast(getString(R.string.package_install_started))
                else toast(getString(R.string.package_install_failed, r.message()))
                if (tab == "downloads") showDownloads()
            }
        }
    }

    private fun startRefresh(block: () -> Unit) {
        stopRefresh()
        val runnable = object : Runnable {
            override fun run() {
                if (tab != "downloads") return
                block()
                main.postDelayed(this, 2500)
            }
        }
        refreshRunnable = runnable
        main.postDelayed(runnable, 2500)
    }

    private fun stopRefresh() {
        refreshRunnable?.let { main.removeCallbacks(it) }
        refreshRunnable = null
    }

    // ---------------------------------------------------------------- console

    private fun showFiles() {
        tab = "files"
        navBar.visibility = View.VISIBLE
        for ((k, v) in navItems) {
            val selected = k == "files"
            v.background = chipDrawable(selected)
            v.setTextColor(if (selected) Palette.neon else Palette.textSecondary)
        }
        val col = scrollContent()
        col.addView(titleText(getString(R.string.files_title), 24f))
        col.addView(spacer(10))
        if (!prefs.paired) {
            col.addView(bodyText(getString(R.string.pair_required)))
            return
        }

        val storageRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(storageRow)
        col.addView(spacer(10))

        val crumb = bodyText("", 12f)
        val up = actionButton(getString(R.string.files_up), false) { }
        val crumbRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        crumbRow.addView(up)
        crumbRow.addView(View(this), LinearLayout.LayoutParams(dpx(8), 1))
        crumbRow.addView(crumb, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(crumbRow)
        col.addView(spacer(10))

        val listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        fun load(storage: String, path: String) {
            filesStorage = storage
            filesPath = path
            crumb.text = "/" + path
            up.alpha = if (path.isNotEmpty()) 1f else 0.4f
            up.setOnClickListener {
                if (filesPath.isNotEmpty()) load(filesStorage, parentPath(filesPath))
            }
            listHost.removeAllViews()
            listHost.addView(bodyText(getString(R.string.files_loading)))
            io.execute {
                val r = api.files(storage, path)
                val entries = Ps5Client.parseFiles(r)
                runOnUiThread {
                    if (tab != "files") return@runOnUiThread
                    listHost.removeAllViews()
                    if (!r.ok) {
                        listHost.addView(
                            bodyText(getString(R.string.files_action_failed, r.message()), 12f).apply {
                                setTextColor(Palette.danger)
                            }
                        )
                        return@runOnUiThread
                    }
                    renderFiles(
                        listHost, path, entries,
                        openDir = { child -> load(storage, child) },
                        onFile = { entry -> showFileActions(storage, path, entry) { load(storage, path) } }
                    )
                }
            }
        }

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(
            actionButton(getString(R.string.files_new_folder), false) {
                promptText(
                    getString(R.string.files_new_folder), "",
                    getString(R.string.files_name_hint)
                ) { name ->
                    if (name.isBlank()) return@promptText
                    val target = if (filesPath.isEmpty()) name else "${filesPath}/$name"
                    io.execute {
                        val r = api.fileAction(filesStorage, "mkdir", target)
                        runOnUiThread {
                            if (!r.ok) toast(getString(R.string.files_action_failed, r.message()))
                            else load(filesStorage, filesPath)
                        }
                    }
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        actions.addView(View(this), LinearLayout.LayoutParams(dpx(10), 1))
        actions.addView(
            actionButton(getString(R.string.files_refresh), true) {
                load(filesStorage, filesPath)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        col.addView(actions)
        col.addView(spacer(12))
        col.addView(listHost)

        io.execute {
            val sr = api.storage()
            val storages = Ps5Client.parseStorage(sr)
            runOnUiThread {
                if (tab != "files") return@runOnUiThread
                storageRow.removeAllViews()
                val list = storages.ifEmpty {
                    listOf(Storage("data", "data", "/data", 0L, 0L, false))
                }
                for ((idx, s) in list.withIndex()) {
                    if (idx > 0) storageRow.addView(View(this), LinearLayout.LayoutParams(dpx(8), 1))
                    storageRow.addView(
                        actionButton(s.label, s.id == filesStorage) { load(s.id, "") },
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    )
                }
                load(filesStorage, filesPath)
            }
        }
    }

    private fun parentPath(path: String): String {
        val idx = path.lastIndexOf('/')
        return if (idx <= 0) "" else path.substring(0, idx)
    }

    private fun renderFiles(
        container: LinearLayout,
        path: String,
        entries: List<FileEntry>,
        openDir: (String) -> Unit,
        onFile: (FileEntry) -> Unit,
    ) {
        container.removeAllViews()
        if (entries.isEmpty()) {
            container.addView(bodyText(getString(R.string.files_empty)))
            return
        }
        for (e in entries) {
            val card = softCard(10)
            card.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dpx(8) }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val badge = TextView(this).apply {
                text = if (e.dir) getString(R.string.files_dir)
                else e.name.substringAfterLast('.', "").uppercase().take(4)
                    .ifBlank { getString(R.string.files_file) }
                textSize = 9f
                gravity = Gravity.CENTER
                setTextColor(if (e.dir) Palette.neon else Palette.textSecondary)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = chipDrawable(false)
                setPadding(dpx(6), dpx(4), dpx(6), dpx(4))
            }
            row.addView(
                badge,
                LinearLayout.LayoutParams(dpx(52), LinearLayout.LayoutParams.WRAP_CONTENT)
                    .also { it.marginEnd = dpx(10) }
            )
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            body.addView(titleText(e.name, 14f).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            body.addView(bodyText(
                if (e.dir) getString(R.string.files_dir) else Ps5Client.formatBytes(e.size), 11f
            ))
            row.addView(body, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(row)
            card.setOnClickListener {
                if (e.dir) openDir(if (path.isEmpty()) e.name else "$path/${e.name}")
                else onFile(e)
            }
            container.addView(card)
        }
    }

    private fun showFileActions(storage: String, path: String, entry: FileEntry, onDone: () -> Unit) {
        val full = if (path.isEmpty()) entry.name else "$path/${entry.name}"
        val dialog = Dialog(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassDrawable(26)
            setPadding(dpx(20), dpx(20), dpx(20), dpx(18))
        }
        col.addView(titleText(entry.name, 20f))
        col.addView(spacer(4))
        col.addView(bodyText(Ps5Client.formatBytes(entry.size), 12f))
        col.addView(spacer(16))
        col.addView(actionButton(getString(R.string.files_rename), false) {
            dialog.dismiss()
            promptText(
                getString(R.string.files_rename), entry.name,
                getString(R.string.files_name_hint)
            ) { name ->
                if (name.isBlank() || name == entry.name) return@promptText
                io.execute {
                    val r = api.fileAction(storage, "rename", full, name = name)
                    runOnUiThread {
                        if (!r.ok) toast(getString(R.string.files_action_failed, r.message()))
                        else {
                            toast(getString(R.string.files_renamed))
                            onDone()
                        }
                    }
                }
            }
        })
        col.addView(spacer(8))
        col.addView(actionButton(getString(R.string.files_move_here), false) {
            dialog.dismiss()
            chooseStorage(getString(R.string.files_move_title)) { dest ->
                promptText(
                    getString(R.string.files_move_title), "",
                    getString(R.string.files_move_hint)
                ) { target ->
                    io.execute {
                        val r = api.fileAction(
                            storage, "move", full,
                            target = target.trim(), targetStorage = dest.id
                        )
                        runOnUiThread {
                            if (!r.ok) toast(getString(R.string.files_action_failed, r.message()))
                            else {
                                toast(getString(R.string.files_moved))
                                onDone()
                            }
                        }
                    }
                }
            }
        })
        col.addView(spacer(8))
        col.addView(actionButton(getString(R.string.files_delete), true) {
            dialog.dismiss()
            confirmDeleteFile(storage, full, entry.name, onDone)
        })
        col.addView(spacer(14))
        col.addView(actionButton(getString(R.string.close), false) { dialog.dismiss() })

        dialog.setContentView(col)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.85f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.decorView?.layoutDirection = resources.configuration.layoutDirection
    }

    private fun confirmDeleteFile(storage: String, full: String, name: String, onDone: () -> Unit) {
        val dialog = Dialog(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassDrawable(26)
            setPadding(dpx(20), dpx(20), dpx(20), dpx(18))
        }
        col.addView(titleText(getString(R.string.confirm_delete_title), 20f))
        col.addView(spacer(10))
        col.addView(bodyText(getString(R.string.files_confirm_delete, name), 15f))
        col.addView(spacer(18))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            actionButton(getString(R.string.confirm_delete_cancel), false) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(View(this), LinearLayout.LayoutParams(dpx(10), 1))
        row.addView(
            actionButton(getString(R.string.confirm_delete_confirm), true) {
                dialog.dismiss()
                io.execute {
                    val r = api.fileAction(storage, "delete", full)
                    runOnUiThread {
                        if (!r.ok) toast(getString(R.string.files_action_failed, r.message()))
                        else {
                            toast(getString(R.string.files_deleted))
                            onDone()
                        }
                    }
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        col.addView(row)

        dialog.setContentView(col)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.85f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.decorView?.layoutDirection = resources.configuration.layoutDirection
    }

    private fun promptText(title: String, initial: String, hint: String, onOk: (String) -> Unit) {
        val dialog = Dialog(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassDrawable(26)
            setPadding(dpx(20), dpx(20), dpx(20), dpx(18))
        }
        col.addView(titleText(title, 20f))
        col.addView(spacer(12))
        val input = EditText(this).apply {
            setText(initial)
            this.hint = hint
            setSingleLine(true)
            textSize = 15f
            setTextColor(Palette.text)
            setHintTextColor(Palette.textDim)
            background = outlineDrawable(14)
            setPadding(dpx(12), dpx(10), dpx(12), dpx(10))
        }
        col.addView(input)
        col.addView(spacer(18))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            actionButton(getString(R.string.confirm_delete_cancel), false) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(View(this), LinearLayout.LayoutParams(dpx(10), 1))
        row.addView(
            actionButton(getString(R.string.action_ok), true) {
                val value = input.text.toString().trim()
                dialog.dismiss()
                onOk(value)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        col.addView(row)

        dialog.setContentView(col)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.85f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.decorView?.layoutDirection = resources.configuration.layoutDirection
        input.requestFocus()
    }

    private fun showSettingsDialog() {
        val dialog = Dialog(this)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpx(20), dpx(20), dpx(20), dpx(18))
            background = glassDrawable(26)
        }
        scroll.addView(col, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        col.addView(titleText(getString(R.string.settings_title), 22f))
        col.addView(spacer(16))

        col.addView(accentText(getString(R.string.settings_appearance)))
        col.addView(spacer(8))
        val themeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val dark = actionButton(getString(R.string.settings_theme_dark), !prefs.lightTheme) {
            if (prefs.lightTheme) {
                prefs.lightTheme = false
                restoreTab = tab
                dialog.dismiss()
                recreate()
            }
        }
        val light = actionButton(getString(R.string.settings_theme_light), prefs.lightTheme) {
            if (!prefs.lightTheme) {
                prefs.lightTheme = true
                restoreTab = tab
                dialog.dismiss()
                recreate()
            }
        }
        themeRow.addView(dark, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        themeRow.addView(View(this), LinearLayout.LayoutParams(dpx(10), 1))
        themeRow.addView(light, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(themeRow)
        col.addView(spacer(16))

        col.addView(View(this).apply {
            setBackgroundColor(Color.parseColor(Palette.hairline))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpx(1)))
        col.addView(spacer(16))

        val current = LocaleHelper.currentTag(this)
        col.addView(accentText(getString(R.string.language_title)))
        col.addView(bodyText(LocaleHelper.languageNames[current] ?: "English"))
        col.addView(spacer(8))
        col.addView(actionButton(getString(R.string.language_choose), false) {
            dialog.dismiss()
            showLanguagePicker()
        })
        col.addView(spacer(16))

        col.addView(View(this).apply {
            setBackgroundColor(Color.parseColor(Palette.hairline))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpx(1)))
        col.addView(spacer(16))

        val hapticRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val hapticInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        hapticInfo.addView(accentText(getString(R.string.settings_haptics)))
        hapticInfo.addView(bodyText(getString(R.string.settings_haptics_hint), 11f))
        hapticRow.addView(hapticInfo, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val hapticSwitch = Switch(this).apply {
            isChecked = prefs.haptics
            showText = false
        }
        fun tintSwitch() {
            hapticSwitch.thumbTintList = ColorStateList.valueOf(
                if (hapticSwitch.isChecked) Palette.neon else Palette.textDim
            )
            hapticSwitch.trackTintList = ColorStateList.valueOf(
                if (hapticSwitch.isChecked) Color.parseColor(if (Palette.dark) "#5522D3FF" else "#33007C9E")
                else Color.parseColor(Palette.chipFill)
            )
        }
        tintSwitch()
        hapticSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.haptics = checked
            Haptics.enabled = checked
            tintSwitch()
            if (checked) hapticSwitch.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
        hapticRow.addView(hapticSwitch, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        col.addView(hapticRow)
        col.addView(spacer(18))
        val autoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        autoRow.addView(accentText(getString(R.string.settings_auto_load_agent)), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        var autoBtn: Button? = null
        autoBtn = actionButton(
            getString(if (prefs.autoLoadAgent) R.string.settings_auto_on else R.string.settings_auto_off),
            prefs.autoLoadAgent
        ) {
            prefs.autoLoadAgent = !prefs.autoLoadAgent
            autoBtn?.text = getString(
                if (prefs.autoLoadAgent) R.string.settings_auto_on else R.string.settings_auto_off
            )
        }
        autoRow.addView(autoBtn)
        col.addView(autoRow)
        col.addView(spacer(18))

        col.addView(View(this).apply {
            setBackgroundColor(Color.parseColor(Palette.hairline))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpx(1)))
        col.addView(spacer(16))

        col.addView(accentText(getString(R.string.settings_downloads)))
        col.addView(spacer(8))
        val pcInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pcInfo.addView(bodyText(getString(R.string.settings_parallel_connections), 13f))
        pcInfo.addView(bodyText(getString(R.string.settings_parallel_connections_hint), 11f))
        col.addView(pcInfo)
        col.addView(spacer(8))
        val pcRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val pcValue = TextView(this).apply {
            text = prefs.parallelConnections.toString()
            setTextColor(Palette.text)
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(dpx(16), dpx(6), dpx(16), dpx(6))
        }
        fun pushConfig() {
            if (!prefs.paired) return
            io.execute {
                runCatching {
                    api.setConfig(
                        prefs.parallelConnections,
                        prefs.autoExtract,
                        prefs.verifySha256,
                        prefs.keepArchive
                    )
                }
            }
        }
        fun syncConnections(v: Int) {
            prefs.parallelConnections = v
            pcValue.text = v.toString()
            pushConfig()
        }
        val minus = actionButton("-", false) {
            syncConnections((prefs.parallelConnections - 1).coerceAtLeast(1))
        }
        val plus = actionButton("+", false) {
            syncConnections((prefs.parallelConnections + 1).coerceAtMost(64))
        }
        pcRow.addView(minus)
        pcRow.addView(pcValue)
        pcRow.addView(plus)
        col.addView(pcRow)
        col.addView(spacer(14))

        fun addToggle(titleRes: Int, hintRes: Int, get: () -> Boolean, set: (Boolean) -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(accentText(getString(titleRes)))
            info.addView(bodyText(getString(hintRes), 11f))
            row.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val sw = Switch(this).apply {
                isChecked = get()
                showText = false
            }
            fun tint() {
                sw.thumbTintList = ColorStateList.valueOf(
                    if (sw.isChecked) Palette.neon else Palette.textDim
                )
                sw.trackTintList = ColorStateList.valueOf(
                    if (sw.isChecked) Color.parseColor(if (Palette.dark) "#5522D3FF" else "#33007C9E")
                    else Color.parseColor(Palette.chipFill)
                )
            }
            tint()
            sw.setOnCheckedChangeListener { _, checked ->
                set(checked)
                tint()
                pushConfig()
            }
            row.addView(sw)
            col.addView(row)
            col.addView(spacer(12))
        }

        addToggle(
            R.string.settings_auto_extract, R.string.settings_auto_extract_hint,
            { prefs.autoExtract }, { prefs.autoExtract = it }
        )
        addToggle(
            R.string.settings_verify_sha256, R.string.settings_verify_sha256_hint,
            { prefs.verifySha256 }, { prefs.verifySha256 = it }
        )
        addToggle(
            R.string.settings_keep_archive, R.string.settings_keep_archive_hint,
            { prefs.keepArchive }, { prefs.keepArchive = it }
        )

        if (prefs.paired) {
            io.execute {
                val r = api.getConfig()
                val j = r.json()
                val remote = j?.optInt("parallelConnections", 0) ?: 0
                val ra = j?.optInt("autoExtract", -1) ?: -1
                val rv = j?.optInt("verifySha256", -1) ?: -1
                val rk = j?.optInt("keepArchive", -1) ?: -1
                if (r.ok && j != null) {
                    val changed =
                        (remote in 1..64 && remote != prefs.parallelConnections) ||
                        (ra in 0..1 && (ra == 1) != prefs.autoExtract) ||
                        (rv in 0..1 && (rv == 1) != prefs.verifySha256) ||
                        (rk in 0..1 && (rk == 1) != prefs.keepArchive)
                    runOnUiThread {
                        if (remote in 1..64) {
                            prefs.parallelConnections = remote
                            pcValue.text = remote.toString()
                        }
                        if (ra in 0..1) prefs.autoExtract = ra == 1
                        if (rv in 0..1) prefs.verifySha256 = rv == 1
                        if (rk in 0..1) prefs.keepArchive = rk == 1
                        if (changed) {
                            dialog.dismiss()
                            showSettingsDialog()
                        }
                    }
                } else if (prefs.paired) {
                    pushConfig()
                }
            }
        }

        col.addView(spacer(18))
        col.addView(accentText(getString(R.string.settings_default_storage)))
        val defInfo = bodyText(getString(R.string.settings_default_storage_hint), 11f)
        col.addView(defInfo)
        col.addView(spacer(8))
        val defRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(defRow)
        if (prefs.paired) {
            io.execute {
                val list = Ps5Client.parseStorage(api.storage())
                runOnUiThread {
                    defRow.removeAllViews()
                    if (list.isEmpty()) {
                        defInfo.text = getString(R.string.storage_error)
                        return@runOnUiThread
                    }
                    val current = list.firstOrNull { it.id == prefs.storageId }
                    if (current != null) {
                        defInfo.text = getString(R.string.settings_default_storage_value, current.label)
                    }
                    for ((idx, s) in list.withIndex()) {
                        if (idx > 0) defRow.addView(View(this), LinearLayout.LayoutParams(dpx(8), 1))
                        defRow.addView(
                            actionButton(s.label, s.id == prefs.storageId) {
                                prefs.storageId = s.id
                                dialog.dismiss()
                                showSettingsDialog()
                            },
                            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        )
                    }
                }
            }
        }
        col.addView(spacer(18))
        col.addView(actionButton(getString(R.string.close), false) { dialog.dismiss() })

        dialog.setContentView(scroll)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            (resources.displayMetrics.heightPixels * 0.85f).toInt()
        )
        dialog.window?.decorView?.layoutDirection = resources.configuration.layoutDirection
    }

    private fun showLanguagePicker() {
        val current = LocaleHelper.currentTag(this)
        val dialog = Dialog(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpx(20), dpx(20), dpx(20), dpx(18))
            background = glassDrawable(26)
        }
        col.addView(titleText(getString(R.string.language_choose), 22f))
        col.addView(spacer(14))
        for (tag in LocaleHelper.languages) {
            val selected = tag == current
            col.addView(actionButton(LocaleHelper.languageNames[tag] ?: tag, selected) {
                if (selected) {
                    dialog.dismiss()
                } else {
                    prefs.language = tag
                    restoreTab = tab
                    dialog.dismiss()
                    recreate()
                }
            })
            col.addView(spacer(8))
        }
        col.addView(spacer(4))
        col.addView(actionButton(getString(R.string.close), false) { dialog.dismiss() })

        dialog.setContentView(col)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.88f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.decorView?.layoutDirection = resources.configuration.layoutDirection
    }

    private fun showConsole() {
        tab = "console"
        navBar.visibility = View.VISIBLE
        for ((k, v) in navItems) {
            val selected = k == "console"
            v.background = chipDrawable(selected)
            v.setTextColor(if (selected) Palette.neon else Palette.textSecondary)
        }
        val col = scrollContent()
        col.addView(titleText(getString(R.string.console_title), 24f))
        col.addView(spacer(12))

        val device = glassCard(16)
        device.addView(accentText(getString(R.string.connection)))
        device.addView(bodyText("${prefs.host}:${prefs.port}"))
        var sysText: TextView? = null
        if (!prefs.paired) {
            device.addView(bodyText(getString(R.string.connection_unpaired_hint)).apply {
                setTextColor(Palette.textSecondary)
            })
            device.addView(spacer(10))
            device.addView(actionButton(getString(R.string.pair_now), true) {
                showSetup("console")
            })
            device.addView(spacer(8))
            device.addView(actionButton(getString(R.string.browse_catalog), false) {
                showCatalog()
            })
        } else {
            val statusText = bodyText(getString(R.string.reading))
            sysText = statusText
            device.addView(statusText)
            device.addView(spacer(10))
            device.addView(actionButton(getString(R.string.unpair), false) {
                prefs.clearPairing()
                updateConnectionPill()
                showConsole()
            })
        }
        col.addView(device)
        col.addView(spacer(14))

        val storageCard = glassCard(16)
        storageCard.addView(accentText(getString(R.string.ps5_storage)))
        var storageHolder: LinearLayout? = null
        if (!prefs.paired) {
            storageCard.addView(bodyText(getString(R.string.storage_locked)).apply {
                setTextColor(Palette.textDim)
            })
        } else {
            val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            storageHolder = holder
            storageCard.addView(holder)
        }
        col.addView(storageCard)
        col.addView(spacer(14))

        if (prefs.paired) {
            io.execute {
                val sys = api.system().json()
                val storage = Ps5Client.parseStorage(api.storage())
                runOnUiThread {
                    if (tab != "console") return@runOnUiThread
                    if (sys != null) {
                        val version = sys.optString("version", "?")
                        val power = sys.optJSONObject("power")?.optString("state", "") ?: ""
                        sysText?.text = if (power.isNotBlank())
                            getString(R.string.service_status_power, version, power)
                        else getString(R.string.service_status, version)
                    } else {
                        sysText?.text = getString(R.string.device_status_failed)
                    }
                    storageHolder?.removeAllViews()
                    if (storage.isEmpty()) {
                        storageHolder?.addView(bodyText(getString(R.string.no_storage)))
                    } else {
                        for (s in storage) {
                            val row = LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(0, dpx(6), 0, dpx(6))
                            }
                            row.addView(bodyText(s.label).apply { setTextColor(Palette.text) })
                            row.addView(
                                bodyText(
                                    getString(
                                        R.string.storage_free_of,
                                        Ps5Client.formatBytes(s.freeBytes),
                                        Ps5Client.formatBytes(s.totalBytes)
                                    ) + if (s.external) " · " + getString(R.string.external) else ""
                                )
                            )
                            storageHolder?.addView(row)
                        }
                    }
                }
            }
        }
    }

    // ----------------------------------------------------------------- cheats

    private fun showCheats() {
        tab = "cheats"
        stopRefresh()
        navBar.visibility = View.VISIBLE
        for ((k, v) in navItems) {
            val selected = k == "cheats"
            v.background = chipDrawable(selected)
            v.setTextColor(if (selected) Palette.neon else Palette.textSecondary)
        }
        val col = scrollContent()
        col.addView(titleText(getString(R.string.cheats_title), 24f))
        col.addView(bodyText(getString(R.string.cheats_subtitle)).apply {
            setTextColor(Palette.textSecondary)
        })
        col.addView(spacer(12))

        val search = glassInput(getString(R.string.cheats_search_hint)).apply {
            setText(cheatsQuery)
            setSelection(text.length)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                    cheatsQuery = s?.toString() ?: ""
                    renderCheatSections()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        col.addView(search)
        col.addView(spacer(10))

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        cheatsStatus = bodyText("").apply { setTextColor(Palette.textDim) }
        toolbar.addView(cheatsStatus, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        cheatsRefresh = actionButton(getString(R.string.cheats_refresh), false) {
            loadCheatGames(true)
        }
        toolbar.addView(cheatsRefresh)
        col.addView(toolbar)
        col.addView(spacer(14))

        val sections = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cheatsSections = sections
        col.addView(sections)
        renderCheatSections()
        loadCheatGames(false)
    }

    private fun loadCheatGames(force: Boolean, retry: Boolean = false) {
        if (cheatsLoading && !retry) return
        cheatsLoading = true
        cheatsError = null
        renderCheatSections()
        io.execute {
            val r = cheatApi.games(force)
            val games = CheatRunnerClient.parseGames(r)
            val refreshing = r.json()?.optBoolean("refreshing") ?: false
            runOnUiThread {
                cheatsLoading = false
                if (r.ok) {
                    cheatGames = games
                    cheatsError = null
                } else {
                    cheatsError = r.message()
                }
                if (r.ok && games.isEmpty() && refreshing && cheatsRetries < 12) {
                    cheatsRetries++
                    cheatsLoading = true
                    renderCheatSections()
                    main.postDelayed({
                        if (tab == "cheats") {
                            loadCheatGames(false, retry = true)
                        } else {
                            cheatsLoading = false
                        }
                    }, 2000)
                } else {
                    cheatsRetries = 0
                    if (tab == "cheats") renderCheatSections()
                }
            }
        }
    }

    private fun renderCheatSections() {
        val container = cheatsSections ?: return
        container.removeAllViews()
        cheatsRefresh?.isEnabled = !cheatsLoading

        val query = cheatsQuery.trim().lowercase()
        val filtered = cheatGames.filter {
            query.isEmpty() ||
                it.titleName.lowercase().contains(query) ||
                it.titleId.lowercase().contains(query)
        }
        val ps5 = filtered.filter { it.platform == "PS5" }.sortedWith(cheatGameOrder)
        val ps4 = filtered.filter { it.platform == "PS4" }.sortedWith(cheatGameOrder)

        cheatsStatus?.text = when {
            cheatsLoading -> getString(R.string.cheats_loading)
            cheatsError != null -> getString(R.string.cheats_status_error)
            else -> resources.getQuantityString(R.plurals.cheats_count, filtered.size, filtered.size)
        }

        if (cheatsError != null && cheatGames.isEmpty()) {
            val card = softCard(16)
            card.addView(accentText(getString(R.string.cheats_unavailable_title)))
            card.addView(spacer(6))
            card.addView(bodyText(getString(R.string.cheats_unavailable_body)))
            card.addView(spacer(6))
            card.addView(bodyText(cheatsError ?: "").apply { setTextColor(Palette.danger) })
            card.addView(spacer(10))
            card.addView(actionButton(getString(R.string.cheats_refresh), true) {
                loadCheatGames(true)
            })
            container.addView(card)
            return
        }
        if (cheatsLoading && cheatGames.isEmpty()) return
        if (cheatGames.isEmpty()) {
            container.addView(bodyText(getString(R.string.cheats_empty)).apply {
                setTextColor(Palette.textDim)
            })
            return
        }
        if (query.isNotEmpty() && filtered.isEmpty()) {
            container.addView(bodyText(getString(R.string.no_matches)).apply {
                setTextColor(Palette.textDim)
            })
            return
        }

        addCheatSection(
            container,
            getString(R.string.cheats_section_ps5),
            getString(R.string.cheats_section_ps5_hint),
            ps5,
            getString(R.string.cheats_section_ps5_empty),
        )
        addCheatSection(
            container,
            getString(R.string.cheats_section_ps4),
            getString(R.string.cheats_section_ps4_hint),
            ps4,
            getString(R.string.cheats_section_ps4_empty),
        )
    }

    private val cheatGameOrder = compareByDescending<CheatGame> { it.running }
        .thenByDescending { it.hasCheat }
        .thenBy { it.titleName.lowercase() }

    private fun addCheatSection(
        parent: LinearLayout,
        title: String,
        hint: String,
        games: List<CheatGame>,
        empty: String,
    ) {
        parent.addView(accentText(title, 13f))
        parent.addView(bodyText(hint, 11f).apply { setTextColor(Palette.textDim) })
        parent.addView(spacer(8))
        if (games.isEmpty()) {
            parent.addView(bodyText(empty).apply { setTextColor(Palette.textDim) })
        } else {
            for (game in games) {
                parent.addView(cheatGameCard(game))
                parent.addView(spacer(10))
            }
        }
        parent.addView(spacer(8))
    }

    private fun cheatGameCard(game: CheatGame): LinearLayout {
        val card = softCard(12)
        card.pressAnimation()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = glassDrawable(12, Palette.coverTop, Palette.coverBottom, mid = Palette.coverMid)
            if (game.hasIcon) load(cheatApi.iconUrl(game)) { crossfade(true) }
        }
        row.addView(icon, LinearLayout.LayoutParams(dpx(48), dpx(48)).also { it.marginEnd = dpx(12) })

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(
            titleText(game.titleName, 15f).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        top.addView(cheatPill(game.platform, Palette.neon, game.platform == "PS5"))
        body.addView(top)

        body.addView(bodyText("${game.titleId} · ${getString(R.string.cheats_version, game.version)}", 11f))
        body.addView(spacer(6))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bottom.addView(cheatPill(getString(R.string.cheats_installed_badge), Palette.success, true))
        bottom.addView(spacerView(6))
        if (game.running) {
            bottom.addView(cheatPill(getString(R.string.cheats_running_badge), Palette.neon, true))
            bottom.addView(spacerView(6))
        }
        val cheatText = if (game.hasCheat) {
            val fmt = game.cheatFormat.uppercase().ifBlank { "CHEAT" }
            getString(R.string.cheats_file_badge, fmt)
        } else {
            getString(R.string.cheats_no_file_badge)
        }
        bottom.addView(
            cheatPill(
                cheatText,
                if (game.hasCheat) Palette.neon else Palette.textDim,
                false,
            )
        )
        body.addView(bottom)

        row.addView(body, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row)
        card.setOnClickListener { showCheatDialog(game) }
        return card
    }

    private fun spacerView(width: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dpx(width), 1)
    }

    private fun cheatPill(text: String, color: Int, filled: Boolean): TextView = TextView(this).apply {
        this.text = text
        textSize = 10f
        setTextColor(color)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dpx(8), dpx(3), dpx(8), dpx(3))
        background = GradientDrawable().apply {
            cornerRadius = 60f
            setColor(
                if (filled) Color.argb(34, Color.red(color), Color.green(color), Color.blue(color))
                else Color.parseColor(Palette.chipFill)
            )
            setStroke(1, Color.argb(80, Color.red(color), Color.green(color), Color.blue(color)))
        }
    }

    private fun showCheatDialog(game: CheatGame) {
        val dialog = Dialog(this)
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassDrawable(28)
            setPadding(dpx(18), dpx(18), dpx(18), dpx(18))
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            titleText(game.titleName, 19f).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        head.addView(actionButton(getString(R.string.close), false) { dialog.dismiss() })
        wrap.addView(head)
        wrap.addView(
            bodyText(
                "${game.platform} · ${game.titleId} · ${getString(R.string.cheats_version, game.version)}"
            ).apply { setTextColor(Palette.textSecondary) }
        )
        wrap.addView(spacer(12))

        val status = bodyText("").apply { setTextColor(Palette.textDim) }
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val launch = actionButton(getString(R.string.cheats_launch), !game.running) {
            launchCheatGame(game, status, dialog)
        }
        val refresh = actionButton(getString(R.string.cheats_refresh), false) {
            loadCheatState(dialog, game, body, status)
        }
        actions.addView(launch, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(spacerView(8))
        actions.addView(refresh, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        wrap.addView(actions)
        wrap.addView(spacer(10))
        wrap.addView(status)
        wrap.addView(spacer(8))
        wrap.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        dialog.setContentView(wrap)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94f).toInt(),
            (resources.displayMetrics.heightPixels * 0.84f).toInt()
        )
        dialog.window?.decorView?.layoutDirection = resources.configuration.layoutDirection
        loadCheatState(dialog, game, body, status)
    }

    private fun loadCheatState(
        dialog: Dialog,
        game: CheatGame,
        container: LinearLayout?,
        status: TextView,
    ) {
        container?.removeAllViews()
        status.text = getString(R.string.cheats_loading_state)
        if (!game.hasCheat) {
            status.text = getString(R.string.cheats_no_file_badge)
            container?.addView(bodyText(getString(R.string.cheats_no_file)).apply {
                setTextColor(Palette.textSecondary)
            })
            container?.addView(spacer(6))
            container?.addView(bodyText(getString(R.string.cheats_no_file_hint)).apply {
                setTextColor(Palette.textDim)
            })
            return
        }
        io.execute {
            val r = cheatApi.state(game.titleId)
            runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                val holder = container ?: return@runOnUiThread
                holder.removeAllViews()
                if (!r.ok) {
                    if (r.code == 404) {
                        status.text = getString(R.string.cheats_no_file_badge)
                        holder.addView(bodyText(getString(R.string.cheats_no_file)).apply {
                            setTextColor(Palette.textSecondary)
                        })
                        holder.addView(spacer(6))
                        holder.addView(bodyText(getString(R.string.cheats_no_file_hint)).apply {
                            setTextColor(Palette.textDim)
                        })
                    } else {
                        status.text = getString(R.string.cheats_state_failed, r.message())
                        holder.addView(actionButton(getString(R.string.cheats_refresh), true) {
                            loadCheatState(dialog, game, holder, status)
                        })
                    }
                    return@runOnUiThread
                }
                val state = CheatRunnerClient.parseState(r)
                if (state == null) {
                    status.text = getString(R.string.cheats_state_failed, r.message())
                    return@runOnUiThread
                }
                status.text = resources.getQuantityString(
                    R.plurals.cheats_summary,
                    state.mods.size,
                    state.summaryOn,
                    state.summaryOff,
                    state.summaryNotReady,
                )

                val info = softCard(12)
                info.addView(accentText(getString(R.string.cheats_format, state.cheatFormat.uppercase().ifBlank { "?" })))
                info.addView(bodyText(
                    if (state.gameRunning) getString(R.string.cheats_game_running)
                    else getString(R.string.cheats_game_not_running)
                ).apply {
                    setTextColor(if (state.gameRunning) Palette.success else Palette.textDim)
                })
                holder.addView(info)
                holder.addView(spacer(10))

                if (state.mods.isEmpty()) {
                    holder.addView(bodyText(getString(R.string.cheats_no_mods)).apply {
                        setTextColor(Palette.textDim)
                    })
                    return@runOnUiThread
                }
                for (mod in state.mods) {
                    holder.addView(cheatModCard(dialog, game, holder, status, mod))
                    holder.addView(spacer(8))
                }
            }
        }
    }

    private fun cheatModCard(
        dialog: Dialog,
        game: CheatGame,
        container: LinearLayout,
        status: TextView,
        mod: CheatMod,
    ): LinearLayout {
        val card = softCard(12)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(titleText(mod.name, 14f).apply { maxLines = 2 })
        val stateText = when (mod.visualState) {
            "on" -> getString(R.string.cheats_state_on)
            "mixed" -> getString(R.string.cheats_state_mixed)
            "conflict" -> getString(R.string.cheats_state_conflict)
            "error" -> getString(R.string.cheats_state_error)
            "disabled" -> getString(R.string.cheats_state_disabled)
            else -> getString(R.string.cheats_state_off)
        }
        info.addView(bodyText(stateText, 11f).apply {
            setTextColor(
                when (mod.visualState) {
                    "on", "mixed" -> Palette.success
                    "conflict", "error" -> Palette.danger
                    else -> Palette.textDim
                }
            )
        })
        if (mod.blockReason.isNotBlank()) {
            info.addView(bodyText(mod.blockReason, 10f).apply { setTextColor(Palette.textDim) })
        }
        row.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val toggle = Switch(this).apply {
            showText = false
            isChecked = mod.visualState == "on" || mod.visualState == "mixed"
            isEnabled = mod.canToggle
            thumbTintList = ColorStateList.valueOf(
                if (isChecked) Palette.neon else Palette.textDim
            )
            trackTintList = ColorStateList.valueOf(
                if (isChecked) Color.parseColor(if (Palette.dark) "#5522D3FF" else "#33007C9E")
                else Color.parseColor(Palette.chipFill)
            )
        }
        var updating = false
        toggle.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            toggle.isEnabled = false
            io.execute {
                val r = cheatApi.toggle(game.titleId, mod.index, checked)
                runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    if (r.ok) {
                        loadCheatState(dialog, game, container, status)
                    } else {
                        toast(getString(R.string.cheats_toggle_failed, r.message()))
                        updating = true
                        toggle.isChecked = !checked
                        updating = false
                        toggle.isEnabled = mod.canToggle
                    }
                }
            }
        }
        row.addView(toggle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        card.addView(row)
        return card
    }

    private fun launchCheatGame(game: CheatGame, status: TextView, dialog: Dialog) {
        status.text = getString(R.string.cheats_launching)
        io.execute {
            val r = cheatApi.launch(game.titleId)
            runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                if (r.ok) {
                    status.text = getString(R.string.cheats_launch_sent)
                    toast(getString(R.string.cheats_launch_sent))
                } else {
                    status.text = getString(R.string.cheats_launch_failed, r.message())
                    toast(getString(R.string.cheats_launch_failed, r.message()))
                }
            }
        }
    }

    // --------------------------------------------------------------- adapter

    private inner class CatalogAdapter(
        private val items: MutableList<Game>,
        private val onClick: (Game) -> Unit,
    ) : RecyclerView.Adapter<CatalogAdapter.VH>() {

        inner class VH(val card: LinearLayout) : RecyclerView.ViewHolder(card)

        fun submit(list: List<Game>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val card = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = glassDrawable(20)
                setPadding(dpx(16), dpx(16), dpx(16), dpx(16))
            }
            card.layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dpx(12) }
            card.pressAnimation()
            return VH(card)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val game = items[position]
            val card = holder.card
            card.removeAllViews()

            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val cover = ImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                background = glassDrawable(12, Palette.coverTop, Palette.coverBottom, mid = Palette.coverMid)
                if (game.cover.startsWith("http")) loadCover(game.cover)
            }
            row.addView(
                cover,
                LinearLayout.LayoutParams(dpx(68), dpx(92)).also { it.marginEnd = dpx(14) }
            )

            val body = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }

            val top = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val title = titleText(game.title, 17f).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            val badge = TextView(this@MainActivity).apply {
                text = if (game.platform == "ps5") "PS5" else "PS4"
                textSize = 10f
                setPadding(dpx(9), dpx(4), dpx(9), dpx(4))
                background = chipDrawable(game.platform == "ps5")
                setTextColor(Palette.neon)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            top.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(badge)
            body.addView(top)

            val meta = buildString {
                append(game.titleId)
                if (game.region.isNotBlank()) append(" · ").append(game.region)
                if (game.genre.isNotBlank()) append(" · ").append(game.genre)
            }
            body.addView(bodyText(meta, 12f))

            val bottom = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dpx(8), 0, 0)
            }
            bottom.addView(
                accentText(
                    resources.getQuantityString(
                        R.plurals.options_summary,
                        game.options.size,
                        sizeLabel(game.bestSize),
                        game.options.size,
                    ),
                    11f
                ),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            bottom.addView(accentText(getString(R.string.view_options), 11f))
            body.addView(bottom)

            row.addView(body, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(row)

            card.setOnClickListener { onClick(game) }
        }
    }
}
