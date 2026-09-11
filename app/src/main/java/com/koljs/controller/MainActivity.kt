package com.koljs.controller

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private var currentRun: RunInfo? = null
    private var pollJob: Job? = null

    private lateinit var tvStatus: TextView
    private lateinit var tvElapsed: TextView
    private lateinit var tvRemaining: TextView
    private lateinit var tvIp: TextView
    private lateinit var tvUser: TextView
    private lateinit var tvPwd: TextView
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnRefresh: MaterialButton
    private lateinit var btnSettings: MaterialButton
    private lateinit var chipGroup: ChipGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        bindViews()
        setupClicks()
    }

    override fun onResume() {
        super.onResume()
        if (prefs.token.isBlank()) {
            Toast.makeText(this, "请先在设置中填写 GitHub Token", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
        } else {
            startPolling()
        }
    }

    override fun onPause() {
        super.onPause()
        pollJob?.cancel()
    }

    private fun bindViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvElapsed = findViewById(R.id.tvElapsed)
        tvRemaining = findViewById(R.id.tvRemaining)
        tvIp = findViewById(R.id.tvIp)
        tvUser = findViewById(R.id.tvUser)
        tvPwd = findViewById(R.id.tvPwd)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnSettings = findViewById(R.id.btnSettings)
        chipGroup = findViewById(R.id.chipGroup)
    }

    private fun setupClicks() {
        btnStart.setOnClickListener { startSession() }
        btnStop.setOnClickListener { stopSession() }
        btnRefresh.setOnClickListener { refreshOnce() }
        btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        tvIp.setOnClickListener { copyToClipboard("云桌面地址", tvIp.text.toString()) }
        tvUser.setOnClickListener { copyToClipboard("用户名", tvUser.text.toString()) }
        tvPwd.setOnClickListener {
            if (prefs.rdpPassword.isNotBlank()) copyToClipboard("密码", prefs.rdpPassword)
            else Toast.makeText(this, "未设置密码（可在设置页填写，或查看 GitHub Secrets）", Toast.LENGTH_SHORT).show()
        }
    }

    private fun apiNow(): GitHubApi? =
        if (prefs.token.isBlank()) null
        else GitHubApi(prefs.token, prefs.owner, prefs.repo, prefs.workflowFile)

    private fun selectedHours(): Int = when (chipGroup.checkedChipId) {
        R.id.chip1 -> 1
        R.id.chip2 -> 2
        R.id.chip3 -> 3
        R.id.chip4 -> 4
        R.id.chip5 -> 5
        else -> 6
    }

    // ---------- 轮询 ----------

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                refreshOnce()
                delay(30_000)
            }
        }
    }

    private fun refreshOnce() {
        val api = apiNow() ?: run {
            tvStatus.text = "未配置 Token，请点击下方设置"
            return
        }
        lifecycleScope.launch {
            try {
                currentRun = api.latestRun()
            } catch (e: Exception) {
                tvStatus.text = "获取状态失败：${e.message}"
                return@launch
            }
            updateUi()
        }
    }

    // ---------- 启停操作 ----------

    private fun startSession() {
        val api = apiNow() ?: run {
            Toast.makeText(this, "请先在设置中配置 Token", Toast.LENGTH_SHORT).show(); return
        }
        val hours = selectedHours()
        btnStart.isEnabled = false
        btnStart.text = "启动中…"
        lifecycleScope.launch {
            try {
                api.dispatchWorkflow(hours)
                prefs.lastHours = hours
                Toast.makeText(this@MainActivity, "已触发启动（$hours 小时），约 3-5 分钟后可连接", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "启动失败：${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                btnStart.text = "启动云桌面"
                refreshOnce()
            }
        }
    }

    private fun stopSession() {
        val api = apiNow() ?: return
        val run = currentRun ?: return
        btnStop.isEnabled = false
        btnStop.text = "停止中…"
        lifecycleScope.launch {
            try {
                api.cancelRun(run.id)
                Toast.makeText(this@MainActivity, "已发送停止命令，虚拟机即将回收", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "停止失败：${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                btnStop.text = "停止云桌面"
                refreshOnce()
            }
        }
    }

    // ---------- 界面刷新 ----------

    private fun updateUi() {
        val run = currentRun
        val running = run != null && (run.status == "queued" || run.status == "in_progress")
        btnStart.isEnabled = !running
        btnStop.isEnabled = running
        tvStatus.text = statusText(run)

        if (run == null) {
            tvElapsed.text = "—"
            tvRemaining.text = "—"
        } else if (running) {
            val elapsedSec = (System.currentTimeMillis() - run.createdAt.time) / 1000
            tvElapsed.text = "已运行：" + TimeEstimator.format(elapsedSec)
            tvRemaining.text = "预计剩余：" + TimeEstimator.format(
                TimeEstimator.remainingSeconds(run.createdAt.time, prefs.lastHours)
            )
        } else {
            tvElapsed.text = "—"
            tvRemaining.text = "点击「启动云桌面」开启新会话"
        }
    }

    private fun statusText(run: RunInfo?): String = when {
        run == null -> "空闲：尚未运行过"
        run.status == "queued" -> "排队中（等待分配虚拟机）"
        run.status == "in_progress" -> "运行中"
        run.conclusion == "success" -> "上次会话：正常结束"
        run.conclusion == "failure" -> "上次会话：失败（可到 GitHub 查看日志）"
        run.conclusion == "cancelled" -> "上次会话：已手动停止"
        else -> "上次会话：${run.conclusion ?: run.status}"
    }

    // ---------- 剪贴板 ----------

    private fun copyToClipboard(label: String, value: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "$label 已复制", Toast.LENGTH_SHORT).show()
    }
}
