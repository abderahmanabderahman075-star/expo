package expo.modules.appmetrics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProcessIdentityTest {
  @After
  fun reset() {
    ProcessIdentity.resetForTesting()
  }

  @Test
  fun `generates a stable session id per process`() {
    val first = ProcessIdentity.initialize()
    val second = ProcessIdentity.initialize()

    assertNotNull(first)
    assertEquals(first, second)
    assertEquals(first, ProcessIdentity.sessionId)
  }

  @Test
  fun `sessionId is null before initialize`() {
    assertNull(ProcessIdentity.sessionId)
  }
}
