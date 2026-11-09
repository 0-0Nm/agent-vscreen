package com.kilon.agentvscreen.host

import android.content.Context
import android.net.ConnectivityManager
import com.kilon.agentvscreen.api.ApiConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.security.SecureRandom

/**
 * 1.1.6: the built-in Tailscale node, so other devices can reach this phone without the Tailscale app.
 *
 * The node is `libtsbridge.so` (tsnet, userspace networking: no VPN slot, no root), shipped in the APK and run as a
 * child process of this app. It joins the user's tailnet as its own device ("agent-vscreen") and forwards HTTP sent to
 * <node>:port to our local server, adding [secret] so [com.kilon.agentvscreen.api.ApiServer] knows the request really
 * came from the tailnet, and the peer's identity. The token is still required: tailnet membership alone gives nothing.
 *
 * The child dies with us (its stdin is our pipe: EOF ends it) and is restarted with backoff while the mode is on.
 * Login: the bridge reports an auth URL (the user signs in in a browser) or uses an auth key given once.
 */
object Tailnet {
    private const val TAG = "Tailnet"

    enum class Mode(val key: String) { OFF("off"), BUILTIN("builtin"), SYSTEM("system");
        companion object { fun of(k: String?) = entries.firstOrNull { it.key == k } ?: OFF }
    }

    data class Status(
        val running: Boolean = false,
        val state: String = "Stopped",
        val authUrl: String? = null,
        val ip: String? = null,
        val dnsName: String? = null,
        val tailnet: String? = null,
        val user: String? = null,
        val peers: Int = 0,
        val online: Int = 0,
        val error: String? = null,
        val lastPeer: String? = null,
    ) {
        val loggedIn get() = state == "Running" && ip != null
        val needsLogin get() = state == "NeedsLogin" || state == "NeedsMachineAuth"
        fun json(): JSONObject = JSONObject().put("mode", "builtin").put("state", state).put("ip", ip ?: JSONObject.NULL)
            .put("dns_name", dnsName ?: JSONObject.NULL).put("tailnet", tailnet ?: JSONObject.NULL)
            .put("peers_online", online).put("error", error ?: JSONObject.NULL).put("last_peer", lastPeer ?: JSONObject.NULL)
    }

    /** Random per process: only our own child knows it, so no app on the phone can pose as a tailnet peer. */
    val secret: String = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile private var proc: Process? = null
    @Volatile private var stdin: OutputStreamWriter? = null
    @Volatile private var wanted = false
    @Volatile private var restarts = 0
    private var supervisor: Thread? = null

    fun binary(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir, "libtsbridge.so")
    fun available(ctx: Context): Boolean = binary(ctx).canExecute()

    /** Names the caller may use for this node in the Host header. */
    fun hostNames(): Set<String> {
        val s = _status.value
        return setOfNotNull(s.ip, s.dnsName?.lowercase(), s.dnsName?.substringBefore('.')?.lowercase())
    }

    /** Called from HostService start and whenever the setting changes. */
    @Synchronized fun sync(ctx: Context) {
        if (ApiConfig.tailnetMode == Mode.BUILTIN) start(ctx.applicationContext) else stop()
    }

    @Synchronized private fun start(ctx: Context) {
        wanted = true
        if (supervisor?.isAlive == true) return
        supervisor = Thread({ supervise(ctx) }, "vscreen-tailnet").apply { isDaemon = true; start() }
    }

    @Synchronized fun stop() {
        wanted = false
        runCatching { stdin?.apply { write("quit\n"); flush() } }
        proc?.let { p -> Thread { Thread.sleep(1500); runCatching { p.destroy() } }.apply { isDaemon = true }.start() }
        supervisor?.interrupt(); supervisor = null
        _status.value = Status()
    }

    /** Sign out of the tailnet (the node stays listed in the admin console until removed there). */
    fun logout() { runCatching { stdin?.apply { write("logout\n"); flush() } } }

    /** Restart now, e.g. with a new auth key or control server. */
    fun restart(ctx: Context) { stop(); sync(ctx) }

    private fun supervise(ctx: Context) {
        val bin = binary(ctx)
        while (wanted && !Thread.currentThread().isInterrupted) {
            if (!bin.canExecute()) {
                _status.value = Status(state = "Error", error = "内置 Tailscale 组件缺失（$bin）"); return
            }
            val t0 = System.currentTimeMillis()
            runCatching { runOnce(ctx, bin) }.onFailure { e ->
                AppLogger.warning(TAG, "bridge: ${e.javaClass.simpleName}: ${e.message}")
                _status.value = _status.value.copy(running = false, state = "Error", error = e.message)
            }
            if (!wanted) break
            if (System.currentTimeMillis() - t0 > 60_000) restarts = 0
            val wait = (1_000L shl restarts.coerceAtMost(5)).coerceAtMost(30_000L)
            restarts++
            AppLogger.info(TAG, "bridge ended, restarting in ${wait / 1000}s")
            try { Thread.sleep(wait) } catch (_: InterruptedException) { break }
        }
    }

    private fun runOnce(ctx: Context, bin: File) {
        val dir = File(ctx.filesDir, "tailscale").apply { mkdirs() }
        val pb = ProcessBuilder(bin.absolutePath).directory(dir)
        val env = pb.environment()
        env["TSB_DIR"] = dir.absolutePath
        env["TSB_HOSTNAME"] = ApiConfig.tailnetHostname
        env["TSB_PORT"] = ApiConfig.port.toString()
        env["TSB_TARGET"] = "127.0.0.1:${ApiConfig.port}"
        env["TSB_SECRET"] = secret
        env["TSB_DNS"] = dnsServers(ctx).joinToString(",")
        env["HOME"] = dir.absolutePath
        ApiConfig.tailnetControlUrl?.let { env["TSB_CONTROL_URL"] = it }
        // An auth key is only needed until the node is registered; it is dropped as soon as the node runs.
        ApiConfig.tailnetAuthKey?.let { env["TSB_AUTHKEY"] = it }
        val p = pb.start()
        proc = p
        stdin = OutputStreamWriter(p.outputStream)
        _status.value = _status.value.copy(running = true, error = null)
        AppLogger.info(TAG, "bridge started (pid-less child), host=${ApiConfig.tailnetHostname}")
        Thread({
            p.errorStream.bufferedReader().forEachLine { l -> if (l.isNotBlank()) AppLogger.info(TAG, l.take(300)) }
        }, "vscreen-tailnet-err").apply { isDaemon = true }.start()
        p.inputStream.bufferedReader().forEachLine { line ->
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
            val st = Status(
                running = true,
                state = o.optString("state", "Unknown"),
                authUrl = o.optString("auth_url").takeIf { it.isNotBlank() },
                ip = o.optString("ip").takeIf { it.isNotBlank() },
                dnsName = o.optString("dns_name").takeIf { it.isNotBlank() },
                tailnet = o.optString("tailnet").takeIf { it.isNotBlank() },
                user = o.optString("user").takeIf { it.isNotBlank() },
                peers = o.optInt("peers"), online = o.optInt("online"),
                error = o.optString("error").takeIf { it.isNotBlank() },
                lastPeer = o.optString("last_peer").takeIf { it.isNotBlank() },
            )
            if (st.state != _status.value.state) AppLogger.info(TAG, "state ${st.state}${st.ip?.let { " $it" } ?: ""}${st.error?.let { " ($it)" } ?: ""}")
            if (st.loggedIn && ApiConfig.tailnetAuthKey != null) ApiConfig.tailnetAuthKey = null
            _status.value = st
        }
        val code = runCatching { p.waitFor() }.getOrDefault(-1)
        proc = null; stdin = null
        _status.value = _status.value.copy(running = false, state = if (wanted) "Restarting" else "Stopped",
            error = if (code != 0 && wanted) "内置 Tailscale 退出（$code）" else null)
    }

    /** The phone's resolvers: Android has no /etc/resolv.conf for the Go resolver. */
    private fun dnsServers(ctx: Context): List<String> = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.getLinkProperties(cm.activeNetwork)?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()
    }.getOrDefault(emptyList())
}
