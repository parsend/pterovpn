package dev.c0redev.pteraandroid.update

import android.content.Context

object UpdatePrefs {
    private const val PREFS = "ptera"
    private const val KEY_REMOTE_TAG = "remote_release_tag"

    fun setRemoteReleaseTag(ctx: Context, tag: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_REMOTE_TAG, tag)
            .apply()
    }

    fun getRemoteReleaseTag(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_REMOTE_TAG, null)
}
