package com.clauderelay.app.data

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("relay", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("serverUrl", "") ?: ""
        set(v) = sp.edit().putString("serverUrl", v).apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v).apply()

    var permissionMode: String
        get() = sp.getString("permissionMode", "acceptEdits") ?: "acceptEdits"
        set(v) = sp.edit().putString("permissionMode", v).apply()

    /** Empty = the Mac's default model. */
    var model: String
        get() = sp.getString("model", "") ?: ""
        set(v) = sp.edit().putString("model", v).apply()

    var showTools: Boolean
        get() = sp.getBoolean("showTools", true)
        set(v) = sp.edit().putBoolean("showTools", v).apply()
}
