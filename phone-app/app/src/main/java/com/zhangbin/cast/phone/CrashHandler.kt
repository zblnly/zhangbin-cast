package com.zhangbin.cast.phone

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * 全局闪退捕获器 — 崩溃时写文件，下次启动显示
 */
class CrashHandler private constructor() : Thread.UncaughtExceptionHandler {

    companion object {
        private const val CRASH_FILE = "zhangbin_crash.log"

        fun init(context: Context) {
            val handler = CrashHandler()
            handler.appContext = context.applicationContext
            Thread.setDefaultUncaughtExceptionHandler(handler)
        }

        /** 读取上一次的崩溃日志 */
        fun getLastCrash(context: Context): String? {
            val file = File(context.filesDir, CRASH_FILE)
            if (!file.exists()) return null
            val text = file.readText()
            file.delete()
            return text.ifBlank { null }
        }
    }

    private var appContext: Context? = null
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        try {
            val ctx = appContext ?: return
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            pw.println("=== ${dateFormat.format(Date())} ===")
            pw.println("线程: ${thread.name}")
            pw.println()
            ex.printStackTrace(pw)
            pw.close()

            val file = File(ctx.filesDir, CRASH_FILE)
            FileWriter(file).use { it.write(sw.toString()) }
        } catch (_: Exception) {}

        defaultHandler?.uncaughtException(thread, ex)
    }
}
