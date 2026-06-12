package expo.modules.appmetrics.crashreporting

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import expo.modules.appmetrics.storage.MetricsDatabase
import expo.modules.appmetrics.storage.SessionManager
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SessionCrashReportSinkTest {
  private lateinit var database: MetricsDatabase
  private lateinit var sessionManager: SessionManager

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    database = Room
      .inMemoryDatabaseBuilder(context, MetricsDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    sessionManager = SessionManager(context, database)
  }

  @After
  fun tearDown() {
    database.close()
  }

  private fun sink(currentSessionId: String? = "current"): SessionCrashReportSink =
    SessionCrashReportSink(sessionManager) { currentSessionId }

  private fun report(message: String = "boom"): CrashReport =
    CrashReport.fromThrowable(
      throwable = IllegalStateException(message),
      crashTimestamp = "2026-06-12T10:00:00.000Z",
      ingestedAt = "2026-06-12T10:05:00.000Z",
      appVersion = "1.0.0"
    )

  private suspend fun storedMessage(sessionId: String): String? =
    sessionManager.getCrashReport(sessionId)
      ?.let { CrashReport.decodeFromJsonString(it) }
      ?.exceptionReason
      ?.composedMessage

  // region Embedded id (JVM crash files)

  @Test
  fun `stores a report under its embedded session id`() =
    runTest {
      val handled = sink().store("crashed-session", report("from file"))

      assertTrue(handled)
      assertEquals("java.lang.IllegalStateException: from file", storedMessage("crashed-session"))
    }

  @Test
  fun `does not require the embedded session row to exist`() =
    runTest {
      // A startup crash can predate the session-row persist; the FK-less table
      // still stores it.
      assertTrue(sink().store("never-persisted", report()))

      assertEquals("java.lang.IllegalStateException: boom", storedMessage("never-persisted"))
    }

  // endregion

  // region No id (native crashes / lost files) → previous main session

  @Test
  fun `attributes an id-less report to the previous main session`() =
    runTest {
      sessionManager.startSessionWithIdAt("older", "2023-11-14T20:00:00.000Z")
      sessionManager.startSessionWithIdAt("previous", "2023-11-14T22:00:00.000Z")

      val handled = sink(currentSessionId = "current").store(null, report("native"))

      assertTrue(handled)
      assertEquals("java.lang.IllegalStateException: native", storedMessage("previous"))
      assertNull(sessionManager.getCrashReport("older"))
    }

  @Test
  fun `drops an id-less report when only the current session exists`() =
    runTest {
      sessionManager.startSessionWithIdAt("current", "2023-11-14T22:00:00.000Z")

      val handled = sink(currentSessionId = "current").store(null, report())

      // Handled (won't retry forever) but nothing stored — we never blame the live session.
      assertTrue(handled)
      assertNull(sessionManager.getCrashReport("current"))
    }

  @Test
  fun `drops an id-less report when there are no sessions at all`() =
    runTest {
      assertTrue(sink(currentSessionId = "current").store(null, report()))

      assertNull(sessionManager.getCrashReport("current"))
    }

  @Test
  fun `drops a report whose embedded id is the current session`() =
    runTest {
      // Defensive: a file carrying the live session id means the process survived.
      val handled = sink(currentSessionId = "current").store("current", report())

      assertTrue(handled)
      assertNull(sessionManager.getCrashReport("current"))
    }

  // endregion

  // region File wins over a bare record within one run

  @Test
  fun `a later id-less report does not overwrite a file report for the same session`() =
    runTest {
      // "previous" is the previous main session; a file already stored its rich
      // report there this run, so a bare record resolving to "previous" is skipped.
      sessionManager.startSessionWithIdAt("previous", "2023-11-14T22:00:00.000Z")
      val sink = sink(currentSessionId = "current")

      assertTrue(sink.store("previous", report("from file")))
      assertTrue(sink.store(null, report("from record")))

      assertEquals("java.lang.IllegalStateException: from file", storedMessage("previous"))
    }

  // endregion

  // region Failure

  @Test
  fun `returns false when the database write fails`() =
    runTest {
      database.close()

      assertFalse(sink().store("crashed-session", report()))
    }

  // endregion
}
