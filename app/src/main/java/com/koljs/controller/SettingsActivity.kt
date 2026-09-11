package com.koljs.controller

import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var etToken: EditText
    private lateinit var etOwner: EditText
    private lateinit var etRepo: EditText
    private lateinit var etWorkflow: EditText
    private lateinit var etRdpPassword: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = Prefs(this)
        etToken = findViewById(R.id.etToken)
        etOwner = findViewById(R.id.etOwner)
        etRepo = findViewById(R.id.etRepo)
        etWorkflow = findViewById(R.id.etWorkflow)
        etRdpPassword = findViewById(R.id.etRdpPassword)

        etToken.setText(prefs.token)
        etOwner.setText(prefs.owner)
        etRepo.setText(prefs.repo)
        etWorkflow.setText(prefs.workflowFile)
        etRdpPassword.setText(prefs.rdpPassword)

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            prefs.token = etToken.text.toString().trim()
            prefs.owner = etOwner.text.toString().trim().ifBlank { "dtkluo" }
            prefs.repo = etRepo.text.toString().trim().ifBlank { "my-cloud-desktop" }
            prefs.workflowFile = etWorkflow.text.toString().trim().ifBlank { "windows-rdp-easytier.yml" }
            prefs.rdpPassword = etRdpPassword.text.toString()
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<MaterialButton>(R.id.btnTest).setOnClickListener {
            val api = GitHubApi(
                etToken.text.toString().trim(),
                etOwner.text.toString().trim().ifBlank { "dtkluo" },
                etRepo.text.toString().trim().ifBlank { "my-cloud-desktop" },
                etWorkflow.text.toString().trim().ifBlank { "windows-rdp-easytier.yml" }
            )
            Toast.makeText(this, "测试中…", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                try {
                    val name = api.testConnection()
                    runOnUiThread { Toast.makeText(this@SettingsActivity, "连接成功：$name", Toast.LENGTH_LONG).show() }
                } catch (e: Exception) {
                    runOnUiThread { Toast.makeText(this@SettingsActivity, "测试失败：${e.message}", Toast.LENGTH_LONG).show() }
                }
            }
        }
    }
}
