/*
 * Copyright (C) 2014 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.define

import android.content.Context
import android.os.Build
import android.os.DeadObjectException
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.settings.DebugSettings
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object DebugFlags {
    @JvmField
    var DEBUG_ENABLED = false

    fun init(context: Context) {
        DEBUG_ENABLED = context.prefs().getBoolean(DebugSettings.PREF_DEBUG_MODE, Defaults.PREF_DEBUG_MODE)
        CrashReportExceptionHandler(context.applicationContext).install()
    }
}

// basically copied from StreetComplete
private class CrashReportExceptionHandler(val appContext: Context) : Thread.UncaughtExceptionHandler {
    private var defaultUncaughtExceptionHandler: Thread.UncaughtExceptionHandler? = null

    fun install(): Boolean {
        val ueh = Thread.getDefaultUncaughtExceptionHandler()
        if (ueh is CrashReportExceptionHandler)
            return false
        defaultUncaughtExceptionHandler = ueh
        Thread.setDefaultUncaughtExceptionHandler(this)
        return true
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        if (isCausedBySystemDeath(e)) {
            // The Android system_server process is dying — reboot, OOM-kill of the
            // system, or framework-internal crash. Every IPC into the system from
            // this process will now fail with DeadSystemException; the OS will kill
            // this process shortly. It is not an app bug and not actionable, so
            // skip the crash report (which would otherwise misattribute the fault
            // to the keyboard) and let the default handler terminate us cleanly.
            defaultUncaughtExceptionHandler?.uncaughtException(t, e)
            return
        }

        val stackTrace = StringWriter()

        e.printStackTrace(PrintWriter(stackTrace))
        writeCrashReportToFile("""
Thread: ${t.name}
App version: ${BuildConfig.VERSION_NAME}
Device: ${Build.BRAND} ${Build.DEVICE}, Android ${Build.VERSION.RELEASE}
Locale: ${Locale.getDefault()}
Stack trace:
$stackTrace
Last log:
${Log.getLog(100).joinToString("\n")}
""")
        defaultUncaughtExceptionHandler!!.uncaughtException(t, e)
    }

    /**
     * Detects system-process death via DeadObjectException and its subclasses.
     * <p>
     * Two forms are possible:
     *   • DeadObjectException / DeadSystemException — thrown directly, or appearing
     *     as a cause further down the chain.
     *   • DeadSystemRuntimeException (API 33+) — wraps DeadSystemException as its
     *     cause, so walking the chain catches it too.
     * Class-hierarchy-agnostic name matching guards against API-level gaps.
     */
    private fun isCausedBySystemDeath(e: Throwable): Boolean {
        var cause: Throwable? = e
        val seen = HashSet<Throwable>()
        while (cause != null && seen.add(cause)) {
            if (cause is DeadObjectException) return true
            if (cause.javaClass.name == "android.os.DeadSystemRuntimeException") return true
            cause = cause.cause
        }
        return false
    }

    private fun writeCrashReportToFile(text: String) {
        try {
            val dir = appContext.getExternalFilesDir(null)
            val date = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(Calendar.getInstance().time)
            val crashReportFile = File(dir, "crash_report_$date.txt")
            crashReportFile.appendText(text)
        } catch (_: Exception) {
            // can't write in external files dir, maybe device just booted and is still locked
            // in this case there shouldn't be any sensitive data and we can put crash logs in unprotected files dir
            val dir = DeviceProtectedUtils.getFilesDir(appContext) ?: return
            val date = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(Calendar.getInstance().time)
            val crashReportFile = File(dir, "crash_report_unprotected_$date.txt")
            crashReportFile.appendText(text)
        }
    }
}
