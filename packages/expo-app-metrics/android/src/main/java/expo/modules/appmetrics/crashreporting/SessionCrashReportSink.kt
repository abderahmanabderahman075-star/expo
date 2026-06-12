package expo.modules.appmetrics.crashreporting

import android.util.Log
import expo.modules.appmetrics.TAG
import expo.modules.appmetrics.storage.SessionManager

/**
 * Attributes crash reports produced by [CrashReportProcessor] to sessions and
 * persists them. This is where session knowledge lives — the processor itself
 * is session-agnostic.
 *
 * Attribution:
 * - A report from a JVM crash file carries the crashing process's session id;
 *   it is stored under that id directly (precise even across several unprocessed
 *   crashes).
 * - A report with no id (native crash, lost file) is attributed to the previous
 *   main session — the most recent session that isn't the current one.
 *
 * Files are handed to this sink before bare records (see [CrashReportProcessor]),
 * so [reportedThisRun] keeps a richer file report from being overwritten by a
 * bare record that resolves to the same previous session.
 */
class SessionCrashReportSink(
  private val sessionManager: SessionManager,
  private val currentSessionId: () -> String?
) : CrashReportProcessor.CrashReportSink {
  private val reportedThisRun = mutableSetOf<String>()

  override suspend fun store(sessionId: String?, report: CrashReport): Boolean {
    val current = currentSessionId()
    val target = sessionId ?: sessionManager.getPreviousMainSessionId(current)

    if (target == null || target == current) {
      // Nothing to attribute to, or the process survived (a file carrying the
      // live session id). Handled — don't keep retrying.
      Log.i(TAG, "Dropping a crash report with no attributable prior session.")
      return true
    }
    if (target in reportedThisRun) {
      // A file already stored a (richer) report for this session this run.
      return true
    }
    return runCatching {
      sessionManager.setCrashReport(target, report.encodeToJsonString())
    }.onSuccess {
      reportedThisRun += target
    }.onFailure {
      Log.e(TAG, "Failed to persist a crash report for session $target", it)
    }.isSuccess
  }
}
