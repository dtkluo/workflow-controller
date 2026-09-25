package com.koljs.controller

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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

class ApiException(message: String) : Exception(message)

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
 * GitHub Actions REST API 封装
 */
class GitHubApi(
    private val token: String,
    private val owner: String,
    private val repo: String,
    private val workflowFile: String
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
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

    private fun failMessage(code: Int, body: String?): String = when (code) {
        401 -> "Token 无效或已过期"
        403 -> "Token 权限不足或请求被限流"
        404 -> "仓库或工作流不存在，请检查设置"
        422 -> "请求无效：${body?.take(160) ?: ""}"
        else -> "GitHub 返回错误码 $code"
    }

    /** 验证 Token 与仓库是否可访问，成功返回仓库全名 */
    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        client.newCall(buildRequest("", "GET")).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw ApiException(failMessage(resp.code, text))
            JSONObject(text).optString("full_name", "$owner/$repo")
        }
    }

    /** 触发工作流（ref 固定 main，输入 session_hours） */
    suspend fun dispatchWorkflow(hours: Int): Unit = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("ref", "main")
            put("inputs", JSONObject().put("session_hours", hours.toString()))
        }.toString()
        client.newCall(buildRequest("/actions/workflows/$workflowFile/dispatches", "POST", body))
            .execute().use { resp ->
                if (resp.code != 204) {
                    val text = resp.body?.string() ?: ""
                    throw ApiException(failMessage(resp.code, text))
                }
            }
    }

    /** 查询该工作流最近一次运行，无记录返回 null */
    suspend fun latestRun(): RunInfo? = withContext(Dispatchers.IO) {
        client.newCall(buildRequest("/actions/workflows/$workflowFile/runs?per_page=1", "GET"))
            .execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) throw ApiException(failMessage(resp.code, text))
                val arr = JSONObject(text).optJSONArray("workflow_runs")
                    ?: return@withContext null
                if (arr.length() == 0) return@withContext null
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

    /** 取消指定运行 */
    suspend fun cancelRun(runId: Long): Unit = withContext(Dispatchers.IO) {
        client.newCall(buildRequest("/actions/runs/$runId/cancel", "POST")).execute().use { resp ->
            if (resp.code != 202) {
                val text = resp.body?.string() ?: ""
                throw ApiException(failMessage(resp.code, text))
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

        client.newCall(request).execute().use { resp ->
            if (resp.code == 404) return@withContext null
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw ApiException(failMessage(resp.code, text))

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

private fun JSONObject.optText(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
