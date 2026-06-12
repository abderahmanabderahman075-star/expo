package expo.modules.appmetrics.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import expo.modules.appmetrics.utils.TimeUtils
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CrashReportStorageTest {
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

  private suspend fun startSession(
    id: String,
    startTimestamp: String = "2025-01-15T10:30:00.000Z"
  ) {
    sessionManager.startSessionWithIdAt(id, startTimestamp)
  }

  // region DAO

  @Test
  fun `stores and reads back a crash report payload`() =
    runTest {
      startSession("session-1")

      sessionManager.setCrashReport("session-1", """{"appVersion":"1.0.0"}""")

      assertEquals("""{"appVersion":"1.0.0"}""", sessionManager.getCrashReport("session-1"))
    }

  @Test
  fun `replaces a previously stored report for the same session`() =
    runTest {
      startSession("session-1")

      sessionManager.setCrashReport("session-1", """{"appVersion":"1.0.0"}""")
      sessionManager.setCrashReport("session-1", """{"appVersion":"2.0.0"}""")

      assertEquals("""{"appVersion":"2.0.0"}""", sessionManager.getCrashReport("session-1"))
    }

  @Test
  fun `stores an orphan crash report whose session row was never written`() =
    runTest {
      // A startup crash can fire before the session row reaches disk — the
      // table deliberately has no foreign key so the report still lands.
      sessionManager.setCrashReport("never-persisted", """{"appVersion":"1.0.0"}""")

      assertEquals("""{"appVersion":"1.0.0"}""", sessionManager.getCrashReport("never-persisted"))
    }

  @Test
  fun `returns null for a session without a crash report`() =
    runTest {
      startSession("session-1")

      assertNull(sessionManager.getCrashReport("session-1"))
    }

  // endregion

  // region getInactiveSessionsWithCrashReports

  @Test
  fun `attaches crash payloads to their sessions`() =
    runTest {
      startSession("crashed", startTimestamp = "2025-01-15T10:00:00.000Z")
      startSession("clean", startTimestamp = "2025-01-15T11:00:00.000Z")
      sessionManager.stopSession("crashed")
      sessionManager.stopSession("clean")
      sessionManager.setCrashReport("crashed", """{"appVersion":"1.0.0"}""")

      val sessions = sessionManager.getInactiveSessionsWithCrashReports()

      val crashed = sessions.first { it.sessionWithMetrics.session.id == "crashed" }
      val clean = sessions.first { it.sessionWithMetrics.session.id == "clean" }
      assertEquals("""{"appVersion":"1.0.0"}""", crashed.crashReportPayload)
      assertNull(clean.crashReportPayload)
    }

  @Test
  fun `keeps the inactive-session ordering of getInactiveSessions`() =
    runTest {
      startSession("older", startTimestamp = "2025-01-15T10:00:00.000Z")
      startSession("newer", startTimestamp = "2025-01-15T11:00:00.000Z")
      sessionManager.stopSession("older")
      sessionManager.stopSession("newer")

      val sessions = sessionManager.getInactiveSessionsWithCrashReports()

      assertEquals(
        listOf("newer", "older"),
        sessions.map { it.sessionWithMetrics.session.id }
      )
    }

  @Test
  fun `excludes active sessions`() =
    runTest {
      startSession("active")
      sessionManager.setCrashReport("active", """{"appVersion":"1.0.0"}""")

      val sessions = sessionManager.getInactiveSessionsWithCrashReports()

      assertEquals(emptyList<SessionWithChildren>(), sessions)
    }

  // endregion

  // region Pruning

  @Test
  fun `cleanupOldSessions removes crash reports of pruned sessions`() =
    runTest {
      val oldTimestamp = "2020-01-01T00:00:00.000Z"
      startSession("ancient", startTimestamp = oldTimestamp)
      sessionManager.stopSession("ancient")
      sessionManager.setCrashReport("ancient", """{"appVersion":"1.0.0"}""")

      sessionManager.cleanupOldSessions()

      assertNull(sessionManager.getCrashReport("ancient"))
    }

  @Test
  fun `cleanupOldSessions keeps the crash report of an old but still-active session`() =
    runTest {
      // `deleteSessionsOlderThan` protects live sessions; their crash reports
      // must be protected the same way (and must not be aged out as orphans —
      // the session row exists).
      startSession("long-lived", startTimestamp = "2020-01-01T00:00:00.000Z")
      sessionManager.setCrashReport(
        "long-lived",
        """{"appVersion":"1.0.0"}""",
        createdAt = "2020-01-01T00:00:00.000Z"
      )

      sessionManager.cleanupOldSessions()

      assertEquals("""{"appVersion":"1.0.0"}""", sessionManager.getCrashReport("long-lived"))
    }

  @Test
  fun `cleanupOldSessions keeps crash reports of recent sessions`() =
    runTest {
      startSession("recent", startTimestamp = TimeUtils.getCurrentTimestampInISOFormat())
      sessionManager.stopSession("recent")
      sessionManager.setCrashReport("recent", """{"appVersion":"1.0.0"}""")

      sessionManager.cleanupOldSessions()

      assertEquals("""{"appVersion":"1.0.0"}""", sessionManager.getCrashReport("recent"))
    }

  @Test
  fun `cleanupOldSessions removes orphan crash reports past the retention window`() =
    runTest {
      sessionManager.setCrashReport(
        "orphan",
        """{"appVersion":"1.0.0"}""",
        createdAt = "2020-01-01T00:00:00.000Z"
      )

      sessionManager.cleanupOldSessions()

      assertNull(sessionManager.getCrashReport("orphan"))
    }

  @Test
  fun `cleanupOldSessions keeps recent orphan crash reports`() =
    runTest {
      // An orphan whose session row hasn't landed (yet) must survive pruning
      // until the retention window passes.
      sessionManager.setCrashReport("fresh-orphan", """{"appVersion":"1.0.0"}""")

      sessionManager.cleanupOldSessions()

      assertEquals("""{"appVersion":"1.0.0"}""", sessionManager.getCrashReport("fresh-orphan"))
    }

  @Test
  fun `clearAllData wipes crash reports`() =
    runTest {
      startSession("session-1")
      sessionManager.setCrashReport("session-1", """{"appVersion":"1.0.0"}""")
      sessionManager.setCrashReport("orphan", """{"appVersion":"1.0.0"}""")

      sessionManager.clearAllData()

      assertNull(sessionManager.getCrashReport("session-1"))
      assertNull(sessionManager.getCrashReport("orphan"))
    }

  // endregion
}
