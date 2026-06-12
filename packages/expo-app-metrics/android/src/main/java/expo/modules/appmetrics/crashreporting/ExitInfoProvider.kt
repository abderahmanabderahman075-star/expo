package expo.modules.appmetrics.crashreporting

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import expo.modules.appmetrics.utils.TimeUtils
import kotlin.math.abs

/**
 * Platform-free projection of an [ApplicationExitInfo] record. Decouples
 * `CrashReportProcessor` from the API-30 framework type so its logic runs and
 * tests on every SDK level.
 */
data class ExitRecord(
  /** One of the `ApplicationExitInfo.REASON_*` constants. */
  val reason: Int,
  /** Exit status — the signal number when the process died by signal/native crash. */
  val status: Int,
  val description: String?,
  val timestampMillis: Long,
  val pid: Int
) {
  /** Stable identity used to track already-processed records across launches. */
  val key: String
    get() = "$timestampMillis:$pid:$reason"

  /**
   * Whether this record proves the process died abnormally — used to
   * corroborate a pending JVM crash file in debuggable builds. Broader than
   * [isStandaloneCrash]: a signaled death confirms a crash file even though a
   * bare `REASON_SIGNALED` record alone doesn't count as a crash.
   */
  val isDeathRecord: Boolean
    get() = reason == ApplicationExitInfo.REASON_CRASH ||
      reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
      reason == ApplicationExitInfo.REASON_SIGNALED

  /**
   * The explicit v1 detection policy: only true crashes count, matching how
   * Play Console separates crash rate from ANR rate. ANRs, low-memory kills,
   * and bare signals are deliberately excluded.
   */
  val isStandaloneCrash: Boolean
    get() = reason == ApplicationExitInfo.REASON_CRASH ||
      reason == ApplicationExitInfo.REASON_CRASH_NATIVE

  /**
   * Whether this record describes the same death as a pending JVM crash file:
   * the same pid within a narrow time window (pids get reused, so the window
   * keeps the match honest).
   */
  fun matches(file: PendingJvmCrash): Boolean =
    pid == file.pid && abs(timestampMillis - file.crashedAtMillis) <= PID_TIME_WINDOW_MS

  /**
   * Minimal cross-platform report for a death we only know from the OS record —
   * native crashes (no JVM handler ran) or JVM crashes whose file was lost.
   * The status of a Java crash is an exit code, not a signal, so `signal` is
   * only set for native crashes.
   */
  fun toCrashReport(ingestedAt: String, appVersion: String): CrashReport {
    val crashTimestamp = TimeUtils.millisToTimestamp(timestampMillis)
    return CrashReport(
      signal = if (reason == ApplicationExitInfo.REASON_CRASH_NATIVE) status else null,
      terminationReason = description,
      appVersion = appVersion,
      timestampBegin = crashTimestamp,
      timestampEnd = crashTimestamp,
      ingestedAt = ingestedAt
    )
  }

  companion object {
    private const val PID_TIME_WINDOW_MS = 5 * 60 * 1000L
  }
}

/** Test seam over `ActivityManager.getHistoricalProcessExitReasons`. */
fun interface ExitInfoProvider {
  fun getExitRecords(): List<ExitRecord>
}

/**
 * Real provider. Returns records of the **current process name only**:
 * `getHistoricalProcessExitReasons` reports every process of the package, and a
 * `:background` worker's death must not be mistaken for a main-process crash.
 * Empty below API 30 — native crashes on older devices are an accepted gap
 * (the JVM handler still covers Java crashes there).
 */
class AndroidExitInfoProvider(private val context: Context) : ExitInfoProvider {
  override fun getExitRecords(): List<ExitRecord> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      return emptyList()
    }
    return runCatching { queryExitRecords() }.getOrElse { emptyList() }
  }

  @RequiresApi(Build.VERSION_CODES.R)
  private fun queryExitRecords(): List<ExitRecord> {
    val activityManager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
    val processName = Application.getProcessName()
    return activityManager
      .getHistoricalProcessExitReasons(context.packageName, NO_PID_FILTER, NO_MAX)
      .filter { it.processName == processName }
      .map { info ->
        ExitRecord(
          reason = info.reason,
          status = info.status,
          description = info.description,
          timestampMillis = info.timestamp,
          pid = info.pid
        )
      }
  }

  companion object {
    private const val NO_PID_FILTER = 0
    private const val NO_MAX = 0
  }
}
