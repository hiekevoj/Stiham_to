package cz.plzen.stihamto

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

object CrashHandler {
    private const val TAG = "CrashHandler"
    private const val CRASH_FILE_NAME = "last_crash.txt"
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true
        val appCtx = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                throwable.printStackTrace(pw)
                val stackTrace = sw.toString()

                val crashReport = buildString {
                    appendLine("=== PÁD APLIKACE STÍHÁM TO ===")
                    appendLine("Datum: ${Date()}")
                    appendLine("Vlákno: ${thread.name} (id: ${thread.id})")
                    appendLine("Výjimka: ${throwable::class.java.name}: ${throwable.message}")
                    appendLine("--- StackTrace ---")
                    appendLine(stackTrace)
                }

                Log.e(TAG, crashReport)

                val file = File(appCtx.filesDir, CRASH_FILE_NAME)
                file.writeText(crashReport)
            } catch (e: Throwable) {
                Log.e(TAG, "Chyba při ukládání stack trace pádu: ${e.message}")
            }

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun consumeLastCrashReport(context: Context): String? {
        return try {
            val file = File(context.filesDir, CRASH_FILE_NAME)
            if (file.exists()) {
                val content = file.readText()
                file.delete()
                content
            } else null
        } catch (_: Throwable) {
            null
        }
    }
}
