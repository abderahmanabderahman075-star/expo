package expo.modules.appmetrics.crashreporting

/**
 * Maps a JVM stack trace into the cross-platform [CrashReport.CallStackTree] shape.
 *
 * Frames go into `callStackRootFrames` as a flat list in `printStackTrace` order
 * (crash site first) with `subFrames` left null — the same shape iOS's simulated
 * reports use, and consumers flatten nested `subFrames` depth-first anyway, so the
 * two shapes render identically. A flat list also keeps huge `StackOverflowError`
 * stacks from producing pathologically deep JSON nesting.
 *
 * Only `symbol` is populated per frame: JVM stacks are already symbolic
 * (`com.example.Foo.bar(Foo.kt:42)`), and the binary/address fields describe
 * machine code locations that have no JVM equivalent.
 */
object CallStackTreeBuilder {
  /**
   * Frame cap per stack. `StackOverflowError` traces routinely reach the JVM's
   * 1024-element limit; beyond this cap the repeating tail adds payload size
   * without diagnostic value, so it's replaced by a marker frame.
   */
  const val MAX_FRAMES = 256

  fun fromStackTrace(stackTrace: Array<StackTraceElement>): CrashReport.CallStackTree =
    fromSymbols(stackTrace.map { it.toString() })

  /** Builds the tree from already-formatted frame strings (the pending-crash-file path). */
  fun fromSymbols(symbols: List<String>): CrashReport.CallStackTree {
    val frames = symbols.take(MAX_FRAMES).map { symbol ->
      CrashReport.CallStackTree.Frame(symbol = symbol)
    }
    val truncatedCount = symbols.size - MAX_FRAMES
    val allFrames = if (truncatedCount > 0) {
      frames + CrashReport.CallStackTree.Frame(symbol = "… $truncatedCount more frames")
    } else {
      frames
    }
    return CrashReport.CallStackTree(
      callStacks = listOf(
        CrashReport.CallStackTree.CallStack(
          threadAttributed = true,
          callStackRootFrames = allFrames
        )
      )
    )
  }
}
