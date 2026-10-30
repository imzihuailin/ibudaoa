package io.github.ibudaoa.running

import android.content.Context

/** User-controlled diagnostics; never changes Android debuggable or active-task persistence. */
internal object DiagnosticMode {
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("diagnostic-mode", Context.MODE_PRIVATE)
    fun enabled(context: Context): Boolean = prefs(context).getBoolean("enabled", false)
    fun set(context: Context, enabled: Boolean) {
        check(prefs(context).edit().putBoolean("enabled", enabled).commit())
        if (!enabled) clear(context)
    }
    fun clear(context: Context) {
        context.applicationContext.noBackupFilesDir.listFiles().orEmpty().filter {
            it.name.startsWith("run-result-") || it.name.startsWith("run-task-reviewed-")
        }.forEach { check(it.delete() || !it.exists()) { "Diagnostic cleanup failed" } }
    }
}
