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
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private var currentRun: RunInfo? = null

    /** 30 秒轮询协程 */
    private var pollJob: Job? = null

    /**
     * 正在飞行中的 [refreshOnce] 协程。
     *
     * 留住句柄是为了「用户点了立即刷新」时能掐掉还堵在退避重试里的旧请求，
     * 而不是让新请求被 [refreshing] 静默吞掉（用户表现为「点了毫无反应」）。
     */
    private var refreshJob: Job? = null

    /** 「启动云桌面」的互斥 Job；活跃期间禁止重复触发 */
    private var startActionJob: Job? = null

    /** 「停止云桌面」的互斥 Job（含取消确认巡检）；活跃期间禁止重入，否则同一 run 会被反复取消 */
    private var stopActionJob: Job? = null

    /** 正在读取云桌面连接信息的协程 */
    private var linkJob: Job? = null

    private lateinit var tvStatus: TextView
    private lateinit var tvLastUpdate: TextView
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
     * 上一次**成功**取到状态的时间戳，0 表示从未成功。
     *
     * 它是 tvLastUpdate 写文案的依据：成功覆盖成「最后更新 HH:mm:ss」，
     * 失败则保留它作为「上次更新」的参照，好让用户知道界面上的数据有多旧。
     */
    private var lastStatusOkAt = 0L

    /** 「HH:mm:ss」格式化器。仅在主线程调用（SimpleDateFormat 非线程安全） */
    private val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /**
     * 覆盖状态行的临时文案（优先于 [statusText]）。
     *
     * 取消确认巡检期间 30 秒轮询仍在跑、会不断调用 [updateUi]，没有它的话
     * 用户看到的依旧是「运行中」，完全不知道刚才那一下点击到底有没有受理。
     */
    private var statusOverride: String? = null

    /**
     * 防重入标志。请求内部会做退避重试，单次耗时可能超过 30 秒轮询间隔，
     * 不加该标志会导致请求层层堆积、越积越多。
     *
     * 注意：该标志**只能**交给当时最新的那个刷新协程复位（见 [refreshOnce] 的 finally），
     * 否则被强制掐掉的旧协程会在事后把标志改回 false，堆积又会重演。
     */
    private var refreshing = false

    /** 缓存的 API 实例与它的配置签名，配置没变就一直复用 */
    private var cachedApi: GitHubApi? = null
    private var cachedApiKey: String = ""

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
        // 光取消 pollJob 是不够的：refreshOnce 起的是独立 Job，不会跟着结束，
        // 于是回到前台时 refreshing 可能还是 true，之后的刷新全被静默吞掉。
        pollJob?.cancel()
        pollJob = null
        refreshJob?.cancel()
        refreshJob = null
        refreshing = false
    }

    private fun bindViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvLastUpdate = findViewById(R.id.tvLastUpdate)
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
        btnRefresh.setOnClickListener { refreshOnce(force = true, userInitiated = true) }
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

    /**
     * 取当前配置对应的 [GitHubApi]，配置没变则复用旧实例。
     *
     * 原来每次请求都新建一个实例，握手与连接池无法跨请求复用，一轮「拉状态」的大半时间
     * 耗在 TLS 握手上；配合应用层缓存后，同一配置下的连续请求能稳定命中同一条长连接。
     */
    private fun apiNow(): GitHubApi? {
        if (prefs.token.isBlank()) return null
        val key = "${prefs.token}\u0000${prefs.owner}\u0000${prefs.repo}\u0000${prefs.workflowFile}"
        val cached = cachedApi
        if (cached != null && cachedApiKey == key) return cached
        val created = GitHubApi(prefs.token, prefs.owner, prefs.repo, prefs.workflowFile)
        cachedApi = created
        cachedApiKey = key
        return created
    }

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
                delay(POLL_INTERVAL_MS)
            }
        }
        // 回到前台立刻拉一次：用户此时看到的很可能还是离开前的陈旧状态
        refreshOnce(force = true)
    }

    /**
     * 拉一次最新状态并刷新界面。
     *
     * @param force true = 抢占式：先掐掉上一次仍在飞行/退避重试里的请求再发新的，并且
     *   立刻在 tvLastUpdate 上给出「正在刷新…」。自动场景（切回前台、启停结束后的确认）
     *   同样需要抢占能力，否则会被 [refreshing] 挡住。
     *   false = 30 秒轮询：撞上飞行中的请求就静默跳过，不打扰用户。
     * @param userInitiated true = **用户亲手点的「立即刷新」**。只有这种情况才弹 Toast；
     *   其余自动场景（onResume、启停结束）都有各自的 Toast/状态行反馈，
     *   再叠一个「状态已刷新」会变成噪音 —— 每次切回前台都弹一下，谁都受不了。
     */
    private fun refreshOnce(force: Boolean = false, userInitiated: Boolean = false) {
        if (force) {
            // 上一次请求可能还堵在重试退避里（单次理论可挂上百秒），直接取消腾位置
            refreshJob?.cancel()
        } else if (refreshing) {
            return
        }
        val api = apiNow() ?: run {
            tvStatus.text = "未配置 Token，请点击下方设置"
            return
        }
        refreshing = true
        if (force) {
            // tvStatus 会被 updateUi() 用状态文案覆盖回去，看不出「正在刷新」的瞬间；
            // 这个常驻的时间戳行才是用户能看见的证据
            tvLastUpdate.text = "正在刷新…"
        }
        refreshJob = lifecycleScope.launch {
            try {
                val run: RunInfo? = try {
                    api.latestRun()
                } catch (e: Exception) {
                    if (isActive) {
                        tvStatus.text = "获取状态失败：${e.message}"
                        markRefreshFailed()
                        // 失败也必须刷新按钮：否则两个按钮会一直沿用上一轮的启用/禁用状态
                        updateUi()
                    }
                    return@launch
                }
                if (!isActive) return@launch
                currentRun = run
                markRefreshSuccess()
                updateUi()
                // 状态行依赖 currentRun，必须在此同步刷新：refreshLink 可能因节流被跳过，
                // 若只靠它回调，用户点「启动云桌面」后状态行会一直停在旧值。
                updateLinkUi()
                // 用户点「立即刷新」时也一并更新连接信息区，否则那块区域永远匀速落后
                refreshLink(force = force)
                if (userInitiated && isActive) {
                    Toast.makeText(this@MainActivity, "状态已刷新", Toast.LENGTH_SHORT).show()
                }
            } finally {
                // 只有「当时最新的那个刷新协程」可以复位标志：
                // ① 被 force 掐掉的旧协程必须闭嘴，否则它会把新协程的标志改回 false，
                //    堆积照样发生；② 句柄还没赋回来就跑完的情况也要兜住，
                //    否则 refreshing 会永久卡在 true，之后所有刷新都被静默吞掉
                val self = coroutineContext[Job]
                val current = refreshJob
                if (current == null || current === self) {
                    refreshing = false
                    if (current === self) refreshJob = null
                }
            }
        }
    }

    // ---------- 启停操作 ----------

    private fun startSession() {
        if (startActionJob?.isActive == true) return
        val api = apiNow() ?: run {
            Toast.makeText(this, "请先在设置中配置 Token", Toast.LENGTH_SHORT).show(); return
        }
        val hours = selectedHours()
        btnStart.isEnabled = false
        btnStart.text = "启动中…"
        // ⚠️ 同上 stopSession：协程体首句必须是挂起调用（此处是 api.dispatchWorkflow），
        // 否则 Main.immediate 会抢在 `startActionJob =` 赋值前执行，导致互斥失效。
        startActionJob = lifecycleScope.launch {
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
                // 先把互斥 Job 摘掉，再基于最新已知状态复位按钮：不能只指望下面那次刷新
                // 走到 updateUi，一旦它被吞掉按钮就会永久灰掉
                startActionJob = null
                btnStart.text = "启动云桌面"
                updateUi()
                // 即便上面因超时抛错，请求也可能实际已送达，刷新一次以确认真实状态
                refreshOnce(force = true)
            }
        }
    }

    /**
     * 停止会话：强制拉一次最新 run，再取消，最后轮询到 GitHub 确认为止。
     *
     * 旧实现有两个致命问题：① 直接拿界面上那份 currentRun 去取消，而它很可能上一次
     * 会话的残留（上一轮刷新失败/超时所致），取消打在已结束的 run 上会拿到 409，
     * 又被 GitHubApi 当成成功，界面于是谎报「已发送停止命令」，机器却照跑；
     * ② 发完就收工，没有重入保护也没有任何等待反馈，用户只能一直点。
     */
    private fun stopSession() {
        if (stopActionJob?.isActive == true) return
        val api = apiNow() ?: run {
            Toast.makeText(this, "请先在设置中配置 Token", Toast.LENGTH_SHORT).show()
            return
        }
        btnStop.isEnabled = false
        btnStop.text = "停止中…"
        setStatusOverride("正在读取最新会话状态…")
        // ⚠️ 约束：协程体的**第一条有效语句必须是一个挂起调用**（此处是 api.latestRun）。
        // lifecycleScope 用的是 Dispatchers.Main.immediate，协程体会抢在
        // `stopActionJob = ...` 赋值完成前就同步执行一段；一旦有人在挂起点之前插入
        // updateUi() 或 early-return，busy 会被算成 false，按钮禁用当场失效。
        stopActionJob = lifecycleScope.launch {
            try {
                // ① 先拿最新的一份 run 当取消目标，绝不用可能过期的界面缓存
                var failureDetail: String? = null
                val target: RunInfo? = try {
                    api.latestRun(maxAttempts = INTERACTIVE_RUN_ATTEMPTS).also { currentRun = it }
                } catch (e: Exception) {
                    failureDetail = e.message
                    null
                }
                if (target == null) {
                    val message = failureDetail?.let { "无法确认当前会话：$it，请稍后重试" }
                        ?: "当前没有运行中的会话，无需停止"
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    updateUi()
                    return@launch
                }
                if (!SessionState.isCancelable(target)) {
                    // 没有活跃会话就直说，绝不谎报「已发送停止命令」
                    Toast.makeText(this@MainActivity, "当前没有运行中的会话，无需停止", Toast.LENGTH_LONG).show()
                    updateUi()
                    return@launch
                }

                // ② 发取消
                setStatusOverride("已发送停止命令，正在等待 GitHub 确认…")
                var outcome: CancelOutcome = CancelOutcome.FAILED
                try {
                    outcome = api.cancelRun(target.id)
                    failureDetail = null
                } catch (e: Exception) {
                    outcome = CancelOutcome.FAILED
                    failureDetail = e.message
                }

                // 用**表达式**形式的 when：枚举将来多出一个值时这里会编译失败，
                // 而不是静默跳过那个分支让用户点了停止却毫无反应
                val message = when (outcome) {
                    CancelOutcome.ACCEPTED -> if (
                        watchCancellation(api, target.id) == CancelWatchVerdict.CONFIRMED
                    ) {
                        "已停止：云桌面会话已结束，虚拟机正在回收"
                    } else {
                        "取消指令已送达，但 GitHub 尚未确认生效，可再点一次「停止云桌面」重试"
                    }
                    CancelOutcome.ALREADY_FINISHED -> "该会话已经结束，本次未发出新的停止命令"
                    CancelOutcome.FAILED ->
                        "停止失败：${failureDetail ?: "未知错误"}\n请稍后重试，或到 GitHub 页面手动取消"
                }
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            } finally {
                // 无论成功、失败还是异常，都要把按钮从「停止中…」恢复到可用状态
                stopActionJob = null
                statusOverride = null
                btnStop.text = "停止云桌面"
                updateUi()
                refreshOnce(force = true)
            }
        }
    }

    /**
     * 取消确认巡检。
     *
     * GitHub 的 cancel 返回 202 只代表「已受理」，job 真被杀要几十秒甚至更久
     * （本 workflow 最后一个 step 是 PowerShell 长驻保活循环，还带着 easytier-core
     * 子进程）。这段时间必须给用户可见的进度，而不是立刻把按钮恢复让他继续点。
     *
     * @return [CancelWatchVerdict.CONFIRMED] 表示已确认回收，其余表示超时未确认
     */
    private suspend fun watchCancellation(api: GitHubApi, targetRunId: Long): CancelWatchVerdict {
        val startedAt = System.currentTimeMillis()
        var attempt = 0
        var verdict = CancelWatchVerdict.CONTINUE
        while (verdict == CancelWatchVerdict.CONTINUE && attempt < SessionState.CANCEL_WATCH_MAX_ATTEMPTS) {
            delay(SessionState.CANCEL_WATCH_INTERVAL_MS)
            attempt++
            val latest = try {
                api.latestRun(maxAttempts = INTERACTIVE_RUN_ATTEMPTS)
            } catch (e: Exception) {
                null
            }
            verdict = SessionState.evaluateCancelWatch(
                targetRunId = targetRunId,
                latest = latest,
                elapsedMs = System.currentTimeMillis() - startedAt
            )
            // 只有「还是同一个目标」或「已确认回收」才写回界面数据。
            // 巡检途中若有人新 dispatch 了一个 run，直接采用它会让 updateUi() 按新会话
            // 把停止按钮重新启用，与此时仍在生效的 statusOverride（正在等待确认）自相矛盾。
            if (latest != null && (latest.id == targetRunId || verdict == CancelWatchVerdict.CONFIRMED)) {
                currentRun = latest
            }
            // 巡检期间保持状态行与按钮贴合实际情况（此时 statusOverride 仍在生效）
            if (verdict == CancelWatchVerdict.CONTINUE) updateUi()
        }
        return verdict
    }

    private fun setStatusOverride(text: String?) {
        statusOverride = text
        if (text != null) tvStatus.text = text
    }

    /** 一次成功的刷新：记下时点并把它展示出来，作为「刚才确实动过」的证据 */
    private fun markRefreshSuccess() {
        lastStatusOkAt = System.currentTimeMillis()
        tvLastUpdate.text = "最后更新 " + clockFormat.format(Date(lastStatusOkAt))
    }

    /**
     * 一次失败的刷新：保留上一次成功的时点，让用户知道屏幕上的数据已经陈旧到什么程度。
     * 从未成功过则直说，不能凭空给个时间。
     */
    private fun markRefreshFailed() {
        tvLastUpdate.text = if (lastStatusOkAt == 0L) {
            "刷新失败：尚未成功获取"
        } else {
            "刷新失败：上次更新 " + clockFormat.format(Date(lastStatusOkAt))
        }
    }

    // ---------- 界面刷新 ----------

    private fun updateUi() {
        val run = currentRun
        val running = SessionState.isActive(run?.status)
        // 启停操作飞行期间，两个按钮都必须保持禁用：否则用户能在同一 run 上反复点停止
        val busy = startActionJob?.isActive == true || stopActionJob?.isActive == true
        btnStart.isEnabled = !running && !busy
        btnStop.isEnabled = running && !busy
        tvStatus.text = statusOverride ?: statusText(run)

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
        // 过渡态（queued/requested/waiting/pending）统一按「排队中」展示，
        // 否则落在下行会显示成「上次会话：pending」，与已启用的停止按钮自相矛盾
        SessionState.isPending(run.status) -> "排队中（等待分配虚拟机）"
        run.status == SessionState.STATUS_IN_PROGRESS -> "运行中"
        run.conclusion == "success" -> "上次会话：正常结束"
        run.conclusion == "failure" -> "上次会话：失败（可到 GitHub 查看日志）"
        run.conclusion == "cancelled" -> "上次会话：已结束（手动停止或已跑满时长上限）"
        else -> "上次会话：${run.conclusion ?: run.status}"
    }

    // ---------- AgentDock 连接提示词 ----------

    /**
     * 读取状态文件。
     *
     * 节流周期略小于轮询周期，使**每一轮都能拉到最新值**。状态文件由云桌面侧每 1 分钟
     * 同步一次；这里若再压 2 分钟，登录后在手机上看到「已就绪」最长要等 3 分钟。
     * 现在的节奏是「云桌面 ≤60s ＋ 本机 ≤30s」，端到端滞后 ≤90 秒。
     * 调用量约 240 次/小时（带 Token 限额 5000/小时），可忽略。
     */
    private fun refreshLink(force: Boolean) {
        if (prefs.token.isBlank()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastLinkFetchAt < LINK_FETCH_INTERVAL_MS) return
        val api = apiNow() ?: return
        // 先占住时间戳避免并发重复触发，真正失败时再回滚，
        // 否则一次网络抖动就白白吃掉 25 秒配额
        val stamp = now
        lastLinkFetchAt = stamp
        linkJob?.cancel()
        linkJob = lifecycleScope.launch {
            var ok = false
            try {
                agentDockLink = api.fetchAgentDockLink(prefs.stateOwner, prefs.stateRepo, prefs.statePath)
                ok = true
            } catch (e: Exception) {
                if (force) {
                    Toast.makeText(this@MainActivity, "读取连接信息失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                // 失败一律回滚时间戳，让下一次轮询可以立刻重试
                if (!ok && lastLinkFetchAt == stamp) lastLinkFetchAt = 0L
                updateLinkUi()
            }
        }
    }

    private fun updateLinkUi() {
        val link = agentDockLink
        if (link == null) {
            val run = currentRun
            val pending = run != null && run.status in SessionState.PENDING_STATUSES
            val running = run != null && run.status == SessionState.STATUS_IN_PROGRESS
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
            run.status in SessionState.PENDING_STATUSES -> LinkState.QUEUED
            run.status == SessionState.STATUS_IN_PROGRESS -> when {
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
        /** 主状态轮询周期。仅前台生效：onPause 会取消 pollJob，后台不会请求。 */
        private const val POLL_INTERVAL_MS = 30_000L

        /** 连接信息拉取节流。必须小于 [POLL_INTERVAL_MS]，否则会出现等待空窗。 */
        private const val LINK_FETCH_INTERVAL_MS = 25_000L

        /**
         * 交互路径（点「停止」前后定位 run）的重试次数。
         *
         * 后台轮询可以慢慢退避重试，用户在按钮前干等不行，少一轮退避少最坏几十秒。
         */
        private const val INTERACTIVE_RUN_ATTEMPTS = 2
    }

    // ---------- 剪贴板 ----------

    private fun copyToClipboard(label: String, value: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "$label 已复制", Toast.LENGTH_SHORT).show()
    }
}
