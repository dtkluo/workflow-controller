package com.koljs.controller

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 本地加密存储：Token 与设置项
 */
class Prefs(context: Context) {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "secure_prefs",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) { prefs.edit().putString("token", v).apply() }

    var owner: String
        get() = prefs.getString("owner", "dtkluo") ?: "dtkluo"
        set(v) { prefs.edit().putString("owner", v).apply() }

    var repo: String
        get() = prefs.getString("repo", "my-cloud-desktop") ?: "my-cloud-desktop"
        set(v) { prefs.edit().putString("repo", v).apply() }

    var workflowFile: String
        get() = prefs.getString("workflow_file", "windows-rdp-easytier.yml") ?: "windows-rdp-easytier.yml"
        set(v) { prefs.edit().putString("workflow_file", v).apply() }

    var rdpPassword: String
        get() = prefs.getString("rdp_password", "") ?: ""
        set(v) { prefs.edit().putString("rdp_password", v).apply() }

    var lastHours: Int
        get() = prefs.getInt("last_hours", 6)
        set(v) { prefs.edit().putInt("last_hours", v).apply() }
}
