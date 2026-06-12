package expo.modules.appmetrics

import java.util.UUID

/**
 * The main session's identity, established at process start — *before* the
 * module system spins up. `AppMetricsModule.OnCreate` runs lazily (only when JS
 * first touches the module), so anything crash-related that waits for it misses
 * startup crashes entirely. Instead the application lifecycle listener calls
 * [initialize] in `Application.onCreate`, and the module later adopts this id
 * for its main session row. It is also the session id embedded in pending JVM
 * crash files, so a crash captured before the module exists still attributes to
 * this session.
 */
object ProcessIdentity {
  /** The per-process session id, or `null` before [initialize] ran. */
  @Volatile
  var sessionId: String? = null
    private set

  /** Generates (once per process) and returns the session id. Thread-safe and idempotent. */
  fun initialize(): String {
    sessionId?.let { return it }
    synchronized(this) {
      sessionId?.let { return it }
      val id = UUID.randomUUID().toString()
      sessionId = id
      return id
    }
  }

  internal fun resetForTesting() {
    sessionId = null
  }
}
