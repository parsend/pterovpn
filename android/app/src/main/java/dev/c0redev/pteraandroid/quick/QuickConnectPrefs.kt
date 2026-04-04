package dev.c0redev.pteraandroid.quick

import android.content.Context

object QuickConnectPrefs {
    private const val PREF = "quick_connect"
    private fun key(slot: Int) = "slot_$slot"

    fun getSlotName(context: Context, slot: Int): String? {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(key(slot), null)
        return raw?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun setSlotName(context: Context, slot: Int, name: String?) {
        val v = name?.trim()?.takeIf { it.isNotEmpty() }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().apply {
            if (v == null) remove(key(slot)) else putString(key(slot), v)
        }.apply()
    }
}
