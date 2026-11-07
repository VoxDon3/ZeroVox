package com.orbit.zero

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
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
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    companion object {
        private var restoreTab: String? = null
    }

    private lateinit var prefs: Prefs
    private lateinit var api: Ps5Client
    private lateinit var cheatApi: CheatRunnerClient
    private val io = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    private lateinit var content: FrameLayout
    private lateinit var navBar: LinearLayout
    private lateinit var statusPill: TextView
    private val navItems = LinkedHashMap<String, TextView>()

    private var tab = "catalog"
    private var catalogAdapter: CatalogAdapter? = null
    private var catalogCount: TextView? = null
    private var catalogEmpty: TextView? = null
    private var catalogList: RecyclerView? = null
    private var catalogRetry: Button? = null
    private var catalogLoading = false
    private var query = ""
    private var platform = "all"
    private var refreshRunnable: Runnable? = null
    private var refreshing = false
    private var connectionCheckRunning = false
    private var setupAfterPair = "catalog"

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
        val startupPrefs = getSharedPreferences("orbit_zero", MODE_PRIVATE)
        setTheme(if (startupPrefs.getBoolean("lightTheme", false)) R.style.Theme_Orbit_Light else R.style.Theme_Orbit)
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Palette.applyTheme(prefs.lightTheme)
        Haptics.enabled = prefs.haptics
        api = Ps5Client(prefs)
        cheatApi = CheatRunnerClient(prefs)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        requestNotificationPermission()
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
    }

    override fun onResume() {
        super.onResume()
        if (::statusPill.isInitialized) refreshConnectionPill()
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

    override fun onDestroy() {
        super.onDestroy()
        stopRefresh()
        io.shutdownNow()
    }

    private fun portFrom(text: String): Int =
        text.toIntOrNull()?.coerceIn(1, 65535) ?: 34177

    private fun statusDot(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setSize(dpx(8), dpx(8))
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
        }
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
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
            setPadding(dpx(20), dpx(14), dpx(20), dpx(10))
        }
        val orbit = TextView(this).apply {
            text = "ZERO"
            textSize = 21f
            setTextColor(Palette.neon)
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            letterSpacing = 0.18f
        }
        val zero = TextView(this).apply {
            text = "VOX"
            textSize = 21f
            setTextColor(Palette.text)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            letterSpacing = 0.18f
            setPadding(dpx(6), 0, 0, 0)
        }
        statusPill = TextView(this).apply {
            textSize = 11f
            setPadding(dpx(12), dpx(6), dpx(12), dpx(6))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            isSingleLine = true
            includeFontPadding = false
            gravity = Gravity.CENTER
            compoundDrawablePadding = dpx(6)
        }
        val statusColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        statusColumn.addView(statusPill)
        val settingsButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_settings)
            setColorFilter(Palette.textSecondary)
            contentDescription = getString(R.string.settings_title)
            setPadding(dpx(7), dpx(7), dpx(7), dpx(7))
            pressAnimation()
            setOnClickListener { showSettingsDialog() }
        }
        statusColumn.addView(
            settingsButton,
            LinearLayout.LayoutParams(dpx(34), dpx(34)).apply {
                gravity = Gravity.END
                topMargin = dpx(2)
            }
        )
        header.addView(orbit)
        header.addView(zero)
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(
            statusColumn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(header)

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = glassDrawable(22)
            setPadding(dpx(6), dpx(6), dpx(6), dpx(6))
            visibility = View.GONE
        }
        val navParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dpx(16), dpx(4), dpx(16), dpx(14))
        }
        root.addView(navBar, navParams)

        addNavItem("catalog", getString(R.string.nav_catalog))
        addNavItem("downloads", getString(R.string.nav_downloads))
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
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dpx(8), dpx(11), dpx(8), dpx(11))
            pressAnimation()
            setOnClickListener { setTab(key) }
        }
        navItems[key] = item
        navBar.addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
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
        statusPill.text = text
        statusPill.setTextColor(color)
        statusPill.setCompoundDrawablesRelativeWithIntrinsicBounds(statusDot(color), null, null, null)
        statusPill.background = if (connected) glassDrawable(
            30, Palette.connectedTop, Palette.connectedBottom,
            Color.parseColor(Palette.connectedStroke), mid = Palette.connectedMid
        ) else glassDrawable(
            30, Palette.unpairedTop, Palette.unpairedBottom,
            Color.parseColor(Palette.unpairedStroke), mid = Palette.unpairedMid
        )
    }

    private fun refreshConnectionPill() {
        if (!prefs.paired) {
            updateConnectionPill()
            return
        }
        if (connectionCheckRunning) return
        connectionCheckRunning = true
        setConnectionPill(getString(R.string.status_checking), Palette.textSecondary, false)
        io.execute {
            val online = api.system().ok
            runOnUiThread {
                connectionCheckRunning = false
                if (!prefs.paired) {
                    updateConnectionPill()
                } else if (online) {
                    updateConnectionPill()
                } else {
                    setConnectionPill(getString(R.string.status_offline), Palette.danger, false)
                }
            }
        }
    }

    private fun scrollContent(): LinearLayout {
        content.removeAllViews()
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpx(20), dpx(6), dpx(20), dpx(28))
        }
        scroll.addView(col, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        content.addView(scroll, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        return col
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------ setup

    @SuppressLint("SetTextI18n")
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
        col.addView(spacer(18))

        val card = glassCard(20)
        card.addView(accentText(getString(R.string.label_console_address)))
        card.addView(spacer(6))

        val host = glassInput(getString(R.string.hint_ip)).apply {
            setText(prefs.host)
        }
        card.addView(host)
        card.addView(spacer(8))

        val port = glassInput(getString(R.string.hint_port), numeric = true).apply {
            setText(prefs.port.toString())
        }
        card.addView(port)
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
            prefs.port = portFrom(port.text.toString())
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
            val p = portFrom(port.text.toString())
            val c = code.text.toString().trim()
            if (h.isBlank() || c.length != 6) {
                status.text = getString(R.string.pair_invalid)
                return@actionButton
            }
            prefs.host = h
            prefs.port = p
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

        val catalogCard = softCard(16)
        catalogCard.addView(accentText(getString(R.string.catalog_title)))
        catalogCard.addView(spacer(4))
        val catStatus = bodyText(
            if (CatalogRepo.games.isEmpty()) getString(R.string.catalog_not_loaded)
            else resources.getQuantityString(
                R.plurals.catalog_games_ready,
                CatalogRepo.games.size,
                CatalogRepo.games.size,
            )
        )
        catalogCard.addView(catStatus)
        catalogCard.addView(spacer(10))
        catalogCard.addView(actionButton(getString(R.string.catalog_refresh), false) {
            catStatus.text = getString(R.string.refreshing)
            io.execute {
                val ok = CatalogRepo.load(this@MainActivity, true)
                runOnUiThread {
                    catStatus.text = if (ok) resources.getQuantityString(
                        R.plurals.catalog_games_ready,
                        CatalogRepo.games.size,
                        CatalogRepo.games.size,
                    ) else getString(R.string.catalog_refresh_failed, CatalogRepo.lastError ?: "")
                }
            }
        })
        col.addView(catalogCard)
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
                    applyCatalogFilter()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(search)
        root.addView(spacer(10))

        if (!prefs.paired) {
            val banner = softCard(14)
            banner.addView(bodyText(getString(R.string.not_paired_banner)))
            banner.addView(spacer(8))
            banner.addView(actionButton(getString(R.string.pair_button), false) { showSetup() })
            root.addView(banner)
            root.addView(spacer(10))
        }

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
        root.addView(spacer(10))

        catalogCount = bodyText("")
        root.addView(catalogCount)
        root.addView(spacer(8))

        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(0, 0, 0, dpx(18))
        }
        catalogAdapter = CatalogAdapter(ArrayList()) { game -> showOptions(game) }
        list.adapter = catalogAdapter
        catalogList = list
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        catalogEmpty = bodyText(getString(R.string.loading_catalog)).apply {
            gravity = Gravity.CENTER
            setPadding(dpx(20), dpx(30), dpx(20), dpx(30))
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

        content.addView(root, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        applyCatalogFilter()
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
        catalogLoading = true
        if (tab == "catalog") applyCatalogFilter()
        io.execute {
            CatalogRepo.load(this@MainActivity, force)
            runOnUiThread {
                catalogLoading = false
                if (tab == "catalog") applyCatalogFilter()
            }
        }
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
            card.addView(bodyText("${Ps5Client.formatBytes(option.sizeBytes)} · ${option.filename}"))
            card.addView(spacer(8))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val ps5 = actionButton(getString(R.string.download_ps5), true) {
                dialog.dismiss()
                if (!prefs.paired) {
                    toast(getString(R.string.pair_required))
                    showSetup()
                    return@actionButton
                }
                chooseStorage(getString(R.string.download_to_ps5)) { storage ->
                    startConsoleDownload(option, storage)
                }
            }
            val phone = actionButton(getString(R.string.phone), false) {
                dialog.dismiss()
                if (!prefs.paired) {
                    toast(getString(R.string.pair_required))
                    showSetup()
                    return@actionButton
                }
                chooseStorage(getString(R.string.download_to_phone)) { storage ->
                    startPhoneTransfer(option, storage)
                }
            }
            phone.isEnabled = option.phoneAvailable
            phone.alpha = if (option.phoneAvailable) 1f else 0.4f
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
                val labels = list.map {
                    getString(R.string.storage_item, it.label, Ps5Client.formatBytes(it.freeBytes))
                }.toTypedArray()
                val dialog = AlertDialog.Builder(this)
                    .setTitle(title)
                    .setItems(labels) { _, which -> onPick(list[which]) }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
                dialog.window?.decorView?.layoutDirection = resources.configuration.layoutDirection
            }
        }
    }

    private fun startConsoleDownload(option: GameOption, storage: Storage) {
        io.execute {
            val r = api.startDownload(option.releaseId, storage.id)
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

        if (!prefs.paired) {
            val card = glassCard(18)
            card.addView(bodyText(getString(R.string.not_paired_banner)).apply {
                setTextColor(Palette.textSecondary)
            })
            card.addView(spacer(12))
            card.addView(actionButton(getString(R.string.pair_now), true) {
                showSetup("downloads")
            })
            card.addView(spacer(8))
            card.addView(actionButton(getString(R.string.browse_catalog), false) {
                showCatalog()
            })
            col.addView(card)
            col.addView(spacer(16))
            addLockedSection(col, getString(R.string.phone_transfer), getString(R.string.transfers_locked))
            col.addView(hairline())
            addLockedSection(col, getString(R.string.ps5_queue), getString(R.string.queue_locked))
            col.addView(hairline())
            addLockedSection(col, getString(R.string.install_on_ps5), getString(R.string.installs_locked))
            return
        }

        col.addView(accentText(getString(R.string.phone_transfer)))
        val local = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(local)
        col.addView(hairline())
        col.addView(accentText(getString(R.string.ps5_queue)))
        val queue = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(queue)
        col.addView(hairline())
        col.addView(accentText(getString(R.string.install_on_ps5)))
        val installs = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(installs)
        val note = bodyText("").apply { setTextColor(Palette.textDim) }
        col.addView(note)

        fun refresh() {
            if (tab != "downloads") return
            renderLocal(local)
            if (!refreshing) {
                refreshing = true
                io.execute {
                    val jobs = Ps5Client.parseJobs(api.downloads())
                    val receipts = Ps5Client.parseReceipts(api.installations())
                    runOnUiThread {
                        refreshing = false
                        if (tab == "downloads") {
                            renderJobs(queue, jobs)
                            renderReceipts(installs, receipts)
                            note.text = getString(R.string.last_update_now)
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
        if (t.state == "idle") {
            container.addView(bodyText(getString(R.string.no_phone_transfer)))
            return
        }
        val card = softCard(14)
        card.addView(titleText(t.title.ifBlank { t.filename }, 15f))
        val stateLabel = when (t.state) {
            "preparing" -> getString(R.string.state_preparing)
            "downloading" -> getString(R.string.state_downloading)
            "verifying" -> getString(R.string.state_verifying)
            "sent" -> getString(R.string.state_sent)
            "complete" -> getString(R.string.state_complete)
            "error" -> getString(R.string.state_error)
            "cancelled" -> getString(R.string.state_cancelled)
            else -> t.state
        }
        card.addView(bodyText(stateLabel))
        if (t.total > 0) {
            card.addView(progressBar().apply { progress = t.percent })
            card.addView(
                bodyText(
                    "${Ps5Client.formatBytes(t.received)} / ${Ps5Client.formatBytes(t.total)}" +
                        if (t.speed > 0) " · ${Ps5Client.formatBytes(t.speed.toLong())}/s" else ""
                )
            )
        }
        when {
            t.state == "error" && t.message.isNotBlank() ->
                card.addView(bodyText(getString(R.string.error_detail, t.message)).apply {
                    setTextColor(Palette.danger)
                })
            t.state == "sent" ->
                card.addView(bodyText(getString(R.string.transfer_sent_note)).apply {
                    setTextColor(Palette.textDim)
                })
            t.message.isNotBlank() ->
                card.addView(bodyText(t.message).apply { setTextColor(Palette.textDim) })
        }
        if (t.running) {
            card.addView(spacer(8))
            card.addView(actionButton(getString(R.string.cancel_transfer), false) {
                TransferStore.cancelRequested = true
                toast(getString(R.string.cancelling))
            })
        }
        container.addView(card)
    }

    private fun renderJobs(container: LinearLayout, jobs: List<Job>) {
        container.removeAllViews()
        if (jobs.isEmpty()) {
            container.addView(bodyText(getString(R.string.queue_empty)))
            return
        }
        for (job in jobs) {
            val card = softCard(14)
            card.addView(titleText(job.title, 15f))
            card.addView(
                bodyText(
                    "${Ps5Client.statusLabel(this, job.status)} · ${job.format}" +
                        if (job.titleId.isNotBlank()) " · ${job.titleId}" else ""
                ).apply {
                    setTextColor(if (job.status == "error") Palette.danger else Palette.textSecondary)
                }
            )
            if (job.total > 0) {
                card.addView(progressBar().apply { progress = job.percent })
                card.addView(
                    bodyText(
                        "${Ps5Client.formatBytes(job.received)} / ${Ps5Client.formatBytes(job.total)}" +
                            if (job.speed > 0) " · ${Ps5Client.formatBytes(job.speed.toLong())}/s" else ""
                    )
                )
            }
            if (job.error.isNotBlank()) {
                card.addView(bodyText(job.error).apply { setTextColor(Palette.danger) })
            }
            card.addView(spacer(8))

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
            container.addView(spacer(10))
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
            val card = softCard(14)
            card.addView(titleText(receipt.title.ifBlank { getString(R.string.ps5_package) }, 15f))
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
            container.addView(spacer(10))
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

        val catCard = glassCard(16)
        catCard.addView(accentText(getString(R.string.catalog_title)))
        val catText = bodyText(resources.getQuantityString(
            R.plurals.catalog_summary,
            CatalogRepo.games.size,
            CatalogRepo.games.size,
            CatalogRepo.revision,
        ))
        catCard.addView(catText)
        catCard.addView(spacer(10))
        catCard.addView(actionButton(getString(R.string.catalog_refresh), false) {
            catText.text = getString(R.string.refreshing)
            io.execute {
                val ok = CatalogRepo.load(this@MainActivity, true)
                runOnUiThread {
                    catText.text = if (ok) resources.getQuantityString(
                        R.plurals.catalog_summary,
                        CatalogRepo.games.size,
                        CatalogRepo.games.size,
                        CatalogRepo.revision,
                    ) else getString(R.string.load_failed_short, CatalogRepo.lastError ?: "")
                }
            }
        })
        col.addView(catCard)
        col.addView(spacer(14))

        val about = softCard(16)
        about.addView(accentText(getString(R.string.about_title)))
        about.addView(spacer(6))
        about.addView(bodyText(getString(R.string.about_body)))
        col.addView(about)
        col.addView(spacer(10))
        col.addView(bodyText(getString(R.string.version, BuildConfig.VERSION_NAME)).apply {
            setTextColor(Palette.textDim)
        })

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
                setPadding(dpx(16), dpx(14), dpx(16), dpx(14))
            }
            card.layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dpx(10) }
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
                if (game.cover.startsWith("http")) load(game.cover) { crossfade(true) }
            }
            row.addView(
                cover,
                LinearLayout.LayoutParams(dpx(58), dpx(78)).also { it.marginEnd = dpx(12) }
            )

            val body = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }

            val top = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val title = titleText(game.title, 16f).apply {
                maxLines = 1
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
                        Ps5Client.formatBytes(game.bestSize),
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
