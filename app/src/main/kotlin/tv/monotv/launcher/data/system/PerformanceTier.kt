package tv.monotv.launcher.data.system

import tv.monotv.launcher.core.EffectsLevel

/**
 * Picks the effects level used while the "Анимации" setting is on "Авто".
 *
 * The rule is deliberately coarse. Sber TVs and boxes come in two hardware classes — about 1 GB and about 2 GB of
 * RAM on the same family of quad-core SoCs — and the only thing that reliably separates "smooth" from "stutters when
 * the backdrop cross-fades" is how much memory is left for the graphics buffers.
 */
object PerformanceTier {

    /**
     * Largest total RAM, in megabytes, that is still treated as the small-memory class. A "1 GB" device reports
     * roughly 900–1000 MB (the kernel and the GPU keep their share), a "1.5 GB" one about 1400 MB and a "2 GB" one
     * 1800 MB or more, so the line sits between the first two.
     */
    const val LOW_RAM_LIMIT_MB = 1300

    /** One or two cores cannot run the render thread, the UI thread and a starting app side by side. */
    const val LOW_CORE_LIMIT = 2

    /**
     * @param totalRamMb total RAM reported by the system; 0 when unknown
     * @param lowRam     `ActivityManager.isLowRamDevice()` — the firmware vendor's own verdict, always honoured
     * @param cpuCores   number of processors; 0 when unknown
     * @param sdkInt     Android API level. Accepted so the whole hardware profile is passed in one place, but not
     *                   part of the rule: Android 9, 11 and 13 firmwares run on the same class of hardware and no
     *                   effect of the launcher depends on an API level.
     *
     * A value of 0 means "could not be read" and never downgrades by itself — guessing "weak" on a capable TV would
     * silently take the polish away. [EffectsLevel.Off] is never returned: switching animations off entirely is a
     * decision only the user makes.
     */
    @Suppress("UNUSED_PARAMETER")
    fun recommend(totalRamMb: Int, lowRam: Boolean, cpuCores: Int, sdkInt: Int): EffectsLevel {
        val smallMemory = totalRamMb in 1..LOW_RAM_LIMIT_MB
        val fewCores = cpuCores in 1..LOW_CORE_LIMIT
        return if (lowRam || smallMemory || fewCores) EffectsLevel.Reduced else EffectsLevel.Full
    }
}
