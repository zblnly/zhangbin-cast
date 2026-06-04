package com.zhangbin.cast.phone

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * 全局闪退捕获器 — 写入公共 Downloads 目录（重装后不丢失）
 * 并且保留最近 3 次崩溃历史
 */
class CrashHandler private constructor() : Thread.UncaughtExceptionHandler {

    companion object {
        private const val TAG = "ZhangbinCrash"

        fun init(context: Context) {
            // 1. 先记录上次 onCreate 异常（如果有的话）
            val pending = pendingCrash
            if (pending != null) {
                saveCrashLog(context, "onCreate异常", pending)
                pendingCrash = null
            }

            // 2. 注册全局异常处理
            val handler = CrashHandler()
            handler.appContext = context.applicationContext
            Thread.setDefaultUncaughtExceptionHandler(handler)
        }

        /** 临时保存 onCreate 中的异常 */
        var pendingCrash: Throwable? = null

        /** 写入闪退日志到公共目录 */
        fun saveCrashLog(context: Context, label: String, ex: Throwable) {
            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                val dir = File(downloadsDir, "ZhangbinCast")
                dir.mkdirs()

                // 保留最近 3 次，删除旧的
                val existing = dir.listFiles { f -> f.name.startsWith("crash_") }
                    ?.sortedByDescending { it.lastModified() }
                if (existing != null && existing.size >= 3) {
                    existing.drop(3).forEach { it.delete() }
                }

                val sw = StringWriter()
                val pw = PrintWriter(sw)
                val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
                pw.println("=== 张斌手机投屏 闪退报告 ===")
                pw.println("时间: ${dateFormat.format(Date())}")
                pw.println("标签: $label")
                pw.println("线程: ${Thread.currentThread().name}")
                pw.println()
                ex.printStackTrace(pw)
                pw.close()

                val fileName = "crash_${dateFormat.format(Date())}.txt"
                val file = File(dir, fileName)
                FileWriter(file).use { it.write(sw.toString()) }

                // 也保留一份简短的到 cache
                val cacheFile = File(context.cacheDir, "latest_crash.txt")
                FileWriter(cacheFile).use { it.write(sw.toString()) }
            } catch (_: Exception) {}
        }

        /** 从公共目录读取最近一次闪退日志 */
        fun getLatestCrashLog(): String? {
            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                val dir = File(downloadsDir, "ZhangbinCast")
                if (!dir.exists()) return null
                val files = dir.listFiles { f -> f.name.startsWith("crash_") }
                    ?.sortedByDescending { it.lastModified() }
                    ?: return null
                if (files.isEmpty()) return null
                val content = files.first().readText()
                return content.ifBlank { null }
            } catch (_: Exception) { return null }
        }
    }

    private var appContext: Context? = null
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        try {
            val ctx = appContext ?: return
            saveCrashLog(ctx, "uncaughtException", ex)
        } catch (_: Exception) {}

        defaultHandler?.uncaughtException(thread, ex)
    }
}
