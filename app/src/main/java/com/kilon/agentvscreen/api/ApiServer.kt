package com.kilon.agentvscreen.api

import android.content.Context
import com.kilon.agentvscreen.BuildConfig
import com.kilon.agentvscreen.cmd.NativeOffloadRequest
import com.kilon.agentvscreen.cmd.NativeOffloadResult
import com.kilon.agentvscreen.cmd.ShotStore
import com.kilon.agentvscreen.cmd.VScreenOffloadHandler
import com.kilon.agentvscreen.host.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * The one door every agent comes through: a tiny HTTP server on 127.0.0.1 (never on the network).
 *
 *   GET  /v1/ping            no token  — is it running, which version
 *   GET  /v1/help            no token  — the full command reference (same text as `vscreen help all`)
 *   GET  /cli                no token  — the `vscreen` shell client (curl based)
 *   POST /v1/run             token     — run one command: JSON {"args":[…]} or {"cmd":"act \"…\""} or form a=…&a=…
 *   GET  /v1/shot/<name>     token     — a picture a command referenced
 *   GET  /v1/events          token     — events posted by keeps (?session=&wait=30s)
 *   POST /mcp                token     — Model Context Protocol (streamable HTTP, JSON responses)
 *
 * Token: `Authorization: Bearer <t>`, `X-VScreen-Token: <t>` or `?token=<t>`. Session (who owns the screen):
 * `X-VScreen-Session`, `session=` or the JSON field, default "default". Requests that carry a browser Origin or a
 * Host other than localhost are refused, so a web page cannot drive the phone.
 */
class ApiServer(private val ctx: Context) {
    fun ctxFiles(): Context = ctx
    private val TAG = "ApiServer"
    private val handler = VScreenOffloadHandler(ctx)
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "vscreen-http").apply { isDaemon = true } }
    @Volatile private var server: ServerSocket? = null
    @Volatile var lastError: String? = null; private set
    @Volatile var requests = 0L; private set
    @Volatile var lastClient: String? = null; private set

    val running: Boolean get() = server?.isClosed == false

    /** 1.2: the extra socket on this phone's Tailscale address (null: off, or no tailnet address now). */
    @Volatile private var tailServer: ServerSocket? = null
    @Volatile var tailAddress: String? = null; private set
    @Volatile private var tailWatch: Thread? = null

    /** Called when [lastError] / listening changes (the notification shows it). */
    @Volatile var onChange: (() -> Unit)? = null
    @Volatile private var retry: Thread? = null
    @Volatile var bindFailures = 0; private set

    fun start(port: Int) {
        stop()
        if (ApiConfig.tailnet) startTailnet(port)
        if (!bindLocal(port)) retryBind(port)
    }

    /**
     * Bind 127.0.0.1:[port]. SO_REUSEADDR must be set BEFORE bind (setting it on a bound socket does nothing), so a
     * restart never trips over connections of the previous run still in TIME_WAIT.
     */
    private fun bindLocal(port: Int, owner: String? = null): Boolean {
        val ss = ServerSocket()
        try {
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 50)
        } catch (e: Exception) {
            runCatching { ss.close() }
            bindFailures++
            lastError = "端口 $port 被占用：${owner ?: "正在查是谁占着"}，正在自动重试（第 $bindFailures 次）"
            AppLogger.warning(TAG, "bind $port: ${e.message} — ${owner ?: "?"}")
            onChange?.invoke()
            return false
        }
        server = ss; lastError = null
        if (bindFailures > 0) AppLogger.info(TAG, "port $port free again after $bindFailures failed tries")
        bindFailures = 0
        Thread({ acceptLoop(ss) }, "vscreen-http-accept").apply { isDaemon = true }.start()
        AppLogger.info(TAG, "listening on 127.0.0.1:$port")
        onChange?.invoke()
        return true
    }

    /**
     * The port is taken. Almost always by our own previous process that is still going away (after an update, a crash
     * restart, or a kill while frozen): it lets go within seconds to a minute. So keep trying (1 s, 2 s, 4 s … 15 s),
     * and say who holds it, so the user is never left with a dead server and no hint.
     */
    private fun retryBind(port: Int) {
        retry = Thread({
            var wait = 1_000L
            while (!Thread.currentThread().isInterrupted && server == null) {
                val who = portOwner(port)
                try { Thread.sleep(wait) } catch (_: InterruptedException) { break }
                if (bindLocal(port, who)) break
                wait = (wait * 2).coerceAtMost(15_000L)
            }
        }, "vscreen-http-rebind").apply { isDaemon = true }.also { it.start() }
    }

    /** Who holds 127.0.0.1:[port] — asked over the port itself (not on the main thread). */
    private fun portOwner(port: Int): String = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 800)
            s.soTimeout = 1_500
            s.getOutputStream().write("GET /v1/ping HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
            val buf = ByteArray(4096); var n = 0
            val inp = s.getInputStream()
            while (n < buf.size) { val k = inp.read(buf, n, buf.size - n); if (k <= 0) break; n += k }
            val txt = String(buf, 0, n, Charsets.UTF_8)
            if ("agent-vscreen" in txt) {
                val pid = Regex("\"pid\":(\\d+)").find(txt)?.groupValues?.get(1)
                "另一个 Agent-VScreen 进程${pid?.let { "（pid $it）" } ?: ""}还占着它，旧进程退出后就会接上"
            } else "别的程序在用这个端口：可以在“接入 agent”里换一个端口，再重新复制接入命令"
        }
    } catch (e: java.net.SocketTimeoutException) {
        "占着它的进程没有响应，多半是被系统冻结、还没退出的旧 Agent-VScreen 进程"
    } catch (e: Exception) {
        "端口刚刚被放开"
    }

    fun stop() {
        retry?.interrupt(); retry = null
        runCatching { server?.close() }; server = null
        tailWatch?.interrupt(); tailWatch = null
        runCatching { tailServer?.close() }; tailServer = null; tailAddress = null
    }

    /**
     * Other devices, off by default: listen ALSO on this phone's Tailscale address (100.64.0.0/10), never on Wi-Fi / mobile
     * interfaces. Only devices in the user's own tailnet can reach it, over WireGuard. The address can appear or change
     * (Tailscale started later), so it is checked every 20 s.
     */
    private fun startTailnet(port: Int) {
        tailWatch = Thread({
            while (!Thread.currentThread().isInterrupted) {
                val want = tailnetAddress()
                if (want?.hostAddress != tailAddress) {
                    runCatching { tailServer?.close() }; tailServer = null; tailAddress = null
                    if (want != null) runCatching {
                        val ss = ServerSocket(port, 50, want).apply { reuseAddress = true }
                        tailServer = ss; tailAddress = want.hostAddress
                        AppLogger.info(TAG, "also listening on tailnet ${want.hostAddress}:$port")
                        Thread({ acceptLoop(ss) }, "vscreen-http-tail").apply { isDaemon = true }.start()
                    }.onFailure { AppLogger.warning(TAG, "tailnet bind: ${it.message}") }
                }
                try { Thread.sleep(20_000) } catch (_: InterruptedException) { break }
            }
        }, "vscreen-tailnet-watch").apply { isDaemon = true }.also { it.start() }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val s = try { ss.accept() } catch (e: SocketException) { break } catch (e: Exception) { continue }
            pool.execute { serve(s) }
        }
    }

    // ── HTTP ──────────────────────────────────────────────────────────────

    class Req(val method: String, val path: String, val query: Map<String, List<String>>, val headers: Map<String, String>, val body: ByteArray) {
        /** Who connected: loopback (this phone) or a tailnet peer. */
        @Volatile var peer: InetAddress? = null
        fun q(k: String) = query[k]?.firstOrNull()
        val bodyText: String get() = String(body, Charsets.UTF_8)
    }
    class Resp(val status: Int, val type: String, val body: ByteArray, val headers: Map<String, String> = emptyMap()) {
        companion object {
            fun json(o: Any, status: Int = 200, headers: Map<String, String> = emptyMap()) =
                Resp(status, "application/json; charset=utf-8", o.toString().toByteArray(), headers)
            fun text(t: String, status: Int = 200, headers: Map<String, String> = emptyMap()) =
                Resp(status, "text/plain; charset=utf-8", t.toByteArray(), headers)
            fun err(status: Int, code: String, msg: String) = json(JSONObject().put("ok", false).put("code", code).put("error", msg), status)
        }
    }

    private fun serve(s: Socket) {
        s.use { sock ->
            runCatching {
                sock.soTimeout = 30_000
                val inp = BufferedInputStream(sock.getInputStream())
                val req = read(inp) ?: return
                req.peer = sock.inetAddress
                sock.soTimeout = 0 // a command (act --watch, events --wait) may take minutes
                val resp = try { route(req) } catch (t: Throwable) {
                    AppLogger.warning(TAG, "route ${req.path}: ${t.javaClass.simpleName}: ${t.message}")
                    Resp.err(500, "INTERNAL_ERROR", "${t.javaClass.simpleName}: ${t.message}")
                }
                write(sock.getOutputStream(), resp)
            }.onFailure { if (it !is SocketException) AppLogger.warning(TAG, "serve: ${it.message}") }
        }
    }

    private fun read(inp: InputStream): Req? {
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = inp.read(); if (b < 0) return null
            head.write(b); last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (head.size() > 32_768) return null
        }
        val lines = head.toString("UTF-8").split("\r\n")
        val parts = lines.first().split(" ")
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        for (l in lines.drop(1)) { val i = l.indexOf(':'); if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim() }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len > 4_000_000) return null
        val body = ByteArray(len)
        var off = 0
        while (off < len) { val n = inp.read(body, off, len - off); if (n < 0) break; off += n }
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = parseForm(target.substringAfter('?', ""))
        return Req(parts[0].uppercase(), path, query, headers, body)
    }

    private fun write(out: OutputStream, r: Resp) {
        val reason = when (r.status) { 200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Error" }
        val sb = StringBuilder("HTTP/1.1 ${r.status} $reason\r\nContent-Type: ${r.type}\r\nContent-Length: ${r.body.size}\r\nConnection: close\r\nCache-Control: no-store\r\nX-VScreen-Version: ${BuildConfig.VERSION_NAME}\r\n")
        r.headers.forEach { (k, v) -> sb.append(k).append(": ").append(v.replace("\r", "").replace("\n", " ")).append("\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray()); out.write(r.body); out.flush()
    }

    // ── routing ───────────────────────────────────────────────────────────

    private fun route(r: Req): Resp {
        // No browser may drive the phone (CSRF / DNS rebinding): only local hosts, no Origin.
        val host = r.headers["host"]?.substringBefore(':')?.lowercase()
        val remote = r.peer?.let { !it.isLoopbackAddress } == true
        if (remote) {
            // Only a tailnet peer reaches this (the socket is bound to the Tailscale address); it must still name us by
            // that address or a MagicDNS name, so a web page cannot rebind a hostname onto us.
            if (!ApiConfig.tailnet || !isTailnet(r.peer)) return Resp.err(403, "NOT_ALLOWED", "其它设备访问没有打开")
            if (host != null && host != tailAddress && !host.endsWith(".ts.net")) return Resp.err(403, "BAD_HOST", "用本机的 Tailscale 地址访问")
        } else if (host != null && host !in setOf("127.0.0.1", "localhost", "[::1]")) return Resp.err(403, "BAD_HOST", "只接受 127.0.0.1 / localhost")
        if (r.headers["origin"].let { it != null && it != "null" }) return Resp.err(403, "BROWSER_REFUSED", "不接受网页发起的请求")
        requests++
        when (r.path) {
            "/", "/v1/ping" -> return Resp.json(JSONObject().put("ok", true).put("app", "agent-vscreen")
                .put("version", BuildConfig.VERSION_NAME).put("code", BuildConfig.VERSION_CODE).put("pid", android.os.Process.myPid())
                .put("endpoints", JSONArray(listOf("/v1/run", "/v1/shot/<name>", "/v1/events", "/v1/help", "/mcp", "/cli"))))
            "/v1/help" -> return Resp.text(VScreenOffloadHandler.HELP + "\n")
            "/cli" -> return Resp.text(cliScript())
            "/skill" -> return Resp.text(AgentGuide.skill())
        }
        if (!authorized(r)) return Resp.err(401, "BAD_TOKEN", "缺少或错误的令牌：在 Agent-VScreen 里复制接入命令/令牌（Authorization: Bearer <令牌>）")
        lastClient = r.headers["user-agent"]?.take(60)
        return when {
            r.path == "/v1/run" -> if (r.method == "POST") run(r) else Resp.err(405, "METHOD", "POST")
            r.path.startsWith("/v1/shot/") -> shot(r.path.removePrefix("/v1/shot/"))
            r.path == "/v1/events" -> events(r)
            r.path == "/mcp" -> if (r.method == "POST") Mcp.handle(this, r) else Resp(405, "text/plain", "MCP: POST JSON-RPC (no SSE stream)".toByteArray(), mapOf("Allow" to "POST"))
            else -> Resp.err(404, "NOT_FOUND", r.path)
        }
    }

    private fun authorized(r: Req): Boolean {
        val t = ApiConfig.token
        if (t.isEmpty()) return false
        val given = r.headers["authorization"]?.removePrefix("Bearer ")?.trim() ?: r.headers["x-vscreen-token"] ?: r.q("token")
        return given != null && constEq(given, t)
    }

    private fun constEq(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var d = 0; for (i in a.indices) d = d or (a[i].code xor b[i].code); return d == 0
    }

    fun sessionOf(r: Req, json: JSONObject? = null): String =
        (json?.optString("session")?.takeIf { it.isNotBlank() } ?: r.headers["x-vscreen-session"] ?: r.q("session"))
            ?.trim()?.take(64)?.replace(Regex("[^A-Za-z0-9_.:-]"), "_")?.takeIf { it.isNotEmpty() } ?: VScreenOffloadHandler.DEFAULT_SESSION

    /** Run one command for [session]: the caller's turn is open while it runs and for the quiet window after. */
    fun exec(argv: List<String>, session: String): NativeOffloadResult {
        com.kilon.agentvscreen.host.A11yHeal.maybeHeal(ctx, "command")
        Turns.begin(session)
        try { return handler.handle(NativeOffloadRequest(listOf("vscreen") + argv, session)) }
        finally { Turns.end(session) }
    }

    /** Picture URLs use the address the caller used (this phone: 127.0.0.1; a tailnet peer: our tailnet address). */
    fun shotUrl(name: String, r: Req? = null): String {
        val h = r?.headers?.get("host")?.takeIf { r.peer?.isLoopbackAddress == false && it.isNotBlank() }
        return (if (h != null) "http://$h" else ApiConfig.baseUrl) + "/v1/shot/$name"
    }

    private fun run(r: Req): Resp {
        val ct = r.headers["content-type"].orEmpty()
        var json: JSONObject? = null
        val argv: List<String> = when {
            ct.startsWith("application/x-www-form-urlencoded") -> parseForm(r.bodyText)["a"].orEmpty()
            else -> {
                json = runCatching { JSONObject(r.bodyText.ifBlank { "{}" }) }.getOrElse { return Resp.err(400, "BAD_JSON", "body 要是 JSON：{\"args\":[\"act\",\"tap 搜索\"]} 或 {\"cmd\":\"act \\\"tap 搜索\\\"\"}") }
                json.optJSONArray("args")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                    ?: json.optString("cmd").takeIf { it.isNotBlank() }?.let { splitCommand(it) } ?: emptyList()
            }
        }
        val session = sessionOf(r, json)
        val res = exec(argv, session)
        val shots = ShotStore.refs(res.output)
        val textWithUrls = ShotStore.IN_TEXT.replace(res.output) { shotUrl(it.groupValues[1], r) }
        // 1.1: a command whose output is JSON (status, --json) is also given parsed as `data`, and its own "ok": false
        // makes the exit non-zero; an error's code is given as `code` — callers never parse text to know the outcome.
        val data = textWithUrls.trim().takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
        val exit = if (res.exitCode == 0 && data?.optBoolean("ok", true) == false) 1 else res.exitCode
        val code = data?.optString("code")?.takeIf { it.isNotBlank() } ?: ERR_CODE.find(textWithUrls)?.groupValues?.get(1)
        val headers = mapOf("X-VScreen-Exit" to exit.toString(), "X-VScreen-Images" to shots.joinToString(","), "X-VScreen-Session" to session) +
            (code?.let { mapOf("X-VScreen-Code" to it) } ?: emptyMap())
        return if (r.q("format") == "text" || r.headers["accept"]?.startsWith("text/plain") == true) Resp.text(textWithUrls, 200, headers)
        else Resp.json(JSONObject().put("ok", exit == 0).put("exit", exit).put("code", code ?: JSONObject.NULL).put("session", session)
            .put("text", textWithUrls).put("data", data ?: JSONObject.NULL)
            .put("images", JSONArray(shots.map { JSONObject().put("name", it).put("url", shotUrl(it, r)) })), 200, headers)
    }

    private fun shot(name: String): Resp {
        val f = ShotStore.file(ctx, name) ?: return Resp.err(404, "NO_SHOT", "没有这张图（只保留最近 300 张）：$name")
        return Resp(200, "image/jpeg", f.readBytes())
    }

    private fun events(r: Req): Resp {
        val wait = r.q("wait")?.let { com.kilon.agentvscreen.cmd.VScreenOptions.durationMs(it) } ?: 0L
        val evs = EventHub.take(sessionOf(r), wait)
        return Resp.json(JSONObject().put("ok", true).put("events", JSONArray(evs)))
    }

    private var cliCache: String? = null
    private fun cliScript(): String = cliCache ?: ctx.assets.open("cli/vscreen.sh").bufferedReader().readText()
        .replace("@@PORT@@", ApiConfig.port.toString()).replace("@@VERSION@@", BuildConfig.VERSION_NAME)

    companion object {
        /** Tailscale hands out 100.64.0.0/10 (CGNAT) addresses. */
        fun isTailnet(a: InetAddress?): Boolean {
            val b = (a as? java.net.Inet4Address)?.address ?: return false
            return (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64
        }

        /** This phone's tailnet address, if Tailscale is connected. */
        fun tailnetAddress(): InetAddress? = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp }
                .flatMap { it.inetAddresses.toList() }.firstOrNull { isTailnet(it) }
        }.getOrNull()

        private val ERR_CODE = Regex("【错误】\\s*([A-Z][A-Z_]{2,})")
        fun parseForm(s: String): Map<String, List<String>> {
            if (s.isEmpty()) return emptyMap()
            val out = LinkedHashMap<String, MutableList<String>>()
            for (kv in s.split('&')) {
                if (kv.isEmpty()) continue
                val k = URLDecoder.decode(kv.substringBefore('='), "UTF-8")
                val v = URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8")
                out.getOrPut(k) { ArrayList() } += v
            }
            return out
        }

        /** Shell-like split: spaces separate, "…" and '…' group, backslash escapes inside "…" and outside quotes. */
        fun splitCommand(s: String): List<String> {
            val out = ArrayList<String>(); val cur = StringBuilder(); var inTok = false
            var i = 0; var q: Char? = null
            while (i < s.length) {
                val c = s[i]
                when {
                    q == '\'' -> if (c == '\'') q = null else cur.append(c)
                    q == '"' -> when {
                        c == '"' -> q = null
                        c == '\\' && i + 1 < s.length && s[i + 1] in "\"\\$`" -> { cur.append(s[i + 1]); i++ }
                        else -> cur.append(c)
                    }
                    c == '"' || c == '\'' -> { q = c; inTok = true }
                    c == '\\' && i + 1 < s.length -> { cur.append(s[i + 1]); i++; inTok = true }
                    c.isWhitespace() -> { if (inTok) { out += cur.toString(); cur.clear(); inTok = false } }
                    else -> { cur.append(c); inTok = true }
                }
                i++
            }
            if (inTok) out += cur.toString()
            return out.let { if (it.firstOrNull() == "vscreen") it.drop(1) else it }
        }
    }
}
