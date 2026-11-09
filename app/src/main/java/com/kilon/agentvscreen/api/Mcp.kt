package com.kilon.agentvscreen.api

import android.util.Base64
import com.kilon.agentvscreen.BuildConfig
import com.kilon.agentvscreen.cmd.ShotStore
import com.kilon.agentvscreen.cmd.VScreenOffloadHandler
import org.json.JSONArray
import org.json.JSONObject

/**
 * Model Context Protocol over streamable HTTP (POST, JSON responses, no server-initiated stream). One tool, `vscreen`,
 * takes the same command line as the CLI; pictures come back as image content so the caller's own model sees them.
 */
internal object Mcp {
    private val VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")

    private val TOOL = JSONObject()
        .put("name", "vscreen")
        .put("description", "在看不见的副屏里操作手机上的应用（不占用户主屏）。参数 command 就是一条 vscreen 命令行，例如 " +
            "`launch 设置`、`act \"tap 搜索; type 耳机; key enter until text:综合\"`、`act`（看一眼）、`status`、`help act`。" +
            "返回四段文字（做了什么 / 这段时间 / 现在 / 注意）和截图；画面的意思由你看图判断。\n\n" + VScreenOffloadHandler.QUICK_HELP)
        .put("inputSchema", JSONObject().put("type", "object")
            .put("properties", JSONObject()
                .put("command", JSONObject().put("type", "string").put("description", "vscreen 后面的部分，如 act \"tap 搜索; type 耳机\""))
                .put("session", JSONObject().put("type", "string").put("description", "可选：会话名（一个副屏同一时间属于一个会话），默认取请求头 X-VScreen-Session 或 default")))
            .put("required", JSONArray().put("command")))

    fun handle(server: ApiServer, r: ApiServer.Req): ApiServer.Resp {
        val body = r.bodyText.trim()
        if (body.startsWith("[")) {
            val arr = runCatching { JSONArray(body) }.getOrElse { return ApiServer.Resp.json(error(null, -32700, "parse error"), 400) }
            val out = JSONArray()
            for (i in 0 until arr.length()) one(server, r, arr.optJSONObject(i))?.let { out.put(it) }
            return if (out.length() == 0) ApiServer.Resp(202, "text/plain", ByteArray(0)) else ApiServer.Resp.json(out)
        }
        val msg = runCatching { JSONObject(body) }.getOrElse { return ApiServer.Resp.json(error(null, -32700, "parse error"), 400) }
        val res = one(server, r, msg) ?: return ApiServer.Resp(202, "text/plain", ByteArray(0))
        val headers = if (msg.optString("method") == "initialize") mapOf("Mcp-Session-Id" to java.util.UUID.randomUUID().toString()) else emptyMap()
        return ApiServer.Resp.json(res, 200, headers)
    }

    /** null for notifications (no response). */
    private fun one(server: ApiServer, r: ApiServer.Req, m: JSONObject?): JSONObject? {
        if (m == null) return error(null, -32600, "invalid request")
        val id = m.opt("id")
        val method = m.optString("method")
        if (id == null || id == JSONObject.NULL) return null // notification / response
        val p = m.optJSONObject("params") ?: JSONObject()
        return when (method) {
            "initialize" -> {
                val want = p.optString("protocolVersion")
                result(id, JSONObject()
                    .put("protocolVersion", if (want in VERSIONS) want else VERSIONS.first())
                    .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                    .put("serverInfo", JSONObject().put("name", "agent-vscreen").put("version", BuildConfig.VERSION_NAME))
                    // 1.1.6: the full short guide, so every new conversation of an MCP agent knows how to use us
                    // without anyone having saved a note.
                    .put("instructions", "Agent-VScreen：这台手机上的后台副屏。要打开、查看或操作手机里的应用时，用 vscreen 工具（参数 command 就是一条 vscreen 命令行），" +
                        "它在看不见的副屏上操作，不打扰用户主屏，不需要 adb。\n\n" + AgentGuide.guide().replace("`vscreen ", "`")))
            }
            "ping" -> result(id, JSONObject())
            "tools/list" -> result(id, JSONObject().put("tools", JSONArray().put(TOOL)))
            "tools/call" -> {
                if (p.optString("name") != "vscreen") return error(id, -32602, "unknown tool ${p.optString("name")}")
                val a = p.optJSONObject("arguments") ?: JSONObject()
                val cmd = a.optString("command").trim()
                val argv = ApiServer.splitCommand(cmd)
                val session = server.sessionOf(r, a)
                val res = server.exec(argv, session)
                val shots = ShotStore.refs(res.output)
                var text = res.output
                shots.forEachIndexed { i, n -> text = text.replace(ShotStore.REF + n, "[图${i + 1}，见附图]") }
                val content = JSONArray().put(JSONObject().put("type", "text").put("text", text))
                for (n in shots.take(4)) {
                    val f = ShotStore.file(server.ctxFiles(), n) ?: continue
                    content.put(JSONObject().put("type", "image").put("mimeType", "image/jpeg")
                        .put("data", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)))
                }
                result(id, JSONObject().put("content", content).put("isError", res.exitCode != 0))
            }
            "resources/list" -> result(id, JSONObject().put("resources", JSONArray()))
            "prompts/list" -> result(id, JSONObject().put("prompts", JSONArray()))
            else -> error(id, -32601, "method not found: $method")
        }
    }

    private fun result(id: Any, r: JSONObject) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", r)
    private fun error(id: Any?, code: Int, msg: String) = JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", msg))
}
