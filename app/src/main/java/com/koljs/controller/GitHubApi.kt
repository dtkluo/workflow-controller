package com.koljs.controller

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class RunInfo(
    val id: Long,
    val status: String,        // queued / in_progress / completed
    val conclusion: String?,  // success / failure / cancelled / null
    val createdAt: Date
)

/**
 * GitHub API 调用异常。
 *
 * @param retryable 标记该错误属于「网络层瞬时故障」（429 限流、5xx 服务端异常），
 *                  退避后重试有意义；Token 无效、仓库不存在等 4xx 业务错误为 false，
 *                  重试只会让用户多等一倍时间而结果不变。
 */
class ApiException(
    message: String,
    val retryable: Boolean = false,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * 云桌面同步过来的 AgentDock 连接信息（读取自私有仓库的状态文件）。
 * 其中 bearerToken / oauthPassword / prompt 属敏感内容，仅在本机展示与剪贴板复制。
 */
data class AgentDockLink(
    val updatedAt: String?,
    val publicMcpUrl: String?,
    val localMcpUrl: String?,
    val bearerToken: String?,
    val oauthPassword: String?,
    val version: String?,
    val health: String?,
    val tunnelMode: String?,
    val message: String?,
    val installed: Boolean,
    val coreAlive: Boolean,
    val healthzOk: Boolean,
    val easytierIp: String?,
    val socks5: String?,
    val rdpUser: String?,
    val prompt: String?
)

/**
 * GitHub Actions REST API 封装。
 *
 * 网络策略说明：国内直连 api.github.com 存在两个典型问题——① TLS/连接握手偏慢，
 * 偶发超过 15s；② 请求可能在传输中偶发中断。因此这里统一提高超时阈值，并对
 * **只读请求**做指数退避重试；写操作中 [dispatchWorkflow] 不自动重试（避免网络抖动
 * 导致同一会话被重复触发，白耗 Actions 额度），[cancelRun] 幂等故允许重试。
 */
class GitHubApi(
    private val token: String,
    private val owner: String,
    private val repo: String,
    private val workflowFile: String
) {
    private companion object {
        /** 连接超时：国内直连 api.github.com 握手常超 10s，原 15s 阈值过于激进 */
        const val CONNECT_TIMEOUT_S = 20L
        /** 读取超时：GitHub 首字节延迟 + 状态文件正文下载 */
        const val READ_TIMEOUT_S = 30L
        const val WRITE_TIMEOUT_S = 30L
        /** 单次调用总耗时上限，防止个别连接长时间挂住（含重定向与重试链） */
        const val CALL_TIMEOUT_S = 45L
        /** 只读请求的最大尝试次数 */
        const val MAX_ATTEMPTS = 3
        /** 退避基数：800ms，之后 1.6s（第 3 次前不再等待，直接抛出） */
        const val RETRY_BASE_DELAY_MS = 800L
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun buildRequest(path: String, method: String, body: String? = null): Request {
        val builder = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo$path")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
        return when (method) {
            "POST" -> builder.post((body ?: "{}").toRequestBody(jsonType)).build()
            else -> builder.get().build()
        }
    }

    /** 429（限流）与 5xx（服务端瞬时故障）才值得退避重试 */
    private fun isRetryable(code: Int): Boolean = code == 429 || code in 500..599

    private fun failMessage(code: Int, body: String?): String = when (code) {
        401 -> "Token 无效或已过期"
        403 -> "Token 权限不足或请求被限流"
        404 -> "仓库或工作流不存在，请检查设置"
        422 -> "请求无效：${body?.take(160) ?: ""}"
        429 -> "请求过于频繁，已达 GitHub 限流"
        in 500..599 -> "GitHub 服务端异常（$code）"
        else -> "GitHub 返回错误码 $code"
    }

    /**
     * 对网络层瞬时故障做指数退避重试。
     *
     * 可重试：连接失败 / 读写超时 / DNS 解析失败（均表现为 [IOException]），
     * 以及 429、5xx（包装为 `retryable = true` 的 [ApiException]）。
     * 不可重试：401 / 403 / 404 / 422 等业务错误，直接抛出。
     *
     * @param maxAttempts 写操作可传 1 以彻底关闭重试（见 [dispatchWorkflow]）
     */
    private suspend fun <T> withRetry(
        label: String,
        maxAttempts: Int = MAX_ATTEMPTS,
        block: suspend () -> T
    ): T {
        var lastError: Exception? = null
        for (attempt in 1..maxAttempts) {
            try {
                return block()
            } catch (e: ApiException) {
                if (!e.retryable || attempt == maxAttempts) throw e
                lastError = e
            } catch (e: IOException) {
                if (attempt == maxAttempts) {
                    throw ApiException(
                        "$label 失败：网络不可达或超时（已自动重试 $maxAttempts 次，请检查网络）",
                        cause = e
                    )
                }
                lastError = e
            }
            delay(RETRY_BASE_DELAY_MS shl (attempt - 1))
        }
        throw ApiException("$label 失败：${lastError?.message ?: "未知错误"}", cause = lastError)
    }

    /** 验证 Token 与仓库是否可访问，成功返回仓库全名 */
    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        withRetry("连接 GitHub") {
            client.newCall(buildRequest("", "GET")).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    throw ApiException(failMessage(resp.code, text), isRetryable(resp.code))
                }
                JSONObject(text).optString("full_name", "$owner/$repo")
            }
        }
    }

    /**
     * 触发工作流（ref 固定 main，输入 session_hours）。
     *
     * 不自动重试：`workflow_dispatch` 不具备幂等性，若首次请求实际已送达（仅响应丢失），
     * 重试会凭空多起一个占用额度的工作流实例。宁可让调用方提示用户确认是否已触发。
     */
    suspend fun dispatchWorkflow(hours: Int): Unit = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("ref", "main")
            put("inputs", JSONObject().put("session_hours", hours.toString()))
        }.toString()
        withRetry("触发启动", maxAttempts = 1) {
            client.newCall(buildRequest("/actions/workflows/$workflowFile/dispatches", "POST", body))
                .execute().use { resp ->
                    if (resp.code != 204) {
                        val text = resp.body?.string() ?: ""
                        throw ApiException(failMessage(resp.code, text), isRetryable(resp.code))
                    }
                }
        }
    }

    /** 查询该工作流最近一次运行，无记录返回 null */
    suspend fun latestRun(): RunInfo? = withContext(Dispatchers.IO) {
        withRetry("获取运行状态") {
            client.newCall(buildRequest("/actions/workflows/$workflowFile/runs?per_page=1", "GET"))
                .execute().use { resp ->
                    val text = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) {
                        throw ApiException(failMessage(resp.code, text), isRetryable(resp.code))
                    }
                    val arr = JSONObject(text).optJSONArray("workflow_runs")
                        ?: return@withRetry null
                    if (arr.length() == 0) return@withRetry null
                    val obj = arr.getJSONObject(0)
                    RunInfo(
                        id = obj.getLong("id"),
                        status = obj.getString("status"),
                        conclusion = if (obj.isNull("conclusion")) null else obj.getString("conclusion"),
                        createdAt = try {
                            dateFormat.parse(obj.getString("created_at")) ?: Date()
                        } catch (e: Exception) {
                            Date()
                        }
                    )
                }
        }
    }

    /** 取消指定运行（幂等：重复取消已完成实例无副作用，故允许重试） */
    suspend fun cancelRun(runId: Long): Unit = withContext(Dispatchers.IO) {
        withRetry("停止云桌面") {
            client.newCall(buildRequest("/actions/runs/$runId/cancel", "POST")).execute().use { resp ->
                // 202 = 已受理；409/422 = 该实例已结束，视作成功，不必报错
                if (resp.code != 202 && resp.code != 409 && resp.code != 422) {
                    val text = resp.body?.string() ?: ""
                    throw ApiException(failMessage(resp.code, text), isRetryable(resp.code))
                }
            }
        }
    }

    /**
     * 读取云桌面写入私有仓库的 AgentDock 连接信息与 AI 助手提示词。
     * 状态文件尚未生成（HTTP 404）时返回 null，表示当前会话还没同步过。
     */
    suspend fun fetchAgentDockLink(
        stateOwner: String,
        stateRepo: String,
        statePath: String
    ): AgentDockLink? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$stateOwner/$stateRepo/contents/$statePath")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()

        withRetry("读取云桌面连接信息") {
            client.newCall(request).execute().use { resp ->
                if (resp.code == 404) return@withRetry null
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    throw ApiException(failMessage(resp.code, text), isRetryable(resp.code))
                }

                val encoded = JSONObject(text).optString("content", "")
                val decoded = try {
                    val compact = encoded.replace("\n", "").replace("\r", "")
                    String(Base64.decode(compact, Base64.DEFAULT), Charsets.UTF_8)
                } catch (e: Exception) {
                    throw ApiException("状态文件解码失败：${e.message}")
                }

                val root = JSONObject(decoded)
                val agentDock = root.optJSONObject("agentdock")
                val desktop = root.optJSONObject("cloud_desktop")

                AgentDockLink(
                    updatedAt = root.optText("updated_at"),
                    publicMcpUrl = agentDock?.optText("public_mcp_url"),
                    localMcpUrl = agentDock?.optText("local_mcp_url"),
                    bearerToken = agentDock?.optText("bearer_token"),
                    oauthPassword = agentDock?.optText("oauth_password"),
                    version = agentDock?.optText("version"),
                    health = agentDock?.optText("health"),
                    tunnelMode = agentDock?.optText("tunnel_mode"),
                    message = agentDock?.optText("message"),
                    installed = agentDock?.optBoolean("installed", false) ?: false,
                    coreAlive = agentDock?.optBoolean("core_alive", false) ?: false,
                    healthzOk = agentDock?.optBoolean("healthz_ok", false) ?: false,
                    easytierIp = desktop?.optText("easytier_ip"),
                    socks5 = desktop?.optText("socks5"),
                    rdpUser = desktop?.optText("rdp_user"),
                    prompt = root.optText("prompt")
                )
            }
        }
    }
}

private fun JSONObject.optText(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
