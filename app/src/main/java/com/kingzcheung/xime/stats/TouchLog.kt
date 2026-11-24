package com.kingzcheung.xime.stats

import android.content.Context
import java.io.File
import java.util.concurrent.Executors

/**
 * 触点记录（调试，默认关）：每次按键记下按在键的哪里、松手在哪里，删除键也记一行，用来找误触多的键和按偏的方向。
 * 只存在本机 files/touch-log.csv，不联网；密码框和无痕模式不记。超过 [MAX_BYTES] 时旧的一半丢掉。
 *
 * 每行：时间毫秒,键,键左,键上,键宽,键高,按下x,按下y,松手x,松手y,按住毫秒。坐标是键盘根布局里的像素；
 * 删除键那行只有时间和「delete」。
 */
object TouchLog {
    const val PREF_KEY = "touch_log_enabled"
    const val FILE_NAME = "touch-log.csv"
    private const val HEADER = "time,key,left,top,width,height,down_x,down_y,up_x,up_y,held_ms"
    private const val MAX_BYTES = 8L * 1024 * 1024

    @Volatile var enabled = false
        private set
    /** 当前输入框是密码框等，不记。由输入法在每个输入框开始时设置。 */
    @Volatile var secretEditor = false
    @Volatile private var file: File? = null
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "touch-log").apply { isDaemon = true } }

    fun init(context: Context) {
        file = file(context)
        enabled = com.kingzcheung.xime.settings.SettingsPreferences.getPrefsPublic(context).getBoolean(PREF_KEY, false)
    }

    fun setEnabled(context: Context, on: Boolean) {
        com.kingzcheung.xime.settings.SettingsPreferences.getPrefsPublic(context).edit().putBoolean(PREF_KEY, on).apply()
        file = file(context)
        enabled = on
    }

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun recording() = enabled && !secretEditor && !com.kingzcheung.xime.settings.IncognitoState.active

    fun key(key: String, left: Float, top: Float, width: Float, height: Float,
            downX: Float, downY: Float, upX: Float, upY: Float, heldMs: Long) {
        if (!recording()) return
        val name = key.replace(",", "comma").replace("\n", "").ifEmpty { "?" }
        append("${System.currentTimeMillis()},$name,${left.toInt()},${top.toInt()},${width.toInt()},${height.toInt()}," +
            "${downX.toInt()},${downY.toInt()},${upX.toInt()},${upY.toInt()},$heldMs")
    }

    fun delete() {
        if (!recording()) return
        append("${System.currentTimeMillis()},delete,,,,,,,,,")
    }

    fun clear(context: Context) {
        val target = file(context)
        writer.execute { target.delete() }
    }

    private fun append(line: String) {
        val target = file ?: return
        writer.execute {
            runCatching {
                if (target.length() > MAX_BYTES) {
                    val lines = target.readLines().drop(1)
                    target.writeText(HEADER + "\n" + lines.drop(lines.size / 2).joinToString("") { "$it\n" })
                }
                if (!target.exists() || target.length() == 0L) target.writeText(HEADER + "\n")
                target.appendText(line + "\n")
            }
        }
    }
}
