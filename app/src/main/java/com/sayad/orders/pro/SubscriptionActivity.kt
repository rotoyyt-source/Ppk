package com.sayad.orders.pro

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

class SubscriptionActivity : AppCompatActivity() {

    private lateinit var p: SharedPreferences
    private lateinit var tvLicense: TextView
    private lateinit var tvDeviceId: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Prefs.of(this)
        setContentView(R.layout.activity_subscription)

        tvLicense = findViewById(R.id.tvLicense)
        tvDeviceId = findViewById(R.id.tvDeviceId)
        tvDeviceId.text = "الجهاز: " + LicenseManager.deviceName()
        findViewById<TextView>(R.id.tvPrice).text = LicenseManager.PRICE_TEXT

        findViewById<MaterialButton>(R.id.btnActivate).setOnClickListener {
            val code = findViewById<EditText>(R.id.etCode).text?.toString().orEmpty()
            activate(code)
        }
        findViewById<LinearLayout>(R.id.btnContactWhatsApp).setOnClickListener { openWhatsApp() }
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        refreshLicense()
        LicenseManager.resync(this) { runOnUiThread { refreshLicense() } }
    }

    private fun activate(rawCode: String) {
        if (rawCode.isBlank()) {
            Toast.makeText(this, "اكتب الكود أول", Toast.LENGTH_SHORT).show()
            return
        }
        val btn = findViewById<MaterialButton>(R.id.btnActivate)
        btn.isEnabled = false
        btn.text = "جاري التحقق..."
        LicenseManager.redeem(this, rawCode) { result ->
            runOnUiThread {
                btn.isEnabled = true
                btn.text = "تفعيل"
                when (result) {
                    is LicenseManager.Result.Success -> {
                        Toast.makeText(this, "تم التفعيل بنجاح ✅", Toast.LENGTH_SHORT).show()
                        refreshLicense()
                    }
                    is LicenseManager.Result.Failure -> {
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
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
    }

    private fun openWhatsApp() {
        val digits = LicenseManager.RENEWAL_WHATSAPP.trimStart('0')
        val intl = "962$digits"
        val text = Uri.encode("مرحباً، بدي أشترك بتطبيق صياد الطلبات (${LicenseManager.PRICE_TEXT})")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$intl?text=$text")))
        } catch (_: Exception) {
            Toast.makeText(this, "تواصل معنا عبر واتساب: ${LicenseManager.RENEWAL_WHATSAPP}", Toast.LENGTH_LONG).show()
        }
    }
}
