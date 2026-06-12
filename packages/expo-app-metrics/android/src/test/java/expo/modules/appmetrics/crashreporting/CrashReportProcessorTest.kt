package expo.modules.appmetrics.crashreporting

import android.app.ApplicationExitInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The processor is session-agnostic: it builds reports from crash evidence and
 * hands each to a [CrashReportProcessor.CrashReportSink]. These tests assert on
 * the sink invocations (and file/cursor bookkeeping); session attribution and
 * storage live in [SessionCrashReportSink] and are tested separately.
 *
 * Robolectric only for `android.util.Log`; there is no database here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CrashReportProcessorTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private lateinit var crashFileWriter: CrashFileWriter
  private lateinit var crashFileReader: CrashFileReader

  private val exitRecords = mutableListOf<ExitRecord>()
  private val processedKeys = mutableSetOf<String>()

  private val exitInfoProvider = ExitInfoProvider { exitRecords.toList() }

  private val processedRecordsStore = object : ProcessedExitRecordsStore {
    override fun getProcessedKeys(): Set<String> = processedKeys.toSet()

    override fun setProcessedKeys(keys: Set<String>) {
      processedKeys.clear()
      processedKeys.addAll(keys)
    }
  }

  data class SinkCall(val sessionId: String?, val report: CrashReport)

  private val sinkCalls = mutableListOf<SinkCall>()

  /** Override to simulate a transient sink failure for a given call. */
  private var sinkHandles: (SinkCall) -> Boolean = { true }

  private val sink = CrashReportProcessor.CrashReportSink { sessionId, report ->
    val call = SinkCall(sessionId, report)
    sinkCalls += call
    sinkHandles(call)
  }

  @Before
  fun setUp() {
    crashFileWriter = CrashFileWriter(tmp.root).also { it.prepare() }
    crashFileReader = CrashFileReader(tmp.root)
  }

  private fun processor(isDebuggableBuild: Boolean = false): CrashReportProcessor =
    CrashReportProcessor(
      crashFileReader = crashFileReader,
      exitInfoProvider = exitInfoProvider,
      processedRecordsStore = processedRecordsStore,
      isDebuggableBuild = isDebuggableBuild,
      appVersion = "1.2.3",
      crashReportSink = sink
    )

  private fun writeCrashFile(
    sessionId: String? = "crashed-session",
    pid: Int = 123,
    crashedAtMillis: Long = 1_700_000_000_000
  ) {
    crashFileWriter.write(
      throwable = IllegalStateException("boom"),
      threadName = "main",
      sessionId = sessionId,
      pid = pid,
      crashedAtMillis = crashedAtMillis
    )
  }

  private fun exitRecord(
    reason: Int = ApplicationExitInfo.REASON_CRASH,
    status: Int = 0,
    timestampMillis: Long = 1_700_000_000_500,
    pid: Int = 123,
    description: String? = null
  ): ExitRecord = ExitRecord(
    reason = reason,
    status = status,
    description = description,
    timestampMillis = timestampMillis,
    pid = pid
  )

  // region JVM crash files

  @Test
  fun `release hands a crash file to the sink with its embedded session id`() =
    runTest {
      writeCrashFile(sessionId = "crashed-session")

      processor(isDebuggableBuild = false).process()

      val call = sinkCalls.single()
      assertEquals("crashed-session", call.sessionId)
      assertEquals("java.lang.IllegalStateException: boom", call.report.exceptionReason?.composedMessage)
      assertEquals("1.2.3", call.report.appVersion)
    }

  @Test
  fun `deletes the crash file once the sink handles it`() =
    runTest {
      writeCrashFile()

      processor(isDebuggableBuild = false).process()

      assertEquals(emptyList<PendingJvmCrash>(), crashFileReader.listPendingCrashes())
    }

  @Test
  fun `keeps the crash file for retry when the sink reports failure`() =
    runTest {
      writeCrashFile()
      sinkHandles = { false }

      processor(isDebuggableBuild = false).process()

      assertEquals(1, crashFileReader.listPendingCrashes().size)
    }

  // endregion

  // region Debuggable builds — red-box false-positive guard

  @Test
  fun `debug discards an uncorroborated crash file without calling the sink`() =
    runTest {
      // The dev red box / dev launcher can catch an exception without process
      // death — without a matching exit record the file is not a crash.
      writeCrashFile()

      processor(isDebuggableBuild = true).process()

      assertTrue(sinkCalls.isEmpty())
      assertEquals(emptyList<PendingJvmCrash>(), crashFileReader.listPendingCrashes())
    }

  @Test
  fun `debug promotes a crash file corroborated by pid and time proximity`() =
    runTest {
      writeCrashFile(sessionId = "crashed-session", pid = 123, crashedAtMillis = 1_700_000_000_000)
      exitRecords += exitRecord(pid = 123, timestampMillis = 1_700_000_002_000)

      processor(isDebuggableBuild = true).process()

      assertEquals("crashed-session", sinkCalls.single().sessionId)
    }

  @Test
  fun `debug promotes a crash file corroborated by a signaled death`() =
    runTest {
      writeCrashFile(sessionId = "crashed-session")
      exitRecords += exitRecord(reason = ApplicationExitInfo.REASON_SIGNALED, status = 9)

      processor(isDebuggableBuild = true).process()

      assertEquals("crashed-session", sinkCalls.single().sessionId)
    }

  @Test
  fun `debug does not corroborate with a non-death exit record`() =
    runTest {
      writeCrashFile()
      exitRecords += exitRecord(reason = ApplicationExitInfo.REASON_USER_REQUESTED)

      processor(isDebuggableBuild = true).process()

      assertTrue(sinkCalls.isEmpty())
    }

  @Test
  fun `debug does not corroborate with a record outside the pid time window`() =
    runTest {
      // Pids get reused — same pid but 6 minutes apart is a different death, so
      // the file stays uncorroborated (discarded in debug) and the record
      // surfaces on its own as a bare report.
      writeCrashFile(sessionId = "crashed-session", pid = 123, crashedAtMillis = 1_700_000_000_000)
      exitRecords += exitRecord(pid = 123, timestampMillis = 1_700_000_000_000 + 6 * 60 * 1000)

      processor(isDebuggableBuild = true).process()

      assertEquals(listOf<String?>(null), sinkCalls.map { it.sessionId })
    }

  @Test
  fun `debug does not corroborate with a record of a different pid`() =
    runTest {
      // Different pid → the record doesn't vouch for the file: the file is
      // discarded (debug) and the record surfaces on its own.
      writeCrashFile(sessionId = "crashed-session", pid = 123)
      exitRecords += exitRecord(pid = 456)

      processor(isDebuggableBuild = true).process()

      assertEquals(listOf<String?>(null), sinkCalls.map { it.sessionId })
    }

  // endregion

  // region Dedup between files and records

  @Test
  fun `a corroborating record is consumed and not delivered as its own report`() =
    runTest {
      writeCrashFile(sessionId = "crashed-session", pid = 123, crashedAtMillis = 1_700_000_000_000)
      exitRecords += exitRecord(pid = 123, timestampMillis = 1_700_000_000_500, description = "bare AEI description")

      processor(isDebuggableBuild = true).process()

      // One report — the file's, carrying its rich exceptionReason — not two.
      val call = sinkCalls.single()
      assertEquals("crashed-session", call.sessionId)
      assertEquals("java.lang.IllegalStateException", call.report.exceptionReason?.exceptionType)
      assertNull(call.report.terminationReason)
    }

  @Test
  fun `a record corroborates only one file`() =
    runTest {
      // Two crash-burst files from different pids; the single death record
      // vouches for one only — the other stands on its own evidence (release).
      writeCrashFile(sessionId = "first", pid = 123, crashedAtMillis = 1_700_000_000_000)
      writeCrashFile(sessionId = "second", pid = 456, crashedAtMillis = 1_700_000_100_000)
      exitRecords += exitRecord(pid = 123, timestampMillis = 1_700_000_001_000)

      processor(isDebuggableBuild = false).process()

      // Both files promoted (release); the record corroborated only "first" and
      // was consumed, so it produced no extra (null) report.
      assertEquals(setOf("first", "second"), sinkCalls.map { it.sessionId }.toSet())
      assertEquals(2, sinkCalls.size)
    }

  @Test
  fun `hands files to the sink before bare records`() =
    runTest {
      // The sink relies on this order so a file's richer report wins over a bare
      // record that resolves to the same session.
      writeCrashFile(sessionId = "from-file", pid = 123, crashedAtMillis = 1_700_000_000_000)
      exitRecords += exitRecord(reason = ApplicationExitInfo.REASON_CRASH_NATIVE, status = 11, pid = 456)

      processor(isDebuggableBuild = false).process()

      assertEquals(listOf("from-file", null), sinkCalls.map { it.sessionId })
    }

  // endregion

  // region Bare exit records

  @Test
  fun `hands a native crash to the sink with no session id and a signal`() =
    runTest {
      exitRecords += exitRecord(
        reason = ApplicationExitInfo.REASON_CRASH_NATIVE,
        status = 11,
        description = "Native crash in libhermes"
      )

      processor().process()

      val call = sinkCalls.single()
      assertNull(call.sessionId)
      assertEquals(11, call.report.signal)
      assertEquals("Native crash in libhermes", call.report.terminationReason)
      assertNull(call.report.exceptionReason)
      assertEquals("1.2.3", call.report.appVersion)
    }

  @Test
  fun `hands a lost-file JVM crash to the sink with no session id and no signal`() =
    runTest {
      exitRecords += exitRecord(reason = ApplicationExitInfo.REASON_CRASH)

      processor().process()

      val call = sinkCalls.single()
      assertNull(call.sessionId)
      // A Java crash's status is an exit code, not a signal — must stay null.
      assertNull(call.report.signal)
    }

  @Test
  fun `ignores exit reasons outside the crash allowlist`() =
    runTest {
      exitRecords += exitRecord(reason = ApplicationExitInfo.REASON_ANR)
      exitRecords += exitRecord(reason = ApplicationExitInfo.REASON_LOW_MEMORY)
      exitRecords += exitRecord(reason = ApplicationExitInfo.REASON_SIGNALED)
      exitRecords += exitRecord(reason = ApplicationExitInfo.REASON_USER_REQUESTED)

      processor().process()

      assertTrue(sinkCalls.isEmpty())
    }

  // endregion

  // region Cursor (at-least-once, no reprocessing)

  @Test
  fun `advances the cursor for a handled record`() =
    runTest {
      val record = exitRecord(reason = ApplicationExitInfo.REASON_CRASH)
      exitRecords += record

      processor().process()

      assertEquals(setOf(record.key), processedKeys)
    }

  @Test
  fun `does not advance the cursor for a record the sink failed to handle`() =
    runTest {
      exitRecords += exitRecord(reason = ApplicationExitInfo.REASON_CRASH)
      sinkHandles = { false }

      processor().process()

      // Left out of the cursor so the next launch retries it.
      assertTrue(processedKeys.isEmpty())
    }

  @Test
  fun `does not reprocess exit records on later runs`() =
    runTest {
      exitRecords += exitRecord(reason = ApplicationExitInfo.REASON_CRASH)
      processor().process()
      sinkCalls.clear()

      processor().process()

      assertTrue(sinkCalls.isEmpty())
    }

  @Test
  fun `keeps the processed-record set bounded to the current buffer`() =
    runTest {
      processedKeys += "9999999:1:4" // a record long evicted from the AEI buffer
      val record = exitRecord(reason = ApplicationExitInfo.REASON_CRASH)
      exitRecords += record

      processor().process()

      // Only the records currently in the buffer remain tracked.
      assertEquals(setOf(record.key), processedKeys)
    }

  // endregion
}
