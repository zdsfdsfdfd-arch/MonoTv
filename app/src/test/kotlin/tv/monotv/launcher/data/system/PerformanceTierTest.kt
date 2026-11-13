package tv.monotv.launcher.data.system

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import tv.monotv.launcher.core.EffectsLevel

class PerformanceTierTest {

    private fun recommend(totalRamMb: Int = 1900, lowRam: Boolean = false, cpuCores: Int = 4, sdkInt: Int = 30) =
        PerformanceTier.recommend(totalRamMb, lowRam, cpuCores, sdkInt)

    @Test
    fun `a two gigabyte quad core TV gets full effects`() {
        assertEquals(EffectsLevel.Full, recommend(totalRamMb = 1900, cpuCores = 4))
        assertEquals(EffectsLevel.Full, recommend(totalRamMb = 3800, cpuCores = 8))
    }

    @Test
    fun `a one gigabyte TV gets reduced effects`() {
        assertEquals(EffectsLevel.Reduced, recommend(totalRamMb = 930))
        assertEquals(EffectsLevel.Reduced, recommend(totalRamMb = 1))
    }

    @Test
    fun `the memory limit is inclusive`() {
        assertEquals(EffectsLevel.Reduced, recommend(totalRamMb = 1300))
        assertEquals(EffectsLevel.Full, recommend(totalRamMb = 1301))
        assertEquals(EffectsLevel.Full, recommend(totalRamMb = 1434))
    }

    @Test
    fun `the vendor low-ram flag always reduces`() {
        assertEquals(EffectsLevel.Reduced, recommend(totalRamMb = 3800, lowRam = true, cpuCores = 8))
        assertEquals(EffectsLevel.Reduced, recommend(totalRamMb = 0, lowRam = true, cpuCores = 0))
    }

    @Test
    fun `one or two cores reduce`() {
        assertEquals(EffectsLevel.Reduced, recommend(cpuCores = 1))
        assertEquals(EffectsLevel.Reduced, recommend(cpuCores = 2))
        assertEquals(EffectsLevel.Full, recommend(cpuCores = 3))
    }

    @Test
    fun `unknown values do not downgrade by themselves`() {
        assertEquals(EffectsLevel.Full, recommend(totalRamMb = 0, cpuCores = 0, sdkInt = 0))
        assertEquals(EffectsLevel.Full, recommend(totalRamMb = 0, cpuCores = 4))
        assertEquals(EffectsLevel.Full, recommend(totalRamMb = 1900, cpuCores = 0))
        // Nonsense readings are treated like unknown ones.
        assertEquals(EffectsLevel.Full, recommend(totalRamMb = -1, cpuCores = -4))
        // ...but a known weakness still counts next to an unknown value.
        assertEquals(EffectsLevel.Reduced, recommend(totalRamMb = 0, cpuCores = 2))
        assertEquals(EffectsLevel.Reduced, recommend(totalRamMb = 900, cpuCores = 0))
    }

    @Test
    fun `the API level does not change the verdict`() {
        for (sdk in listOf(0, 23, 28, 30, 33, 36)) {
            assertEquals("sdk $sdk", EffectsLevel.Full, recommend(totalRamMb = 1900, sdkInt = sdk))
            assertEquals("sdk $sdk", EffectsLevel.Reduced, recommend(totalRamMb = 930, sdkInt = sdk))
        }
    }

    @Test
    fun `effects are never switched off automatically`() {
        val random = Random(7)
        repeat(5_000) {
            val level = PerformanceTier.recommend(
                totalRamMb = random.nextInt(-10, 9000),
                lowRam = random.nextBoolean(),
                cpuCores = random.nextInt(-2, 17),
                sdkInt = random.nextInt(0, 40),
            )
            assertNotEquals(EffectsLevel.Off, level)
        }
    }
}
