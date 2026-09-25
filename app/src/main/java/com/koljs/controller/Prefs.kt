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

    /** 状态文件所在仓库的所有者（云桌面把 AgentDock 连接信息写在这里） */
    var stateOwner: String
        get() = prefs.getString("state_owner", "dtkluo") ?: "dtkluo"
        set(v) { prefs.edit().putString("state_owner", v).apply() }

    /** 状态文件所在仓库名（含 Bearer Token，务必使用私有仓库） */
    var stateRepo: String
        get() = prefs.getString("state_repo", "agentdock-v2") ?: "agentdock-v2"
        set(v) { prefs.edit().putString("state_repo", v).apply() }

    /** 状态文件路径 */
    var statePath: String
        get() = prefs.getString("state_path", "runtime/cloud-desktop.json")
            ?: "runtime/cloud-desktop.json"
        set(v) { prefs.edit().putString("state_path", v).apply() }
}
