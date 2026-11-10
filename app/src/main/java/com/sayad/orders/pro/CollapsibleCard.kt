package com.sayad.orders.pro

import android.content.SharedPreferences
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * مجموعة كروت "أكورديون": فتح كرت (جيني مثلاً) بيسكّر الكرت الثاني تلقائياً، والضغط على الكرت المفتوح بيسكّره.
 * الافتراضي: كلهم مسكّرين. الحالة بتنحفظ بمفتاح مستقل لكل مكان (داخل التطبيق / اللوحة العائمة).
 */
class CollapsibleGroup(private val prefs: SharedPreferences, private val stateKey: String) {
    private val renderers = LinkedHashMap<String, (Boolean, Boolean) -> Unit>()
    private var open: String = prefs.getString(stateKey, "") ?: ""

    fun register(id: String, render: (isOpen: Boolean, animate: Boolean) -> Unit) {
        renderers[id] = render
        render(open == id, false)
    }

    fun toggle(id: String) {
        open = if (open == id) "" else id
        prefs.edit().putString(stateKey, open).apply()
        for ((k, r) in renderers) r(k == open, true)
    }
}

/** يحوّل كرت إعدادات التطبيق لكرت يُفتح ويُسكّر بالضغط على رأسه (الاسم)، مع حركة ناعمة. */
object CollapsibleCard {

    fun attach(card: View, id: String, group: CollapsibleGroup) {
        val inner = (card as? ViewGroup)?.getChildAt(0) as? LinearLayout ?: return
        if (inner.childCount < 2) return
        val header = inner.getChildAt(0) as? LinearLayout ?: return
        val ctx = card.context
        val density = ctx.resources.displayMetrics.density

        // كل شي تحت الرأس ننقله لحاوية وحدة نخفيها/نظهرها.
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val moved = ArrayList<View>()
        for (i in 1 until inner.childCount) moved.add(inner.getChildAt(i))
        for (v in moved) {
            inner.removeView(v)
            body.addView(v)
        }
        inner.addView(
            body,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // منطقة ضغط أكبر للرأس (الضغط على اسم التطبيق نفسه بيفتح/يسكّر تلقائياً، بدون أي سهم يدوي)
        val vPad = (6 * density).toInt()
        header.setPadding(header.paddingLeft, vPad, header.paddingRight, vPad)

        group.register(id) { isOpen, animate ->
            if (animate) {
                val root = (card.parent as? ViewGroup) ?: inner
                TransitionManager.beginDelayedTransition(root, AutoTransition().setDuration(180))
            }
            body.visibility = if (isOpen) View.VISIBLE else View.GONE
        }
        header.setOnClickListener { group.toggle(id) }
    }
}
