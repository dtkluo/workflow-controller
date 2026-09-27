package com.koljs.controller

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

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
    private lateinit var tvLinkTitle: TextView
    private lateinit var tvLinkInfo: TextView
    private lateinit var tvLinkState: TextView
    private lateinit var tvLinkMcp: TextView
    private lateinit var tvLinkToken: TextView
    private lateinit var btnCopyPrompt: MaterialButton
    private lateinit var btnRefreshLink: MaterialButton

    /** 云桌面同步过来的 AgentDock 连接信息（含提示词） */
    private var agentDockLink: AgentDockLink? = null
    private var lastLinkFetchAt = 0L

    /**
     * 防重入标志。请求内部会做退避重试，单次耗时可能超过 30 秒轮询间隔，
     * 不加该标志会导致请求层层堆积、越积越多。
     */
    private var refreshing = false

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
        tvLinkTitle = findViewById(R.id.tvLinkTitle)
        tvLinkInfo = findViewById(R.id.tvLinkInfo)
        tvLinkState = findViewById(R.id.tvLinkState)
        tvLinkMcp = findViewById(R.id.tvLinkMcp)
        tvLinkToken = findViewById(R.id.tvLinkToken)
        btnCopyPrompt = findViewById(R.id.btnCopyPrompt)
        btnRefreshLink = findViewById(R.id.btnRefreshLink)
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
        btnRefreshLink.setOnClickListener { refreshLink(force = true) }
        btnCopyPrompt.setOnClickListener {
            val prompt = agentDockLink?.prompt
            if (prompt.isNullOrBlank()) {
                Toast.makeText(this, "暂无可用提示词：请先启动云桌面，待 AgentDock 就绪后自动同步", Toast.LENGTH_LONG).show()
            } else {
                copyToClipboard("AI 助手连接提示词", prompt)
            }
        }
        tvLinkMcp.setOnClickListener {
            val url = agentDockLink?.publicMcpUrl
            if (url.isNullOrBlank()) Toast.makeText(this, "MCP 地址尚未就绪", Toast.LENGTH_SHORT).show()
            else copyToClipboard("MCP 地址", url)
        }
        tvLinkToken.setOnClickListener {
            val token = agentDockLink?.bearerToken
            if (token.isNullOrBlank()) Toast.makeText(this, "Bearer Token 尚未就绪", Toast.LENGTH_SHORT).show()
            else copyToClipboard("Bearer Token", token)
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
        if (refreshing) return
        val api = apiNow() ?: run {
            tvStatus.text = "未配置 Token，请点击下方设置"
            return
        }
        refreshing = true
        lifecycleScope.launch {
            try {
                try {
                    currentRun = api.latestRun()
                } catch (e: Exception) {
                    tvStatus.text = "获取状态失败：${e.message}"
                    return@launch
                }
                updateUi()
                // 状态行依赖 currentRun，必须在此同步刷新：refreshLink 有 2 分钟节流，
                // 若只靠它回调，用户点「启动云桌面」后状态行会一直停在旧值。
                updateLinkUi()
                refreshLink(force = false)
            } finally {
                refreshing = false
            }
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
                Toast.makeText(
                    this@MainActivity,
                    "启动失败：${e.message}\n若提示网络超时，请稍后下拉刷新确认是否已触发（避免重复启动）",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                btnStart.text = "启动云桌面"
                // 即便上面因超时抛错，请求也可能实际已送达，刷新一次以确认真实状态
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

    // ---------- AgentDock 连接提示词 ----------

    /**
     * 读取状态文件。宿主轮询间隔为 30 秒，这里按 2 分钟节流，
     * 避免对 GitHub API 造成不必要的调用。
     */
    private fun refreshLink(force: Boolean) {
        if (prefs.token.isBlank()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastLinkFetchAt < LINK_FETCH_INTERVAL_MS) return
        val api = apiNow() ?: return
        lastLinkFetchAt = now
        lifecycleScope.launch {
            try {
                agentDockLink = api.fetchAgentDockLink(prefs.stateOwner, prefs.stateRepo, prefs.statePath)
            } catch (e: Exception) {
                if (force) {
                    Toast.makeText(this@MainActivity, "读取连接信息失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            updateLinkUi()
        }
    }

    private fun updateLinkUi() {
        val link = agentDockLink
        if (link == null) {
            val run = currentRun
            val pending = run != null && run.status in PENDING_STATUSES
            val running = run != null && run.status == "in_progress"
            tvLinkTitle.text = "AI 助手连接提示词"
            tvLinkState.text = when {
                pending -> "● 正在分配虚拟机…"
                running -> "● 云桌面已启动，等待同步连接信息…"
                else -> ""
            }
            if (pending || running) {
                tvLinkState.setTextColor(ContextCompat.getColor(this, R.color.status_info))
            }
            tvLinkInfo.text = "尚未同步：启动云桌面后会自动安装 AgentDock 并写入连接信息"
            tvLinkMcp.text = "MCP 地址：—"
            tvLinkToken.text = "Bearer Token：—"
            btnCopyPrompt.isEnabled = false
            return
        }

        val state = resolveLinkState(link)
        tvLinkTitle.text = "AI 助手连接提示词（可一键复制）"
        tvLinkState.text = stateHeadline(state, link)
        tvLinkState.setTextColor(ContextCompat.getColor(this, stateColor(state)))
        tvLinkInfo.text = stateDetail(state, link)
        tvLinkMcp.text = "MCP 地址：" + (link.publicMcpUrl ?: "未就绪")
        tvLinkToken.text = "Bearer Token：" + maskSecret(link.bearerToken)
        btnCopyPrompt.isEnabled = !link.prompt.isNullOrBlank()
    }

    /**
     * 云桌面 / AgentDock 的对外可见状态。
     *
     * 本机是「随登录型」形态：云桌面开机后 AgentDock 只是写好参数、注册了「登录即装」的
     * 计划任务，真正启动要等 rdpadmin 完成一次交互式 RDP 登录 —— 这个阶段在状态文件里
     * 是 `health = pending-logon`。不把它显式展示出来，用户只会看到「核心未运行」，
     * 既不知道原因也不知道该做什么。
     */
    private enum class LinkState { QUEUED, PENDING_LOGON, READY, FAILED, STALE, UNKNOWN }

    /**
     * 归一状态。
     *
     * **必须先确认当前会话真的在跑**：状态文件是上次写入时的快照，云桌面关机后它不会
     * 自动清空，直接采信会出现「手机显示已就绪、机器其实早就关了」这种最误导人的情形。
     */
    private fun resolveLinkState(link: AgentDockLink): LinkState {
        val run = currentRun ?: return LinkState.STALE
        return when {
            run.status in PENDING_STATUSES -> LinkState.QUEUED
            run.status == "in_progress" -> when {
                link.health == "healthy" -> LinkState.READY
                link.health == "pending-logon" -> LinkState.PENDING_LOGON
                !link.installed -> LinkState.FAILED
                else -> LinkState.UNKNOWN
            }
            else -> LinkState.STALE
        }
    }

    /** 首行大字：现在到底能不能连，或者下一步该做什么 */
    private fun stateHeadline(state: LinkState, link: AgentDockLink): String = when (state) {
        LinkState.READY -> "● 已就绪，可以直接连接 AI 助手"
        LinkState.PENDING_LOGON -> "● 待登录：请 RDP 连接 ${link.rdpUser ?: "rdpadmin"} 一次"
        LinkState.QUEUED -> "● 正在分配虚拟机…"
        LinkState.FAILED -> "● 安装异常，AgentDock 未启动"
        LinkState.STALE -> "● 云桌面未在运行"
        LinkState.UNKNOWN -> "● 状态未就绪"
    }

    /** 次行小字：原因与后续动作 */
    private fun stateDetail(state: LinkState, link: AgentDockLink): String = when (state) {
        LinkState.READY ->
            "同步于 ${formatUpdatedAt(link.updatedAt)}｜版本 ${link.version ?: "—"}" +
                "｜隧道 ${link.tunnelMode ?: "—"}"
        LinkState.PENDING_LOGON ->
            "AgentDock 已在云端就位，只差完成一次登录。RDP 连上后约 1 分钟自动启动，" +
                "此后可立即断开，会话会保留。"
        LinkState.QUEUED ->
            "虚拟机尚未分配（约 3-5 分钟）。分配后还需 RDP 登录一次。"
        LinkState.FAILED ->
            "错误码 ${link.errorCode ?: "—"}：${link.message ?: "未知原因"}"
        LinkState.STALE ->
            "以下为上次会话的残留记录（同步于 ${formatUpdatedAt(link.updatedAt)}）。" +
                "点「启动云桌面」开始新会话。"
        LinkState.UNKNOWN ->
            "同步于 ${formatUpdatedAt(link.updatedAt)}｜health=${link.health ?: "—"}"
    }

    /**
     * 状态色资源 id：绿=可用、橙=需要你动手、蓝=进行中、红=故障、灰=无会话。
     *
     * 色值分浅色 / 深色两套（`values/colors.xml` 与 `values-night/colors.xml`）——
     * 主题是 `Theme.Material3.DayNight.NoActionBar` 会跟随系统深色模式，
     * 深色值压在深底上对比度不足，必须换成高亮度变体。
     */
    @ColorRes
    private fun stateColor(state: LinkState): Int = when (state) {
        LinkState.READY -> R.color.status_ok
        LinkState.PENDING_LOGON -> R.color.status_action
        LinkState.QUEUED -> R.color.status_info
        LinkState.FAILED -> R.color.status_bad
        LinkState.STALE, LinkState.UNKNOWN -> R.color.status_muted
    }

    private fun formatUpdatedAt(iso: String?): String {
        if (iso.isNullOrBlank()) return "—"
        return try {
            val slice = if (iso.length >= 19) iso.substring(0, 19) else iso
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val parsed = parser.parse(slice) ?: return iso
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(parsed)
        } catch (e: Exception) {
            iso
        }
    }

    private fun maskSecret(value: String?): String {
        if (value.isNullOrBlank()) return "—"
        if (value.length <= 10) return "••••••（点击复制）"
        return value.take(6) + "……" + value.takeLast(4) + "（点击复制）"
    }

    companion object {
        private const val LINK_FETCH_INTERVAL_MS = 120_000L

        /** workflow_dispatch 后、VM 真正跑起来之前的过渡态（GitHub 会先后给出其中若干个） */
        private val PENDING_STATUSES = setOf("requested", "waiting", "pending", "queued")
    }

    // ---------- 剪贴板 ----------

    private fun copyToClipboard(label: String, value: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "$label 已复制", Toast.LENGTH_SHORT).show()
    }
}
