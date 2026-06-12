package expo.modules.appmetrics.crashreporting

import android.os.Process

/**
 * Chained default uncaught-exception handler — the only source of Java stack
 * traces on Android (`ApplicationExitInfo`'s `REASON_CRASH` records carry no
 * trace). Writes one pending-crash file via [CrashFileWriter], then always
 * delegates to whatever handler was installed before it (React Native's,
 * Sentry's, the system's), so the process still dies and other reporters keep
 * working.
 *
 * Whether the file becomes a stored crash report is decided on the next launch
 * by `CrashReportProcessor` — in debuggable builds an exception can be caught
 * by the red box / dev launcher without killing the process, so the file alone
 * is not proof of a crash.
 */
class JvmCrashHandler internal constructor(
  private val fileWriter: CrashFileWriter,
  private val sessionIdProvider: () -> String?,
  private val previousHandler: Thread.UncaughtExceptionHandler?,
  private val pidProvider: () -> Int = { Process.myPid() },
  private val clock: () -> Long = { System.currentTimeMillis() }
) : Thread.UncaughtExceptionHandler {
  override fun uncaughtException(thread: Thread, throwable: Throwable) {
    try {
      fileWriter.write(
        throwable = throwable,
        threadName = thread.name,
        sessionId = runCatching { sessionIdProvider() }.getOrNull(),
        pid = pidProvider(),
        crashedAtMillis = clock()
      )
    } catch (_: Throwable) {
      // Nothing on the capture path may interfere with the chain below.
    } finally {
      previousHandler?.uncaughtException(thread, throwable)
    }
  }

  companion object {
    // Process-wide install marker. The `current is JvmCrashHandler` check alone
    // is not enough: another SDK installing after us makes us its previous
    // handler, and a later re-install here would then add a second instance to
    // the chain, double-recording every crash.
    private val installed = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Installs the handler in front of the current default handler. Idempotent
     * per process: repeated calls (re-created module, multiple init paths)
     * leave the existing chain untouched.
     */
    fun install(fileWriter: CrashFileWriter, sessionIdProvider: () -> String?) {
      if (!installed.compareAndSet(false, true)) {
        return
      }
      val current = Thread.getDefaultUncaughtExceptionHandler()
      Thread.setDefaultUncaughtExceptionHandler(
        JvmCrashHandler(fileWriter, sessionIdProvider, current)
      )
    }

    internal fun resetForTesting() {
      installed.set(false)
    }
  }
}
