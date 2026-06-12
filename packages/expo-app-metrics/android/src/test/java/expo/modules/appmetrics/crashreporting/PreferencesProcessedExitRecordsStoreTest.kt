package expo.modules.appmetrics.crashreporting

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PreferencesProcessedExitRecordsStoreTest {
  private val context: Context
    get() = ApplicationProvider.getApplicationContext()

  @Test
  fun `round-trips keys across store instances`() {
    // Keys contain colons (timestamp:pid:reason) — the persisted form must
    // survive a fresh instance, like a new process would see.
    val keys = setOf("1700000000000:123:4", "1700000000500:456:5")
    PreferencesProcessedExitRecordsStore(context).setProcessedKeys(keys)

    assertEquals(keys, PreferencesProcessedExitRecordsStore(context).getProcessedKeys())
  }

  @Test
  fun `returns an empty set before anything was stored`() {
    assertEquals(emptySet<String>(), PreferencesProcessedExitRecordsStore(context).getProcessedKeys())
  }

  @Test
  fun `replaces the previous set instead of merging`() {
    val store = PreferencesProcessedExitRecordsStore(context)
    store.setProcessedKeys(setOf("old:1:4"))

    store.setProcessedKeys(setOf("new:2:5"))

    assertEquals(setOf("new:2:5"), store.getProcessedKeys())
  }

  @Test
  fun `the returned set cannot corrupt later reads`() {
    // SharedPreferences.getStringSet returns a live set that must not be
    // mutated or reused; the store hands out a copy (or an immutable set —
    // either way, callers can't poison the persisted value).
    val store = PreferencesProcessedExitRecordsStore(context)
    store.setProcessedKeys(setOf("a:1:4"))

    runCatching { (store.getProcessedKeys() as? MutableSet<String>)?.clear() }

    assertEquals(setOf("a:1:4"), store.getProcessedKeys())
  }
}
