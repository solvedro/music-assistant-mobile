package io.music_assistant.client.logging

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import co.touchlab.kermit.platformLogWriter

/** Android keeps Kermit's stock Logcat writer and also writes to a live log file. */
actual fun platformLogWriters(): List<LogWriter> = listOf(
    platformLogWriter(),
    object : LogWriter() {
        private val file = java.io.File("/sdcard/ma_live.log")
        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            try {
                android.util.Log.println(android.util.Log.INFO, "MA_$tag", message)
                file.appendText("${severity.name}/$tag: $message\n")
                if (throwable != null) {
                    file.appendText(throwable.stackTraceToString() + "\n")
                }
            } catch (_: Exception) {}
        }
    }
)
