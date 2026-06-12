package expo.modules.appmetrics.crashreporting

import android.content.Context
import android.util.Log
import expo.modules.appmetrics.AppMetricsPreferences
import expo.modules.appmetrics.TAG
import expo.modules.appmetrics.utils.TimeUtils

/** Persistence seam for the set of already-processed exit-record keys. */
interface ProcessedExitRecordsStore {
  fun getProcessedKeys(): Set<String>

  fun setProcessedKeys(keys: Set<String>)
}

/** SharedPreferences-backed store, see [AppMetricsPreferences]. */
class PreferencesProcessedExitRecordsStore(private val context: Context) : ProcessedExitRecordsStore {
  override fun getProcessedKeys(): Set<String> = AppMetricsPreferences.getProcessedExitRecordKeys(context)

  override fun setProcessedKeys(keys: Set<String>) {
    AppMetricsPreferences.setProcessedExitRecordKeys(context, keys)
  }
}

/**
 * Next-launch crash processing: turns the previous process's death evidence —
 * pending JVM crash files from [JvmCrashHandler] and OS exit records from
 * [ExitInfoProvider] — into [CrashReport]s, and hands each to [crashReportSink].
 *
 * The processor is intentionally **session-agnostic**: it knows how to read and
 * corroborate crash evidence, but not how reports map to sessions. The sink
 * (owned by the module, which owns sessions) decides attribution and storage.
 *
 * Policy (each point deliberate, see the ENG-21535 plan):
 * - **Debuggable builds:** a crash file is promoted only when corroborated by a
 *   matching death record — the dev red box / dev launcher can catch an
 *   exception without the process dying, and an uncorroborated file would
 *   fabricate a crash. Uncorroborated files are discarded.
 * - **Release builds:** the file is promoted on its own evidence. There is no
 *   red box in release, and requiring corroboration would silently drop real
 *   crashes whenever the small exit-record ring buffer evicts the death record.
 * - **Dedup:** a single crash seen as both a file and an exit record yields one
 *   report — the file's (it has the stack); its record is consumed. Files are
 *   handed to the sink before bare records so the file's richer report wins.
 * - **At-least-once:** the processed-record cursor is saved only after the sink
 *   has handled every report; a record whose sink call fails is left out of the
 *   cursor and retried next launch (the sink's storage is idempotent).
 */
class CrashReportProcessor(
  private val crashFileReader: CrashFileReader,
  private val exitInfoProvider: ExitInfoProvider,
  private val processedRecordsStore: ProcessedExitRecordsStore,
  private val isDebuggableBuild: Boolean,
  private val appVersion: String?,
  private val crashReportSink: CrashReportSink
) {
  /**
   * Receives each crash report the processor builds. `sessionId` is the id
   * embedded in a JVM crash file (the process that crashed), or `null` for
   * native crashes and lost-file records — the implementation decides how to
   * attribute and persist those. Returns `true` when the report was handled
   * (stored or intentionally dropped) so its file/record can be cleared;
   * `false` on a transient failure so it is retried on the next launch.
   */
  fun interface CrashReportSink {
    suspend fun store(sessionId: String?, report: CrashReport): Boolean
  }

  suspend fun process() {
    val allRecords = exitInfoProvider.getExitRecords()
    val processedKeys = processedRecordsStore.getProcessedKeys()
    val newRecords = allRecords.filter { it.key !in processedKeys }
    val pendingFiles = crashFileReader.listPendingCrashes()

    val failedRecordKeys = if (newRecords.isNotEmpty() || pendingFiles.isNotEmpty()) {
      processCrashes(newRecords, pendingFiles)
    } else {
      emptySet()
    }

    // The cursor is exactly the keys still present in the OS buffer — records
    // that fell out can never be returned again, so the set stays bounded.
    // Saved after the sink has run, minus keys whose handling failed (they
    // retry next launch): at-least-once, never at-most-once.
    processedRecordsStore.setProcessedKeys(
      allRecords.map { it.key }.toSet() - failedRecordKeys
    )
  }

  /** Returns the keys of exit records the sink failed to handle. */
  private suspend fun processCrashes(
    newRecords: List<ExitRecord>,
    pendingFiles: List<PendingJvmCrash>
  ): Set<String> {
    val resolvedAppVersion = appVersion ?: "unknown"
    val ingestedAt = TimeUtils.getCurrentTimestampInISOFormat()
    val consumedRecordKeys = mutableSetOf<String>()
    val failedRecordKeys = mutableSetOf<String>()

    for (file in pendingFiles) {
      val corroborating = newRecords.firstOrNull { record ->
        record.key !in consumedRecordKeys && record.isDeathRecord && record.matches(file)
      }
      corroborating?.let { consumedRecordKeys += it.key }

      if (isDebuggableBuild && corroborating == null) {
        Log.i(TAG, "Discarding a pending crash file without a matching process death — the exception was likely caught by the dev tooling without killing the app.")
        crashFileReader.delete(file)
        continue
      }

      val handled = crashReportSink.store(
        file.sessionId,
        file.toCrashReport(ingestedAt, resolvedAppVersion)
      )
      if (handled) {
        crashFileReader.delete(file)
      } else {
        // Keep the file; the next launch retries (the sink's storage is idempotent).
        Log.e(TAG, "Failed to handle a crash report; keeping its pending file for retry")
      }
    }

    for (record in newRecords) {
      if (record.key in consumedRecordKeys || !record.isStandaloneCrash) {
        continue
      }
      val handled = crashReportSink.store(
        null,
        record.toCrashReport(ingestedAt, resolvedAppVersion)
      )
      if (!handled) {
        Log.e(TAG, "Failed to handle a crash report from an exit record; it will retry next launch")
        failedRecordKeys += record.key
      }
    }
    return failedRecordKeys
  }
}
