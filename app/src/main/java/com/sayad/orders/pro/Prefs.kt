package com.sayad.orders.pro

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val MASTER = "master_on"
    const val OVERLAY = "overlay_on"
    const val SOUND = "sound_on"
    const val DARK = "dark_mode"
    const val ACCEPTED = "accepted_count"
    const val DIAG_SEEN = "diag_seen"
    const val DIAG_SCREEN = "diag_screen"
    const val DIAG_RESULT = "diag_result"
    const val DIAG_EVENTS = "diag_events"

    // نظام الترخيص (Sniper Orders Pro)
    const val DEVICE_ID = "device_id"
    const val LICENSE_CODE = "license_code"
    const val LICENSE_EXPIRES_AT = "license_expires_at"
    const val LICENSE_LAST_NOTIFIED = "license_last_notified"
    const val SOUND_MODE = "sound_mode" // "alarm" أو "custom"
    const val EXCLUSIVE = "exclusive_mode" // إيقاف تطبيق جيني/بترا الآخر بعد قبول طلب

    fun of(c: Context): SharedPreferences =
        c.getSharedPreferences("sayad_prefs", Context.MODE_PRIVATE)
}

data class Filter(
    val enabled: Boolean,
    val minPrice: Double,
    val useRange: Boolean,
    val maxPrice: Double,
    val maxMinutes: Int,
    val acceptNoPrice: Boolean
)

enum class Target(val key: String, val label: String, val packages: Set<String>) {
    JEENY("jeeny", "Jeeny", setOf("me.com.easytaxista", "com.jeenyassistant")),
    PETRA("petra", "Petra Ride", setOf("com.PetraRide_Captain"));

    fun enabled(p: SharedPreferences): Boolean = p.getBoolean("${key}_enabled", true)

    fun filter(p: SharedPreferences) = Filter(
        enabled = enabled(p),
        minPrice = p.getFloat("${key}_min", 0f).toDouble(),
        useRange = p.getBoolean("${key}_use_range", false),
        maxPrice = p.getFloat("${key}_max", 50f).toDouble(),
        maxMinutes = p.getFloat("${key}_mins", 10f).toInt(),
        acceptNoPrice = p.getBoolean("${key}_no_price", false)
    )

    companion object {
        fun forPackage(pkg: String?): Target? {
            if (pkg == null) return null
            return values().firstOrNull { pkg in it.packages }
        }
    }
}
