package com.sayad.orders.pro

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var p: SharedPreferences
    private lateinit var tvStatus: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvResult: TextView
    private lateinit var tvLicense: TextView
    private lateinit var tvDeviceId: TextView
    private lateinit var layoutExpiryWarning: LinearLayout
    private lateinit var tvExpiryWarning: TextView

    private val cardGroup by lazy { CollapsibleGroup(p, "app_open_card") }
    private val switches = HashMap<String, MaterialSwitch>()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        runOnUiThread {
            refreshDiag()
            if (key == Prefs.LICENSE_EXPIRES_AT) refreshLicense()
            if (key != null) {
                switches[key]?.let { sw ->
                    val v = p.getBoolean(key, sw.isChecked)
                    if (sw.isChecked != v) sw.isChecked = v
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        p = Prefs.of(this)
        AppCompatDelegate.setDefaultNightMode(
            if (p.getBoolean(Prefs.DARK, false)) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvCount = findViewById(R.id.tvCount)
        tvResult = findViewById(R.id.tvResult)
        tvLicense = findViewById(R.id.tvLicense)
        tvDeviceId = findViewById(R.id.tvDeviceId)
        layoutExpiryWarning = findViewById(R.id.layoutExpiryWarning)
        tvExpiryWarning = findViewById(R.id.tvExpiryWarning)

        tvDeviceId.text = "الجهاز: " + LicenseManager.deviceName()

        layoutExpiryWarning.setOnClickListener { openWhatsApp() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        findViewById<MaterialButton>(R.id.btnDiagScreen).setOnClickListener {
            val tv = findViewById<TextView>(R.id.tvDiagScreen)
            tv.visibility = if (tv.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE
            refreshDiag()
        }

        findViewById<MaterialButton>(R.id.btnSubscription).setOnClickListener {
            startActivity(Intent(this, SubscriptionActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<MaterialButton>(R.id.btnBattery).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        findViewById<MaterialButton>(R.id.btnTestSound).setOnClickListener {
            if (p.getString(Prefs.SOUND_MODE, "alarm") == "custom") CustomSound.play(this)
            else Alarm.play(this)
        }

        val btnSoundAlarm = findViewById<MaterialButton>(R.id.btnSoundAlarm)
        val btnSoundCustom = findViewById<MaterialButton>(R.id.btnSoundCustom)

        fun refreshSoundButtons() {
            val mode = p.getString(Prefs.SOUND_MODE, "alarm")
            val onColor = ContextCompat.getColor(this, R.color.primary)
            val offColor = ContextCompat.getColor(this, R.color.muted)
            btnSoundAlarm.setTextColor(if (mode == "alarm") onColor else offColor)
            btnSoundCustom.setTextColor(if (mode == "custom") onColor else offColor)
        }
        refreshSoundButtons()

        btnSoundAlarm.setOnClickListener {
            p.edit().putString(Prefs.SOUND_MODE, "alarm").apply()
            refreshSoundButtons()
            Alarm.play(this)
        }
        btnSoundCustom.setOnClickListener {
            p.edit().putString(Prefs.SOUND_MODE, "custom").apply()
            refreshSoundButtons()
            CustomSound.play(this)
        }

        bindSwitch(findViewById(R.id.swMaster), Prefs.MASTER, true)
        bindSwitch(findViewById(R.id.swOverlay), Prefs.OVERLAY, true)
        bindSwitch(findViewById(R.id.swSound), Prefs.SOUND, true)
        bindSwitch(findViewById(R.id.swDark), Prefs.DARK, false) { dark ->
            AppCompatDelegate.setDefaultNightMode(
                if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
        }
        bindSwitch(findViewById(R.id.swExclusive), Prefs.EXCLUSIVE, true)

        val container = findViewById<LinearLayout>(R.id.appsContainer)
        for (t in Target.values()) addAppCard(container, t)
    }

    override fun onResume() {
        super.onResume()
        p.registerOnSharedPreferenceChangeListener(listener)
        refreshStatus()
        refreshDiag()
        refreshLicense()
        LicenseManager.resync(this) { runOnUiThread { refreshLicense() } }
    }

    private fun refreshLicense() {
        val remaining = LicenseManager.remainingMillis(this)
        if (remaining <= 0L) {
            tvLicense.text = "الاشتراك: غير مفعّل 🔒"
            tvLicense.setTextColor(ContextCompat.getColor(this, R.color.warn))
        } else {
            val days = remaining / 86_400_000L
            val hours = (remaining % 86_400_000L) / 3_600_000L
            tvLicense.text = if (days > 0) {
                "الاشتراك: فعّال ✅ متبقي $days يوم و $hours ساعة"
            } else {
                "الاشتراك: فعّال ✅ متبقي $hours ساعة"
            }
            tvLicense.setTextColor(ContextCompat.getColor(this, R.color.ok))
        }

        if (LicenseManager.isExpiringSoon(this)) {
            val days = remaining / 86_400_000L
            val hours = (remaining % 86_400_000L) / 3_600_000L
            val left = if (days > 0) "$days يوم و $hours ساعة" else "$hours ساعة"
            tvExpiryWarning.text = "⏰ اشتراكك قرب يخلص، باقي $left. للتجديد تواصل معنا عبر واتساب: " +
                LicenseManager.RENEWAL_WHATSAPP
            layoutExpiryWarning.visibility = android.view.View.VISIBLE
        } else {
            layoutExpiryWarning.visibility = android.view.View.GONE
        }
    }

    private fun openWhatsApp() {
        val digits = LicenseManager.RENEWAL_WHATSAPP.trimStart('0')
        val intl = "962$digits"
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$intl"))
            )
        } catch (_: Exception) {
            Toast.makeText(this, "تواصل معنا عبر واتساب: ${LicenseManager.RENEWAL_WHATSAPP}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onPause() {
        p.unregisterOnSharedPreferenceChangeListener(listener)
        super.onPause()
    }

    private fun addAppCard(container: LinearLayout, t: Target) {
        val v = layoutInflater.inflate(R.layout.view_app_card, container, false)
        v.findViewById<TextView>(R.id.tvName).text = t.label
        val swEnabled = v.findViewById<MaterialSwitch>(R.id.swEnabled)
        bindSwitch(swEnabled, "${t.key}_enabled", true)
        switches["${t.key}_enabled"] = swEnabled
        bindSwitch(v.findViewById(R.id.swRange), "${t.key}_use_range", false)
        bindSwitch(v.findViewById(R.id.swNoPrice), "${t.key}_no_price", false)
        val etMin = v.findViewById<EditText>(R.id.etMin)
        val etMax = v.findViewById<EditText>(R.id.etMax)
        val etMins = v.findViewById<EditText>(R.id.etMinutes)
        val btnSave = v.findViewById<MaterialButton>(R.id.btnSave)

        fun markDirty() { btnSave.text = "حفظ ●" }

        initNumber(etMin, "${t.key}_min", 0f) { markDirty() }
        initNumber(etMax, "${t.key}_max", 50f) { markDirty() }
        initNumber(etMins, "${t.key}_mins", 10f) { markDirty() }

        bindStepper(v.findViewById(R.id.btnMinMinus), v.findViewById(R.id.btnMinPlus), etMin, 0.25f)
        bindStepper(v.findViewById(R.id.btnMaxMinus), v.findViewById(R.id.btnMaxPlus), etMax, 0.5f)
        bindStepper(v.findViewById(R.id.btnMinsMinus), v.findViewById(R.id.btnMinsPlus), etMins, 1f)

        btnSave.setOnClickListener {
            p.edit()
                .putFloat("${t.key}_min", parse(etMin))
                .putFloat("${t.key}_max", parse(etMax))
                .putFloat("${t.key}_mins", parse(etMins))
                .apply()
            btnSave.text = "حفظ"
            Toast.makeText(this, "تم حفظ إعدادات ${t.label} ✅", Toast.LENGTH_SHORT).show()
        }
        CollapsibleCard.attach(v, t.key, cardGroup)
        container.addView(v)
    }

    private fun bindSwitch(
        sw: MaterialSwitch,
        key: String,
        def: Boolean,
        after: ((Boolean) -> Unit)? = null
    ) {
        sw.isChecked = p.getBoolean(key, def)
        sw.setOnCheckedChangeListener { _, checked ->
            p.edit().putBoolean(key, checked).apply()
            after?.invoke(checked)
        }
    }

    private fun fmt(v: Float): String {
        if (v % 1f == 0f) return v.toInt().toString()
        return String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
    }

    private fun parse(et: EditText): Float =
        et.text?.toString()?.replace(',', '.')?.toFloatOrNull() ?: 0f

    /** يعرض القيمة المحفوظة؛ التعديل ما بينحفظ إلا بزر حفظ. */
    private fun initNumber(et: EditText, key: String, def: Float, onChange: () -> Unit) {
        et.setText(fmt(p.getFloat(key, def)))
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { onChange() }
        })
    }

    private fun bindStepper(minus: MaterialButton, plus: MaterialButton, et: EditText, step: Float) {
        minus.setOnClickListener {
            val n = Math.round((parse(et) - step).coerceAtLeast(0f) * 100f) / 100f
            et.setText(fmt(n))
        }
        plus.setOnClickListener {
            val n = Math.round((parse(et) + step) * 100f) / 100f
            et.setText(fmt(n))
        }
    }

    private fun serviceEnabled(): Boolean {
        val s = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return s.contains("$packageName/${AcceptService::class.java.name}") ||
            s.contains("$packageName/.AcceptService")
    }

    private fun refreshStatus() {
        if (serviceEnabled()) {
            tvStatus.text = "الخدمة: مفعّلة ✅"
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.ok))
        } else {
            tvStatus.text = "الخدمة: غير مفعّلة ⚠️ فعّلها من الزر تحت"
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.warn))
        }
    }

    private fun refreshDiag() {
        tvCount.text = "عدد الطلبات المقبولة: ${p.getInt(Prefs.ACCEPTED, 0)}"
        tvResult.text = "آخر نتيجة: " + (p.getString(Prefs.DIAG_RESULT, null) ?: "—")
        findViewById<TextView>(R.id.tvDiagScreen)?.text =
            p.getString(Prefs.DIAG_SCREEN, null) ?: "—"
    }
}
