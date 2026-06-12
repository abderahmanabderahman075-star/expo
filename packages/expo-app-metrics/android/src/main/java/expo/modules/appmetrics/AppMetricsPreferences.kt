package expo.modules.appmetrics

import android.content.Context
import androidx.core.content.edit

private const val PREFS_NAME = "dev.expo.app-metrics"
private const val KEY_ENVIRONMENT = "environment"
private const val KEY_PROCESSED_EXIT_RECORDS = "processedExitRecords"

object AppMetricsPreferences {
  /** Keys of `ApplicationExitInfo` records already turned into crash reports. */
  fun getProcessedExitRecordKeys(context: Context): Set<String> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    // Copy: the set returned by `getStringSet` must not be mutated or reused.
    return prefs.getStringSet(KEY_PROCESSED_EXIT_RECORDS, null)?.toSet() ?: emptySet()
  }

  fun setProcessedExitRecordKeys(
    context: Context,
    keys: Set<String>
  ) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit(commit = true) { putStringSet(KEY_PROCESSED_EXIT_RECORDS, keys) }
  }

  fun getEnvironment(context: Context): String? {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getString(KEY_ENVIRONMENT, null) ?: getDefaultEnvironment()
  }

  fun setEnvironment(
    context: Context,
    environment: String
  ) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit(commit = true) { putString(KEY_ENVIRONMENT, environment) }
  }

  fun getDefaultEnvironment(): String? {
    return if (BuildConfig.DEBUG) "development" else null
  }
}
