package com.sayad.orders.pro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class AcceptService : AccessibilityService() {

    private class Item(val node: AccessibilityNodeInfo, val text: String, val id: String?)

    private val prefs: SharedPreferences by lazy { Prefs.of(this) }
    private val handler = Handler(Looper.getMainLooper())

    private var burstTarget: Target? = null
    private var burstUntil = 0L
    private var lastProcess = 0L
    private var lastDiag = 0L
    private var lastScreenDump = 0L
    private var evTotal = 0
    private var evJeeny = 0
    private var evPetra = 0
    private var lastEvWrite = 0L
    private var lastKey = ""
    private var lastClickAt = 0L
    private val seen = LinkedHashSet<String>()

    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var panelMasterSwitch: MaterialSwitch? = null
    private val panelSwitches = HashMap<String, MaterialSwitch>()
    private lateinit var panelGroup: CollapsibleGroup

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.MASTER || key == Prefs.OVERLAY) handler.post { syncBubble() }
        if (key == Prefs.MASTER) handler.post { syncPanelMasterState() }
        if (key != null) {
            panelSwitches[key]?.let { sw ->
                val v = prefs.getBoolean(key, sw.isChecked)
                if (sw.isChecked != v) handler.post { sw.isChecked = v }
            }
        }
    }

    /** إعادة فحص سريعة بعد كل حدث، لأن الطلب بيظهر ويختفي بسرعة. */
    private val burst = object : Runnable {
        override fun run() {
            val t = burstTarget
            if (t != null && SystemClock.uptimeMillis() < burstUntil) {
                process(t)
                handler.postDelayed(this, 12)
            } else {
                burstTarget = null
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        syncBubble()
        startLicenseExpiryWatcher()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName) return

        evTotal++
        val known = Target.forPackage(pkg)
        if (known == Target.JEENY) evJeeny++ else if (known == Target.PETRA) evPetra++
        val nowE = SystemClock.uptimeMillis()
        if (nowE - lastEvWrite > 700) {
            lastEvWrite = nowE
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val cls = event?.className?.toString()?.substringAfterLast('.') ?: "-"
            val type = AccessibilityEvent.eventTypeToString(event?.eventType ?: 0).removePrefix("TYPE_")
            prefs.edit().putString(
                Prefs.DIAG_EVENTS,
                "أحداث: الكل $evTotal · Jeeny $evJeeny · Petra $evPetra\nآخر حدث: ${pkg.substringAfterLast('.')} / $type / $cls ($time)"
            ).apply()
        }

        if (seen.add(pkg)) {
            while (seen.size > 8) seen.remove(seen.first())
            prefs.edit().putString(Prefs.DIAG_SEEN, seen.joinToString("\n")).apply()
        }

        val t = Target.forPackage(pkg) ?: return
        if (!prefs.getBoolean(Prefs.MASTER, true) || !t.enabled(prefs)) return

        val now = SystemClock.uptimeMillis()
        burstUntil = now + 6000
        if (burstTarget == null) {
            burstTarget = t
            handler.postDelayed(burst, 12)
        } else {
            burstTarget = t
        }
        if (now - lastProcess > 8) process(t)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeBubble()
        removePanel()
        try { prefs.unregisterOnSharedPreferenceChangeListener(prefListener) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ---------------------------------------------------------------- المعالجة

    private fun process(t: Target) {
        lastProcess = SystemClock.uptimeMillis()
        if (!LicenseManager.isActive(this)) {
            prefs.edit().putString(Prefs.DIAG_RESULT, "مقفول 🔒 أدخل كود التفعيل من التطبيق").apply()
            return
        }
        if (!prefs.getBoolean(Prefs.MASTER, true)) return
        val f = t.filter(prefs)
        if (!f.enabled) return

        val result = try {
            scan(t, f)
        } catch (e: Exception) {
            "ERROR ${e.javaClass.simpleName}"
        }

        val now = SystemClock.uptimeMillis()
        if (now - lastDiag > 500 || result.startsWith("CLICKED")) {
            lastDiag = now
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            prefs.edit().putString(Prefs.DIAG_RESULT, "${t.label}: $result  ($time)").apply()
        }
    }

    private fun scan(t: Target, f: Filter): String {
        val roots = rootsFor(t)
        if (roots.isEmpty()) return "NO_WINDOW"

        val items = ArrayList<Item>()
        for (r in roots) collect(r, items, 0)
        dumpScreen(t, items)

        // زر القبول: بالـ ID (Petra) أو بالنص (Jeeny)
        val acceptItem = items.firstOrNull { it.id?.endsWith(":id/accept_button") == true }
            ?: items.firstOrNull { OfferParser.isAccept(it.text) && clickableOf(it.node) != null }
            ?: return "NO_OFFER"

        val texts = items.map { it.text }.filter { it.isNotBlank() }

        val priceById = items.firstOrNull { it.id?.endsWith(":id/estimated_trip_price") == true }
        val price = (priceById?.let { OfferParser.decimal(it.text) }) ?: OfferParser.findPrice(texts)

        val minutesById = items.firstOrNull { it.id?.endsWith(":id/estimated_time_to_pick_up") == true }
        val minutes = (minutesById?.let { OfferParser.integer(it.text) }) ?: OfferParser.findMinutes(texts)

        if (price == null && !f.acceptNoPrice) return "INCOMPLETE (no price)"
        if (price != null) {
            if (price < f.minPrice) return "REJECTED price=$price"
            if (f.useRange && price > f.maxPrice) return "REJECTED price=$price"
        }
        if (minutes != null && f.maxMinutes > 0 && minutes > f.maxMinutes) {
            return "REJECTED minutes=$minutes"
        }

        val key = "${t.key}|$price|$minutes"
        val now = SystemClock.uptimeMillis()
        if (key == lastKey && now - lastClickAt < 900) return "COOLDOWN"

        if (!click(acceptItem.node)) return "CLICK_FAILED"

        lastKey = key
        lastClickAt = now
        burstUntil = 0
        val c = prefs.getInt(Prefs.ACCEPTED, 0) + 1
        prefs.edit().putInt(Prefs.ACCEPTED, c).apply()
        beep()
        LicenseManager.reportAccepted(this, t.label, price)

        if (prefs.getBoolean(Prefs.EXCLUSIVE, true)) {
            val ed = prefs.edit()
            for (o in Target.values()) if (o != t) ed.putBoolean("${o.key}_enabled", false)
            ed.apply()
        }

        return "CLICKED price=$price min=$minutes"
    }

    // ---------------------------------------------------------------- قراءة الشاشة

    private fun rootsFor(t: Target): List<AccessibilityNodeInfo> {
        val list = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                val r = w.root ?: continue
                val pk = r.packageName?.toString()
                if (pk != null && pk in t.packages) list.add(r)
            }
        } catch (_: Exception) {
        }
        if (list.isEmpty()) {
            val r = rootInActiveWindow
            val pk = r?.packageName?.toString()
            if (r != null && pk != null && pk in t.packages) list.add(r)
        }
        return list
    }

    private fun collect(n: AccessibilityNodeInfo, out: MutableList<Item>, depth: Int) {
        if (depth > 45 || out.size > 600) return
        val txt = (n.text ?: n.contentDescription)?.toString() ?: ""
        val id = n.viewIdResourceName
        if (txt.isNotBlank() || id != null) out.add(Item(n, txt.trim(), id))
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            collect(c, out, depth + 1)
        }
    }

    private val snaps = ArrayList<Pair<Int, String>>()

    /** تشخيص فقط: آخر 4 لقطات مختلفة للشاشة (الأحدث أول). */
    private fun dumpScreen(t: Target, items: List<Item>) {
        val now = SystemClock.uptimeMillis()
        if (now - lastScreenDump < 800) return
        lastScreenDump = now
        val sb = StringBuilder("[${t.label}]\n")
        for (item in items) {
            if (item.text.isBlank()) continue
            val shortId = item.id?.substringAfter(":id/")
            if (shortId != null) sb.append("{").append(shortId).append("} ")
            sb.append(item.text).append("\n")
            if (sb.length > 1200) break
        }
        val text = sb.toString()
        val h = text.hashCode()
        if (snaps.isNotEmpty() && snaps[snaps.size - 1].first == h) return
        snaps.removeAll { it.first == h }
        snaps.add(Pair(h, text))
        while (snaps.size > 4) snaps.removeAt(0)
        val joined = snaps.reversed().joinToString("\n──────────\n") { it.second }
        prefs.edit().putString(Prefs.DIAG_SCREEN, joined).apply()
    }

    // ---------------------------------------------------------------- الضغط

    private fun clickableOf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        var i = 0
        while (n != null && i < 7) {
            if (n.isClickable && n.isEnabled) return n
            n = n.parent
            i++
        }
        return null
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        val target = clickableOf(node)
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        return tap(node)
    }

    private fun tap(node: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        val p = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 35))
            .build()
        return dispatchGesture(g, null, null)
    }

    private fun beep() {
        if (!prefs.getBoolean(Prefs.SOUND, true)) return
        if (prefs.getString(Prefs.SOUND_MODE, "alarm") == "custom") CustomSound.play(this)
        else Alarm.play(this)
    }

    // ---------------------------------------------------------------- الزر العائم

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun syncBubble() {
        if (!prefs.getBoolean(Prefs.OVERLAY, true)) {
            removeBubble()
            return
        }
        if (bubble == null) addBubble()
        val on = prefs.getBoolean(Prefs.MASTER, true)
        val b = bubble ?: return
        b.text = if (on) "ON" else "OFF"
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(Color.parseColor(if (on) "#7C3AED" else "#B23A3A"))
        bg.setStroke(dp(2), Color.parseColor("#C084FC"))
        b.background = bg
    }

    private fun addBubble() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val tv = TextView(this)
        tv.gravity = Gravity.CENTER
        tv.textSize = 12f
        tv.setTextColor(Color.WHITE)
        tv.typeface = Typeface.DEFAULT_BOLD

        val lp = WindowManager.LayoutParams(
            dp(52), dp(52),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(12)
        lp.y = dp(180)
        bubbleParams = lp

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val mx = (e.rawX - downX).toInt()
                    val my = (e.rawY - downY).toInt()
                    if (abs(mx) > dp(6) || abs(my) > dp(6)) moved = true
                    if (moved) {
                        lp.x = startX + mx
                        lp.y = startY + my
                        try { wm.updateViewLayout(tv, lp) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        val now = prefs.getBoolean(Prefs.MASTER, true)
                        prefs.edit().putBoolean(Prefs.MASTER, !now).apply()
                    } else {
                        val dm = resources.displayMetrics
                        val centerX = lp.x + dp(26)
                        lp.x = if (centerX < dm.widthPixels / 2) 0 else dm.widthPixels - dp(52)
                        try { wm.updateViewLayout(tv, lp) } catch (_: Exception) {}
                    }
                }
            }
            true
        }

        try {
            wm.addView(tv, lp)
            bubble = tv
        } catch (_: Exception) {
        }
    }

    private fun removeBubble() {
        val b = bubble ?: return
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(b)
        } catch (_: Exception) {
        }
        bubble = null
    }

    // ---------------------------------------------------------------- اللوحة العائمة (تشغيل/إيقاف + إعدادات جيني وبيترا)

    private fun togglePanel() {
        if (panel != null) removePanel() else showPanel()
    }

    private fun showPanel() {
        if (panel != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val themed = ContextThemeWrapper(this, R.style.Theme_Sayad)
        val root = LayoutInflater.from(themed).inflate(R.layout.overlay_panel, null) as LinearLayout

        root.findViewById<TextView>(R.id.panelClose).setOnClickListener { removePanel() }

        val master = root.findViewById<MaterialSwitch>(R.id.panelMaster)
        panelMasterSwitch = master
        master.isChecked = prefs.getBoolean(Prefs.MASTER, true)
        master.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(Prefs.MASTER, checked).apply()
            applyPanelLock(root, checked)
        }

        panelGroup = CollapsibleGroup(prefs, "panel_open_card")
        panelSwitches.clear()
        val container = root.findViewById<LinearLayout>(R.id.panelAppsContainer)
        for (t in Target.values()) panelAddAppCard(themed, container, t)

        applyPanelLock(root, master.isChecked)

        val panelMin = root.findViewById<TextView>(R.id.panelMin)
        panelMin.setOnClickListener {
            val body = root.findViewById<View>(R.id.panelScroll)
            val hide = body.visibility == View.VISIBLE
            body.visibility = if (hide) View.GONE else View.VISIBLE
            panelMin.text = if (hide) "▢" else "—"
        }

        val bubbleLp = bubbleParams
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START

        val dm = resources.displayMetrics
        val estPanelW = dp(290)
        val estPanelH = dp(460)
        var startX = (bubbleLp?.x ?: dp(12))
        var startY = (bubbleLp?.y ?: dp(180)) + dp(60)
        startX = startX.coerceIn(dp(4), (dm.widthPixels - estPanelW).coerceAtLeast(dp(4)))
        startY = startY.coerceIn(dp(4), (dm.heightPixels - estPanelH).coerceAtLeast(dp(4)))
        lp.x = startX
        lp.y = startY
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        panelLp = lp

        // سحب اللوحة من المقبض فوق، نفس أسلوب سحب الفقاعة
        val handle = root.findViewById<LinearLayout>(R.id.panelDragHandle)
        var downX = 0f
        var downY = 0f
        var baseX = 0
        var baseY = 0
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    baseX = lp.x
                    baseY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = baseX + (e.rawX - downX).toInt()
                    lp.y = baseY + (e.rawY - downY).toInt()
                    try { wm.updateViewLayout(root, lp) } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(root, lp)
            panel = root
        } catch (_: Exception) {
        }
    }

    private fun removePanel() {
        val v = panel ?: return
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {
        }
        panel = null
        panelLp = null
        panelMasterSwitch = null
    }

    private fun syncPanelMasterState() {
        val root = panel ?: return
        val on = prefs.getBoolean(Prefs.MASTER, true)
        panelMasterSwitch?.isChecked = on
        applyPanelLock(root, on)
    }

    /** يقفل/يفتح إعدادات جيني وبيترا داخل اللوحة حسب حالة التشغيل، حتى ما تتغيّر الأرقام وهو شغّال. */
    private fun applyPanelLock(root: View, hunting: Boolean) {
        val container = root.findViewById<LinearLayout>(R.id.panelAppsContainer) ?: return
        setTreeEnabled(container, !hunting)
        root.findViewById<TextView>(R.id.panelLockNote)?.visibility =
            if (hunting) View.VISIBLE else View.GONE
    }

    private fun setTreeEnabled(v: View, enabled: Boolean) {
        v.isEnabled = enabled
        v.alpha = if (enabled) 1f else 0.4f
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) setTreeEnabled(v.getChildAt(i), enabled)
        }
    }

    private fun panelAddAppCard(themed: ContextThemeWrapper, container: LinearLayout, t: Target) {
        val v = LayoutInflater.from(themed).inflate(R.layout.view_app_card, container, false)
        v.findViewById<TextView>(R.id.tvName).text = t.label
        val swEnabled = v.findViewById<MaterialSwitch>(R.id.swEnabled)
        panelBindSwitch(swEnabled, "${t.key}_enabled", true)
        panelSwitches["${t.key}_enabled"] = swEnabled
        panelBindSwitch(v.findViewById(R.id.swRange), "${t.key}_use_range", false)
        panelBindSwitch(v.findViewById(R.id.swNoPrice), "${t.key}_no_price", false)

        val etMin = v.findViewById<EditText>(R.id.etMin)
        val etMax = v.findViewById<EditText>(R.id.etMax)
        val etMins = v.findViewById<EditText>(R.id.etMinutes)
        val btnSave = v.findViewById<MaterialButton>(R.id.btnSave)

        fun markDirty() { btnSave.text = "حفظ ●" }

        panelInitNumber(etMin, "${t.key}_min", 0f) { markDirty() }
        panelInitNumber(etMax, "${t.key}_max", 50f) { markDirty() }
        panelInitNumber(etMins, "${t.key}_mins", 10f) { markDirty() }

        panelBindStepper(v.findViewById(R.id.btnMinMinus), v.findViewById(R.id.btnMinPlus), etMin, 0.25f)
        panelBindStepper(v.findViewById(R.id.btnMaxMinus), v.findViewById(R.id.btnMaxPlus), etMax, 0.5f)
        panelBindStepper(v.findViewById(R.id.btnMinsMinus), v.findViewById(R.id.btnMinsPlus), etMins, 1f)

        btnSave.setOnClickListener {
            prefs.edit()
                .putFloat("${t.key}_min", panelParse(etMin))
                .putFloat("${t.key}_max", panelParse(etMax))
                .putFloat("${t.key}_mins", panelParse(etMins))
                .apply()
            btnSave.text = "حفظ"
        }
        CollapsibleCard.attach(v, t.key, panelGroup)
        container.addView(v)
    }

    private fun panelBindSwitch(sw: MaterialSwitch, key: String, def: Boolean) {
        sw.isChecked = prefs.getBoolean(key, def)
        sw.setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
    }

    private fun panelFmt(v: Float): String {
        if (v % 1f == 0f) return v.toInt().toString()
        return String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
    }

    private fun panelParse(et: EditText): Float =
        et.text?.toString()?.replace(',', '.')?.toFloatOrNull() ?: 0f

    private fun panelInitNumber(et: EditText, key: String, def: Float, onChange: () -> Unit) {
        et.setText(panelFmt(prefs.getFloat(key, def)))
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { onChange() }
        })
    }

    private fun panelBindStepper(minus: MaterialButton, plus: MaterialButton, et: EditText, step: Float) {
        minus.setOnClickListener {
            val n = Math.round((panelParse(et) - step).coerceAtLeast(0f) * 100f) / 100f
            et.setText(panelFmt(n))
        }
        plus.setOnClickListener {
            val n = Math.round((panelParse(et) + step) * 100f) / 100f
            et.setText(panelFmt(n))
        }
    }

    // ---------------------------------------------------------------- مراقبة انتهاء الاشتراك (مستقلة تماماً عن حلقة الصيد)

    private val licenseWatcher = object : Runnable {
        override fun run() {
            LicenseManager.maybeNotifyExpiring(this@AcceptService)
            handler.postDelayed(this, 30 * 60 * 1000L)
        }
    }

    private fun startLicenseExpiryWatcher() {
        handler.postDelayed(licenseWatcher, 5_000L)
    }
}
