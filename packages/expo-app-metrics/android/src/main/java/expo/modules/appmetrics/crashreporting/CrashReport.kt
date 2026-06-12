package expo.modules.appmetrics.crashreporting

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Structured crash report. Mirrors the cross-platform `CrashReport` TypeScript type
 * (and the iOS Swift struct of the same name) field for field — the JSON payload is
 * the contract between platforms, so the camelCase key names must not drift.
 *
 * The Mach/Unix numeric fields (`exceptionType`, `exceptionCode`, `signal`) are
 * populated only when the OS provides them (native crashes via `ApplicationExitInfo`);
 * JVM crashes carry their details in [exceptionReason] and [callStackTree] instead,
 * because consumers render the numeric fields through `EXC_*`/`SIG*` lookup tables.
 */
@Serializable
data class CrashReport(
  /** Mach exception type — iOS-only, always `null` for reports produced on Android. */
  val exceptionType: Int? = null,
  /** Processor-specific exception code — iOS-only, always `null` on Android. */
  val exceptionCode: Int? = null,
  /** Unix signal number (e.g. SIGSEGV = 11), from `ApplicationExitInfo.getStatus()` for native crashes. */
  val signal: Int? = null,
  /** Human-readable description of the termination, from `ApplicationExitInfo.getDescription()`. */
  val terminationReason: String? = null,
  /** Memory region info for bad-access crashes — iOS-only, always `null` on Android. */
  val virtualMemoryRegionInfo: String? = null,
  /** Exception details. On Android this carries the JVM throwable's class and message. */
  val exceptionReason: ExceptionReason? = null,
  /** Call stack of the crashing thread. */
  val callStackTree: CallStackTree? = null,
  /** App version at the time of the crash. */
  val appVersion: String,
  /** Crash window start. Android knows the exact crash moment, so begin == end. */
  val timestampBegin: String,
  /** Crash window end. Android knows the exact crash moment, so begin == end. */
  val timestampEnd: String,
  /**
   * When this device processed the crash and constructed the report — the next
   * launch after the crash, not the crash moment itself.
   */
  val ingestedAt: String
) {
  /**
   * Exception details. The field names come from MetricKit's ObjC exception reason;
   * on Android `exceptionType`/`className` carry the fully-qualified throwable class
   * and `composedMessage` the `Throwable.toString()` line (class + message).
   */
  @Serializable
  data class ExceptionReason(
    val composedMessage: String,
    val formatString: String,
    val arguments: List<String>,
    val exceptionType: String,
    val className: String,
    val exceptionName: String
  )

  /**
   * Mirrors the shape of MetricKit's `MXCallStackTree` JSON. Android fills only
   * `threadAttributed`, `callStackRootFrames`, and `Frame.symbol`; the
   * binary/address fields are an iOS concern (see [CallStackTreeBuilder]).
   */
  @Serializable
  data class CallStackTree(
    val callStacks: List<CallStack>? = null
  ) {
    @Serializable
    data class CallStack(
      val threadAttributed: Boolean? = null,
      val callStackRootFrames: List<Frame>? = null
    )

    @Serializable
    data class Frame(
      val binaryName: String? = null,
      val binaryUUID: String? = null,
      // ULong, not Long: iOS encodes these as UInt64 and arm64 addresses routinely
      // have the high bit set — a signed Long would fail to decode the whole report.
      val address: ULong? = null,
      val offsetIntoBinaryTextSegment: ULong? = null,
      val sampleCount: Int? = null,
      val subFrames: List<Frame>? = null,
      val symbol: String? = null
    )
  }

  fun encodeToJsonString(): String = json.encodeToString(this)

  companion object {
    // `explicitNulls = false` matches iOS's JSONEncoder, which drops nil Optionals —
    // consumers test for field absence. `ignoreUnknownKeys` keeps decoding tolerant
    // of fields added by either platform later.
    private val json = Json {
      ignoreUnknownKeys = true
      explicitNulls = false
    }

    fun decodeFromJsonString(payload: String): CrashReport? =
      runCatching { json.decodeFromString<CrashReport>(payload) }.getOrNull()

    /**
     * Builds a report from a JVM throwable caught by the uncaught-exception handler.
     * `crashTimestamp` is the crash moment (used as a zero-width window); `ingestedAt`
     * is when the report was assembled on the next launch.
     */
    fun fromThrowable(
      throwable: Throwable,
      crashTimestamp: String,
      ingestedAt: String,
      appVersion: String
    ): CrashReport =
      CrashReport(
        exceptionReason = exceptionReason(
          exceptionClass = throwable.javaClass.name,
          composedMessage = composeMessage(throwable)
        ),
        callStackTree = CallStackTreeBuilder.fromStackTrace(throwable.stackTrace),
        appVersion = appVersion,
        timestampBegin = crashTimestamp,
        timestampEnd = crashTimestamp,
        ingestedAt = ingestedAt
      )

    /**
     * Builds the [ExceptionReason] for a JVM crash from its class name and
     * composed message. Shared by [fromThrowable] and the pending-crash-file
     * path, which only has the strings (the throwable itself died with the
     * previous process).
     */
    fun exceptionReason(exceptionClass: String, composedMessage: String): ExceptionReason =
      ExceptionReason(
        composedMessage = composedMessage,
        formatString = "",
        arguments = emptyList(),
        exceptionType = exceptionClass,
        className = exceptionClass,
        exceptionName = exceptionClass.substringAfterLast('.').substringAfterLast('$')
      )

    /**
     * `Throwable.toString()` plus the cause chain — the root cause is usually
     * the diagnostic that matters, and `toString()` alone drops it. Mirrors the
     * `Caused by:` lines of `printStackTrace`. Depth-capped defensively against
     * cyclic cause chains.
     */
    fun composeMessage(throwable: Throwable): String {
      val message = StringBuilder(throwable.toString())
      var cause = throwable.cause
      var depth = 0
      while (cause != null && depth < MAX_CAUSE_DEPTH) {
        message.append("\nCaused by: ").append(cause.toString())
        cause = cause.cause
        depth++
      }
      return message.toString()
    }

    private const val MAX_CAUSE_DEPTH = 5
  }
}
