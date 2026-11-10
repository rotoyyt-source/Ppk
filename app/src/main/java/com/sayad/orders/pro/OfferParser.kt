package com.sayad.orders.pro

/** قراءة السعر والوقت ونص زر القبول من نصوص الشاشة. */
object OfferParser {
    private val acceptTexts = listOf(
        "قبول العرض", "اقبل العرض", "قبول الطلب", "اقبل الطلب", "قبول الرحلة",
        "قبول", "اقبل",
        "accept offer", "accept request", "accept ride", "accept"
    )
    private val notAccept = listOf("accepted", "تم قبول", "تم القبول")
    private val currency = listOf("jod", "jd", "دينار", "د.أ", "د.ا")
    private val numberRegex = Regex("\\d+(?:\\.\\d+)?")
    private val intRegex = Regex("\\d+")

    /** تحويل الأرقام العربية للاتينية والفاصلة لنقطة. */
    fun normalize(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            when (ch) {
                in '\u0660'..'\u0669' -> sb.append('0' + (ch - '\u0660'))
                in '\u06F0'..'\u06F9' -> sb.append('0' + (ch - '\u06F0'))
                '\u066B', ',' -> sb.append('.')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun decimal(text: String): Double? =
        numberRegex.find(normalize(text))?.value?.toDoubleOrNull()

    fun integer(text: String): Int? =
        intRegex.find(normalize(text))?.value?.toIntOrNull()

    fun isAccept(text: String): Boolean {
        val s = text.trim().lowercase()
        if (s.isEmpty() || s.length > 30) return false
        if (notAccept.any { s.contains(it) }) return false
        return acceptTexts.any { s.contains(it) }
    }

    fun hasCurrency(text: String): Boolean {
        val s = text.lowercase()
        return currency.any { s.contains(it) }
    }

    fun findPrice(texts: List<String>): Double? {
        for (t in texts) {
            if (t.length <= 40 && hasCurrency(t)) {
                val v = decimal(t)
                if (v != null) return v
            }
        }
        return null
    }

    private fun isMinutesText(t: String): Boolean {
        val l = t.lowercase()
        return l.contains("min") || l.contains("دقيق") || l.contains("دقائق")
    }

    fun findMinutes(texts: List<String>): Int? {
        val cand = texts.filter { it.length <= 40 && isMinutesText(it) && integer(it) != null }
        val away = cand.firstOrNull {
            val l = it.lowercase()
            l.contains("away") || l.contains("يبعد") || l.contains("بعيد")
        }
        val pick = away ?: cand.firstOrNull() ?: return null
        return integer(pick)
    }
}
