package expo.modules.appmetrics.crashreporting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Reads back what [CrashFileWriter] wrote: every test writes a fixture with a real writer and parses
 * it with a [CrashFileReader] over the same directory, so the two stay honest about the shared
 * [CrashFileFormat].
 */
class CrashFileReaderTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private fun writer(directory: File = tmp.root): CrashFileWriter =
    CrashFileWriter(directory).also { it.prepare() }

  private fun reader(directory: File = tmp.root): CrashFileReader = CrashFileReader(directory)

  private fun throwable(message: String? = "boom"): Throwable = IllegalStateException(message)

  // region parse round-trip

  @Test
  fun `round-trips the crash metadata`() {
    writer().write(throwable("boom"), "worker-thread", "session-1", 123, 1_700_000_000_000)

    val pending = reader().listPendingCrashes().single()

    assertEquals("session-1", pending.sessionId)
    assertEquals(123, pending.pid)
    assertEquals(1_700_000_000_000, pending.crashedAtMillis)
    assertEquals("java.lang.IllegalStateException", pending.exceptionClass)
    assertEquals("java.lang.IllegalStateException: boom", pending.composedMessage)
    assertEquals("worker-thread", pending.threadName)
  }

  @Test
  fun `round-trips the cause chain in the composed message`() {
    val wrapped = RuntimeException("wrapper", IllegalStateException("root cause"))
    writer().write(wrapped, "main", "session-1", 123, 1_700_000_000_000)

    val pending = reader().listPendingCrashes().single()

    assertEquals(
      "java.lang.RuntimeException: wrapper\nCaused by: java.lang.IllegalStateException: root cause",
      pending.composedMessage
    )
  }

  @Test
  fun `round-trips the stack frames in order`() {
    writer().write(throwable(), "main", "session-1", 123, 1_700_000_000_000)

    val pending = reader().listPendingCrashes().single()

    assertTrue(pending.stackFrames.isNotEmpty())
    // The crash site (this test class) leads the frames.
    assertTrue(pending.stackFrames.first().contains("CrashFileReaderTest"))
  }

  @Test
  fun `round-trips a null session id`() {
    // Crashes before the session identity exists must still be recorded.
    writer().write(throwable(), "main", sessionId = null, pid = 123, crashedAtMillis = 1_700_000_000_000)

    val pending = reader().listPendingCrashes().single()

    assertNull(pending.sessionId)
  }

  @Test
  fun `round-trips a message-less exception`() {
    writer().write(throwable(message = null), "main", "session-1", 123, 1_700_000_000_000)

    val pending = reader().listPendingCrashes().single()

    assertEquals("java.lang.IllegalStateException", pending.composedMessage)
  }

  @Test
  fun `round-trips messages containing newlines and equals signs`() {
    writer().write(
      throwable("first line\nsecond=line"),
      "main",
      "session-1",
      123,
      1_700_000_000_000
    )

    val pending = reader().listPendingCrashes().single()

    assertEquals(
      "java.lang.IllegalStateException: first line\nsecond=line",
      pending.composedMessage
    )
  }

  @Test
  fun `a multi-line message cannot inject or truncate stack frames`() {
    // Frame lines are written escaped below the separator; the message never
    // appears there, so "\n\tat …" / "\nCaused by:" payloads can't pollute
    // the parsed frames.
    writer().write(
      throwable("first\n\tat com.fake.Injected.method(Fake.java:1)\nCaused by: com.fake.FakeCause"),
      "main",
      "session-1",
      123,
      1_700_000_000_000
    )

    val pending = reader().listPendingCrashes().single()

    assertTrue(pending.stackFrames.first().contains("CrashFileReaderTest"))
    assertTrue(pending.stackFrames.none { it.contains("com.fake.Injected") })
  }

  @Test
  fun `a message line of exactly the header separator does not break parsing`() {
    writer().write(throwable("before\n---\nafter"), "main", "session-1", 123, 1_700_000_000_000)

    val pending = reader().listPendingCrashes().single()

    assertEquals(
      "java.lang.IllegalStateException: before\n---\nafter",
      pending.composedMessage
    )
    assertTrue(pending.stackFrames.first().contains("CrashFileReaderTest"))
  }

  @Test
  fun `the file path and fromThrowable produce the same report content`() {
    // Two builders, one contract: a report normalized from the pending file
    // must match what `fromThrowable` would have produced at crash time.
    val throwable = RuntimeException("boom", IllegalStateException("root"))
    writer().write(throwable, "main", "session-1", 123, 1_700_000_000_000)
    val pending = reader().listPendingCrashes().single()

    val fromFile = pending.toCrashReport(ingestedAt = "2026-06-12T10:05:00.000Z", appVersion = "1.0.0")
    val direct = CrashReport.fromThrowable(
      throwable = throwable,
      crashTimestamp = fromFile.timestampBegin,
      ingestedAt = "2026-06-12T10:05:00.000Z",
      appVersion = "1.0.0"
    )

    assertEquals(direct.exceptionReason, fromFile.exceptionReason)
    assertEquals(direct.callStackTree, fromFile.callStackTree)
  }

  @Test
  fun `keeps separate files for separate crashes`() {
    val writer = writer()
    writer.write(throwable(), "main", "session-1", pid = 123, crashedAtMillis = 1_700_000_000_000)
    writer.write(throwable(), "main", "session-2", pid = 456, crashedAtMillis = 1_700_000_000_500)

    val pending = reader().listPendingCrashes()

    assertEquals(2, pending.size)
    assertEquals(setOf("session-1", "session-2"), pending.map { it.sessionId }.toSet())
  }

  @Test
  fun `a second write with the same pid and timestamp overwrites cleanly`() {
    // Same filename by construction (double-installed handlers in one tick) —
    // last write wins; the file must stay parseable, never corrupt.
    val writer = writer()
    writer.write(throwable("first"), "main", "session-1", pid = 123, crashedAtMillis = 1_700_000_000_000)
    writer.write(throwable("second"), "main", "session-1", pid = 123, crashedAtMillis = 1_700_000_000_000)

    val pending = reader().listPendingCrashes()

    assertEquals(1, pending.size)
    assertEquals("java.lang.IllegalStateException: second", pending.single().composedMessage)
  }

  // endregion

  // region listPendingCrashes hygiene

  @Test
  fun `skips corrupt files without throwing`() {
    writer().write(throwable(), "main", "session-1", 123, 1_700_000_000_000)
    tmp.newFile("crash-999-1700000000001.txt").writeText("complete garbage")

    val pending = reader().listPendingCrashes()

    assertEquals(1, pending.size)
    assertEquals("session-1", pending.single().sessionId)
  }

  @Test
  fun `ignores temp files and unrelated files`() {
    tmp.newFile("crash-1-2.txt.tmp").writeText("partial write")
    tmp.newFile("unrelated.json").writeText("{}")

    assertEquals(emptyList<PendingJvmCrash>(), reader().listPendingCrashes())
  }

  @Test
  fun `returns an empty list when the directory does not exist`() {
    assertEquals(emptyList<PendingJvmCrash>(), reader(File(tmp.root, "missing")).listPendingCrashes())
  }

  @Test
  fun `delete removes the pending crash file`() {
    writer().write(throwable(), "main", "session-1", 123, 1_700_000_000_000)
    val reader = reader()
    val pending = reader.listPendingCrashes().single()

    reader.delete(pending)

    assertEquals(emptyList<PendingJvmCrash>(), reader.listPendingCrashes())
  }

  // endregion
}
