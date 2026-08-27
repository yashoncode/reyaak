package io.reyaak.router.config

import io.reyaak.router.catalog.BuiltinCatalog
import io.reyaak.router.catalog.ModelSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** A toggle the user flipped must survive the encode/decode the store does on every write. */
class ToggleRoundTripTest {

    private fun roundTrip(settings: RouterSettings): RouterSettings =
        RouterConfigCodec.decode(RouterConfigCodec.encode(settings, includeSecrets = true)).settings

    private fun withKey(settings: RouterSettings) = settings.withKey(
        KeyRecord(platform = "gemini", label = "k1", secret = "secret")
    )

    @Test
    fun `disabling a builtin model survives a round trip`() {
        val target = BuiltinCatalog.models.first { it.platform == "gemini" }
        val settings = withKey(RouterSettings()).let { s ->
            s.copy(models = s.models.map { if (it.key == target.key) it.copy(enabled = false) else it })
        }
        val back = roundTrip(settings)
        assertEquals(false, back.models.first { it.key == target.key }.enabled)
    }

    @Test
    fun `a discovered model survives a round trip in both states`() {
        val discovered = ModelSpec("gemini", "gemini-9-ultra", "gemini-9-ultra", "Medium", enabled = false)
        val settings = withKey(RouterSettings()).let { it.copy(models = it.models + discovered) }

        val off = roundTrip(settings).models.firstOrNull { it.key == discovered.key }
        assertNotNull("disabled discovered model was dropped", off)
        assertEquals(false, off!!.enabled)

        val onSettings = settings.copy(
            models = settings.models.map { if (it.key == discovered.key) it.copy(enabled = true) else it }
        )
        val on = roundTrip(onSettings).models.firstOrNull { it.key == discovered.key }
        assertNotNull("enabled discovered model was dropped", on)
        assertEquals(true, on!!.enabled)
    }
}
