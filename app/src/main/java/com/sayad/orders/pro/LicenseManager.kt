package com.sayad.orders.pro

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.util.UUID

/**
 * نظام التفعيل بالأكواد.
 * كل كود = مستند بمجموعة "codes" على Firestore، مربوط بجهاز واحد فقط (device id).
 * بعد التفعيل، التطبيق بيحفظ تاريخ الانتهاء محلياً ويتحقق منه بدون نت (مسار سريع)،
 * ويعيد المزامنة مع Firestore بس وقت فتح الشاشة الرئيسية (عشان لو انحظر الكود من بعيد).
 */
object LicenseManager {

    sealed class Result {
        data class Success(val expiresAt: Long) : Result()
        data class Failure(val message: String) : Result()
    }

    private fun db() = FirebaseFirestore.getInstance()

    /** معرّف ثابت للجهاز: ANDROID_ID، وإذا ما توفر منطقياً، UUID محفوظ محلياً بشكل دائم. */
    fun deviceId(ctx: Context): String {
        val p = Prefs.of(ctx)
        p.getString(Prefs.DEVICE_ID, null)?.let { return it }

        val androidId = try {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Exception) {
            null
        }
        val id = if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
            androidId
        } else {
            UUID.randomUUID().toString()
        }
        p.edit().putString(Prefs.DEVICE_ID, id).apply()
        return id
    }

    /** اسم مقروء للجهاز (الشركة + الموديل)، يُعرض للمستخدم بدل معرّف الجهاز التقني. */
    fun deviceName(): String {
        val manu = Build.MANUFACTURER?.replaceFirstChar { it.uppercase() } ?: ""
        val model = Build.MODEL ?: ""
        val name = "$manu $model".trim()
        return name.ifBlank { "جهاز غير معروف" }
    }

    /** نسخة سريعة محلية، بدون نت: هل الاشتراك فعال الآن؟ */
    fun isActive(ctx: Context): Boolean = remainingMillis(ctx) > 0

    /** الوقت المتبقي بالميلي ثانية؛ صفر أو أقل يعني غير مفعّل أو انتهى. */
    fun remainingMillis(ctx: Context): Long {
        val exp = Prefs.of(ctx).getLong(Prefs.LICENSE_EXPIRES_AT, 0L)
        if (exp <= 0L) return 0L
        return exp - System.currentTimeMillis()
    }

    fun savedCode(ctx: Context): String? = Prefs.of(ctx).getString(Prefs.LICENSE_CODE, null)

    private fun normalize(raw: String): String =
        raw.trim().uppercase().replace(" ", "")

    /**
     * تفعيل كود جديد (أو استرجاع نفس الكود على نفس الجهاز بعد إعادة تثبيت).
     * بيشتغل بمعاملة (transaction) عشان ما يصير فيها استخدام الكود مرتين بنفس اللحظة.
     */
    fun redeem(ctx: Context, rawCode: String, onResult: (Result) -> Unit) {
        val code = normalize(rawCode)
        if (code.isEmpty()) {
            onResult(Result.Failure("اكتب الكود أول"))
            return
        }
        val dev = deviceId(ctx)
        val ref = db().collection("codes").document(code)

        db().runTransaction { tx ->
            val snap = tx.get(ref)
            if (!snap.exists()) {
                throw CodeError("الكود غير صحيح")
            }
            val revoked = snap.getBoolean("revoked") ?: false
            if (revoked) throw CodeError("تم إلغاء هذا الكود")

            val used = snap.getBoolean("used") ?: false
            val savedDevice = snap.getString("deviceId")

            if (used) {
                if (savedDevice == dev) {
                    return@runTransaction snap.getLong("expiresAt") ?: 0L
                } else {
                    throw CodeError("هذا الكود مستخدم على جهاز آخر")
                }
            }

            val days = snap.getLong("days") ?: 0L
            if (days <= 0L) throw CodeError("الكود غير صالح")

            val now = System.currentTimeMillis()
            val expiresAt = now + days * 86_400_000L

            tx.update(
                ref,
                mapOf(
                    "used" to true,
                    "deviceId" to dev,
                    "deviceName" to deviceName(),
                    "activatedAt" to now,
                    "expiresAt" to expiresAt
                )
            )
            expiresAt
        }.addOnSuccessListener { expiresAt ->
            Prefs.of(ctx).edit()
                .putLong(Prefs.LICENSE_EXPIRES_AT, expiresAt)
                .putString(Prefs.LICENSE_CODE, code)
                .apply()
            onResult(Result.Success(expiresAt))
        }.addOnFailureListener { e ->
            val msg = (e as? CodeError)?.message ?: (e.message ?: "تعذر الاتصال، تأكد من الإنترنت")
            onResult(Result.Failure(msg))
        }
    }

    /**
     * مزامنة مع Firestore (تُستدعى وقت فتح الشاشة الرئيسية فقط، وليست بالمسار السريع).
     * بتحدّث الوقت المتبقي محلياً، وبتوقف الاشتراك فوراً لو تم إلغاؤه (revoked) من عندك.
     */
    fun resync(ctx: Context, onDone: (() -> Unit)? = null) {
        val code = savedCode(ctx) ?: run { onDone?.invoke(); return }
        db().collection("codes").document(code).get()
            .addOnSuccessListener { snap ->
                if (!snap.exists()) {
                    onDone?.invoke()
                    return@addOnSuccessListener
                }
                val revoked = snap.getBoolean("revoked") ?: false
                if (revoked) {
                    Prefs.of(ctx).edit().putLong(Prefs.LICENSE_EXPIRES_AT, 0L).apply()
                } else {
                    val exp = snap.getLong("expiresAt")
                    if (exp != null) {
                        Prefs.of(ctx).edit().putLong(Prefs.LICENSE_EXPIRES_AT, exp).apply()
                    }
                }
                onDone?.invoke()
            }
            .addOnFailureListener { onDone?.invoke() }
    }

    /**
     * تبليغ خفيف (بدون انتظار نتيجة) بكل طلب تم قبوله، عشان يظهر لك بلوحة الإدارة
     * جنب الكود نفسه. لا يوقف ولا يبطّئ الصيد إذا فشل أو ما في إنترنت لحظتها.
     */
    fun reportAccepted(ctx: Context, targetLabel: String, price: Double?) {
        val code = savedCode(ctx) ?: return
        val info = if (price != null) "$targetLabel · $price" else targetLabel
        try {
            db().collection("codes").document(code).update(
                mapOf(
                    "acceptedCount" to FieldValue.increment(1),
                    "lastAcceptedAt" to System.currentTimeMillis(),
                    "lastAcceptedInfo" to info
                )
            )
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- تنبيه قرب انتهاء الاشتراك

    const val RENEWAL_WHATSAPP = "0797023509"
    const val PRICE_TEXT = "الاشتراك الشهري بـ 3.5 دينار" // غيّرها إذا اختلف السعر
    private const val EXPIRY_WARN_WINDOW_MS = 2 * 24 * 60 * 60 * 1000L // يومين قبل الانتهاء
    private const val NOTIFY_COOLDOWN_MS = 12 * 60 * 60 * 1000L // ما يكرر الإشعار إلا كل 12 ساعة
    private const val CHANNEL_ID = "sniper_license_expiry"

    /** هل الاشتراك داخل نافذة "قرب يخلص"؟ تُستخدم لإظهار شريط التنبيه داخل التطبيق. */
    fun isExpiringSoon(ctx: Context): Boolean {
        val remaining = remainingMillis(ctx)
        return remaining in 1..EXPIRY_WARN_WINDOW_MS
    }

    /** يرسل إشعار نظام تذكير بالتجديد، بحد أقصى مرة كل 12 ساعة. لا يوقف ولا يبطّئ أي شيء. */
    fun maybeNotifyExpiring(ctx: Context) {
        val remaining = remainingMillis(ctx)
        if (remaining <= 0L || remaining > EXPIRY_WARN_WINDOW_MS) return

        val p = Prefs.of(ctx)
        val now = System.currentTimeMillis()
        val lastNotified = p.getLong(Prefs.LICENSE_LAST_NOTIFIED, 0L)
        if (now - lastNotified < NOTIFY_COOLDOWN_MS) return
        p.edit().putLong(Prefs.LICENSE_LAST_NOTIFIED, now).apply()

        val hours = (remaining / 3_600_000L).coerceAtLeast(1L)
        val body = "اشتراكك بصياد الطلبات قرب يخلص (باقي تقريباً $hours ساعة). " +
            "للتجديد تواصل معنا عبر واتساب: $RENEWAL_WHATSAPP"

        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "تنبيه الاشتراك", NotificationManager.IMPORTANCE_HIGH
                )
                nm.createNotificationChannel(channel)
            }
            val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("اشتراكك قرب يخلص ⏰")
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(ctx).notify(2025, notification)
        } catch (_: Exception) {
            // ما في إذن إشعارات أو أي مشكلة نظام؛ نتجاهل بهدوء، التطبيق ما يتأثر
        }
    }

    private class CodeError(message: String) : Exception(message)
}
