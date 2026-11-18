package com.kilon.agentvscreen.cmd

/** Pure, dependency-free presentation mapping for the human layout inspector. */
internal object LayoutInspectorPresentation {
    fun title(
        text: String?,
        description: String?,
        resourceId: String?,
        className: String?,
        password: Boolean = false,
    ): String {
        if (password) return "受保护内容"
        val value = listOf(text, description).firstOrNull { !it.isNullOrBlank() && it != "null" }?.trim()
        if (!value.isNullOrBlank()) return friendly(value).take(80)
        val id = resourceId.orEmpty().substringAfterLast('/').substringAfterLast(':').trim()
        if (Regex("digit_[0-9]+").matches(id)) return "数字 " + id.substringAfter('_')
        val known = mapOf("formula" to "计算表达式", "result" to "计算结果", "clr" to "清除", "eq" to "等号", "calculator_mode_toge_button" to "计算模式切换")
        known[id]?.let { return it }
        val simple = className.orEmpty().substringAfterLast('.').substringAfterLast('$').trim()
        if (simple.isNotBlank() && simple != "null" && simple != " ") return genericName(simple)
        return "无名称控件"
    }

    fun kind(className: String?, clickable: Boolean, editable: Boolean): String {
        val simple = className.orEmpty().substringAfterLast('.').substringAfterLast('$')
        if (editable || simple.contains("EditText", true) || simple.contains("TextInput", true)) return "输入框"
        if (clickable || simple.contains("Button", true) || simple.contains("CheckBox", true) || simple.contains("Switch", true)) return "可点击控件"
        return when {
            simple.contains("Image", true) -> "图片"
            simple.contains("Text", true) -> "文本"
            simple.contains("Recycler", true) || simple.contains("List", true) -> "列表"
            simple.contains("Scroll", true) -> "滚动容器"
            simple.contains("Layout", true) || simple.contains("Group", true) || simple.contains("View", true) -> "容器"
            else -> "控件"
        }
    }

    fun status(code: String): String = when (code.uppercase()) {
        "READY", "OK", "BEST_EFFORT" -> "可检查"
        "CAPTURING", "LOADING" -> "正在采集…"
        "FRAME_CHANGED_DURING_CAPTURE", "FRAME_CHANGED" -> "页面已有新帧，当前为冻结快照"
        "CACHED_FRAME", "NO_FRAME_COPY", "NO_FRAME" -> "线框模式：只看布局，不叠加旧截图"
        "TREE_TRUNCATED" -> "布局较多，当前仅显示部分线框"
        "SENSITIVE_CONTENT" -> "包含受保护内容，已隐藏截图和文字"
        "COORDINATE_SPACE_MISMATCH" -> "坐标尚未对齐，请刷新；可先看布局树"
        "DISPLAY_NOT_ON" -> "副屏尚未亮起，当前为布局记录"
        "MIXED_WINDOWS", "MIXED" -> "窗口混合，已隐藏截图"
        "PAGE_CHANGED", "INVALID", "STALE" -> "页面已变化，请刷新"
        "TARGET_UNAVAILABLE", "ACCESSIBILITY_UNAVAILABLE", "TARGET_TREE_UNAVAILABLE" -> "目标页面暂不可读"
        "APP_BLOCKED", "BLOCKED" -> "此应用禁止读取布局"
        "USER_TAKEOVER" -> "接管中，交还后可检查"
        "STOPPED" -> "副屏已停止"
        "DISPLAY_CLOSED" -> "副屏已关闭"
        else -> if (code.isBlank()) "等待采集" else "暂时无法读取，请刷新或确认副屏仍打开"
    }

    fun shortReference(humanRef: String?, number: Int): String =
        "${humanRef?.takeIf { it.isNotBlank() } ?: "无快照"} / ${number.coerceAtLeast(1)}号"

    private fun genericName(simple: String): String = when (simple.lowercase()) {
        "calculatebutton" -> "计算按钮"
        "equalsbutton" -> "等号按钮"
        "submitbutton" -> "提交按钮"
        "cancelbutton" -> "取消按钮"
        "searchview" -> "搜索框"
        "imagebutton" -> "图片按钮"
        "checkbox" -> "复选框"
        "radiobutton" -> "单选框"
        "switch" -> "开关"
        "edittext" -> "输入框"
        "framelayout", "linearlayout", "relativelayout", "viewgroup" -> "布局容器"
        "textview" -> "文本控件"
        "view" -> "页面控件"
        else -> "未命名控件"
    }
    private fun friendly(value: String): String = when (value.lowercase().trim()) {
        "calculate" -> "计算页"; "scientific calculator" -> "科学计算器"; "standard calculator" -> "标准计算器"
        "exchange rate" -> "汇率"; "settings" -> "设置"; "open history", "history" -> "计算历史"
        "clear", "ac" -> "清除"; "equals" -> "等号"; "delete" -> "退格"; "display in small window" -> "小窗显示"
        "clear memory" -> "清除记忆"; "divide" -> "除号"; "multiply" -> "乘号"; "add" -> "加号"; "subtract" -> "减号"
        else -> value
    }
}

fun layoutInspectorTitle(text: String?, description: String?, resourceId: String?, className: String?, password: Boolean = false): String =
    LayoutInspectorPresentation.title(text, description, resourceId, className, password)

fun layoutInspectorKind(className: String?, clickable: Boolean, editable: Boolean): String =
    LayoutInspectorPresentation.kind(className, clickable, editable)

fun layoutInspectorStatus(code: String): String = LayoutInspectorPresentation.status(code)

fun layoutInspectorReference(humanRef: String?, number: Int): String =
    LayoutInspectorPresentation.shortReference(humanRef, number)
