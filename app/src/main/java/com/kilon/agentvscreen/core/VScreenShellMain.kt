package com.kilon.agentvscreen.core

/**
 * [vscreen] Entry point run as the Shizuku shell user through
 * `CLASSPATH=<this apk> app_process /system/bin com.kilon.agentvscreen.core.VScreenShellMain ...`.
 *
 * It runs outside the app process, with no Context and no app classes
 * initialised, so it only uses reflection on framework classes and prints
 * one line: `OK ...` or `ERR ...`. Kept by proguard (see proguard-rules.pro).
 *
 * Commands:
 *   ime-hide <displayId>   IWindowManager.setDisplayImePolicy(id, DISPLAY_IME_POLICY_HIDE)
 */
class VScreenShellMain {
    companion object {
        /** android.view.WindowManager.DISPLAY_IME_POLICY_HIDE (API 31). */
        private const val DISPLAY_IME_POLICY_HIDE = 2

        @JvmStatic
        fun main(args: Array<String>) {
            val code = try {
                when (args.getOrNull(0)) {
                    "ime-hide" -> imeHide(args.getOrNull(1)?.toIntOrNull() ?: error("missing displayId"))
                    else -> error("unknown command ${args.getOrNull(0)}")
                }
                0
            } catch (t: Throwable) {
                val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
                println("ERR ${cause.javaClass.simpleName}: ${cause.message}")
                1
            }
            System.exit(code)
        }

        private fun windowManager(): Any {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "window")
                ?: error("window service not found")
            val stub = Class.forName("android.view.IWindowManager\$Stub")
            return stub.getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)!!
        }

        private fun imeHide(displayId: Int) {
            if (android.os.Build.VERSION.SDK_INT < 31) error("needs Android 12+")
            val wm = windowManager()
            wm.javaClass.getMethod(
                "setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            ).invoke(wm, displayId, DISPLAY_IME_POLICY_HIDE)
            println("OK ime-hide $displayId")
        }
    }
}
