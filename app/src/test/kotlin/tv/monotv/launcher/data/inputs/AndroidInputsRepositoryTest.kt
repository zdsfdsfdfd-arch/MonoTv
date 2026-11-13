package tv.monotv.launcher.data.inputs

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.media.tv.TvContract
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tv.monotv.launcher.core.InputItem
import tv.monotv.launcher.core.InputKind

/** The platform-facing half: the default wiring on a device without TV inputs, and the TIF switcher's intents. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidInputsRepositoryTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()

    private val hdmi1 = InputItem(
        id = "com.droidlogic.tvinput/.services.Hdmi1InputService/HW5",
        label = "HDMI 1",
        kind = InputKind.Hdmi,
        connected = true,
        switchable = true,
    )
    private val tuner = InputItem(InputMapper.TUNER_ID, InputMapper.TUNER_LABEL, InputKind.Tuner, connected = true, switchable = true)

    // -----------------------------------------------------------------------------------------------------------------
    // Placeholder path
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a device without TV inputs shows unavailable placeholders`() {
        val repository = AndroidInputsRepository(app)
        assertEquals(InputMapper.placeholders, repository.inputs.value)

        repository.start()
        shadowOf(Looper.getMainLooper()).idle()

        val inputs = repository.inputs.value
        assertEquals(listOf("HDMI 1", "HDMI 2", "HDMI 3", "AV", "Эфирное ТВ"), inputs.map { it.label })
        assertTrue(inputs.none { it.switchable })
        assertTrue(inputs.all { it.connected == null })
        repository.stop()
    }

    @Test
    fun `the real platform source resolves to placeholders when read synchronously`() {
        // Same wiring as the public constructor, minus the background thread, so the read has finished on return.
        val repository = AndroidInputsRepository(AndroidTvInputSource(app), TifInputSwitcher(app), Dispatchers.Unconfined)

        repository.start()
        assertEquals(InputMapper.placeholders, repository.inputs.value)

        repository.stop()
        repository.start()
        assertEquals(InputMapper.placeholders, repository.inputs.value)
        repository.stop()
    }

    @Test
    fun `the platform source reports no hardware inputs here`() {
        val source = AndroidTvInputSource(app)
        val reading = try {
            source.read()
        } catch (e: RuntimeException) {
            // The repository treats a throwing source like a missing one; a read that returns is the better outcome.
            null
        }
        assertTrue(reading == null || InputMapper.toItems(reading.inputs, reading.tunerViewable).isEmpty())

        // Registering and releasing callbacks is safe in any order and any number of times.
        source.unregister()
        source.register {}
        source.register {}
        source.unregister()
        source.unregister()
    }

    @Test
    fun `placeholders cannot be switched to and start nothing`() {
        answer(TifInputSwitcher.tunerIntent(), "ru.sberdevices.starlivetv", "ru.sberdevices.starlivetv.MainActivity")
        val repository = AndroidInputsRepository(AndroidTvInputSource(app), TifInputSwitcher(app), Dispatchers.Unconfined)
        repository.start()

        for (placeholder in repository.inputs.value) {
            assertFalse(repository.switchTo(placeholder))
            assertFalse(repository.switchTo(placeholder.copy(switchable = true)))
        }
        assertFalse(repository.switchTo(hdmi1))
        assertNull(shadowOf(app).peekNextStartedActivity())
    }

    @Test
    fun `a missing TV input service is not an error`() {
        val noTv = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? =
                if (name == Context.TV_INPUT_SERVICE) null else super.getSystemService(name)
        }
        val source = AndroidTvInputSource(noTv)
        assertNull(source.read())
        source.register {}
        source.unregister()

        val throwing = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? =
                if (name == Context.TV_INPUT_SERVICE) throw IllegalStateException("no such service") else super.getSystemService(name)
        }
        assertNull(AndroidTvInputSource(throwing).read())
    }

    // -----------------------------------------------------------------------------------------------------------------
    // TIF switcher
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a passthrough input is viewed through its passthrough channel uri`() {
        answer(TifInputSwitcher.passthroughIntent(hdmi1.id), "ru.sberdevices.tvinputs", "ru.sberdevices.tvinputs.MainActivity")

        assertTrue(TifInputSwitcher(app).switchTo(hdmi1))

        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(TvContract.buildChannelUriForPassthroughInput(hdmi1.id), started.data)
        assertEquals("content", started.data!!.scheme)
        assertEquals(TvContract.AUTHORITY, started.data!!.authority)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertNull("an implicit intent: the system decides who shows the input", started.component)
        assertNull(started.extras)
        assertNull(shadowOf(app).peekNextStartedActivity())
    }

    @Test
    fun `the tuner is viewed as the channel list`() {
        answer(TifInputSwitcher.tunerIntent(), "ru.sberdevices.starlivetv", "ru.sberdevices.starlivetv.MainActivity")

        assertTrue(TifInputSwitcher(app).switchTo(tuner))

        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(TvContract.Channels.CONTENT_URI, started.data)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `nothing is started when no activity can show the input`() {
        val switcher = TifInputSwitcher(app)
        assertFalse(switcher.switchTo(hdmi1))
        assertFalse(switcher.switchTo(tuner))
        assertNull(shadowOf(app).peekNextStartedActivity())
    }

    @Test
    fun `an input that is not switchable is refused even if a viewer exists`() {
        answer(TifInputSwitcher.passthroughIntent(hdmi1.id), "ru.sberdevices.tvinputs", "ru.sberdevices.tvinputs.MainActivity")

        assertFalse(TifInputSwitcher(app).switchTo(hdmi1.copy(switchable = false)))

        assertNull(shadowOf(app).peekNextStartedActivity())
    }

    @Test
    fun `a viewer for one input does not make another one switchable`() {
        answer(TifInputSwitcher.passthroughIntent(hdmi1.id), "ru.sberdevices.tvinputs", "ru.sberdevices.tvinputs.MainActivity")

        assertFalse(TifInputSwitcher(app).switchTo(hdmi1.copy(id = "com.droidlogic.tvinput/.services.Hdmi2InputService/HW6")))
        assertFalse(TifInputSwitcher(app).switchTo(tuner))

        assertNull(shadowOf(app).peekNextStartedActivity())
    }

    @Test
    fun `a factory menu posing as an input viewer is never started`() {
        answer(TifInputSwitcher.passthroughIntent(hdmi1.id), "com.cvte.fac.menu", "com.cvte.fac.menu.SourceActivity")
        answer(TifInputSwitcher.tunerIntent(), "com.stb.factorymenu", "com.stb.factorymenu.Main")

        assertFalse(TifInputSwitcher(app).switchTo(hdmi1))
        assertFalse(TifInputSwitcher(app).switchTo(tuner))

        assertNull(shadowOf(app).peekNextStartedActivity())
    }

    @Test
    fun `a start rejected by the system is a refusal, not a crash`() {
        answer(TifInputSwitcher.passthroughIntent(hdmi1.id), "ru.sberdevices.tvinputs", "ru.sberdevices.tvinputs.MainActivity")
        val rejecting = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this

            override fun startActivity(intent: Intent?) {
                throw SecurityException("Permission Denial: not exported")
            }

            override fun startActivity(intent: Intent?, options: Bundle?) {
                throw SecurityException("Permission Denial: not exported")
            }
        }

        assertFalse(TifInputSwitcher(rejecting).switchTo(hdmi1))
    }

    @Test
    fun `switching sends no broadcast and starts no service`() {
        answer(TifInputSwitcher.passthroughIntent(hdmi1.id), "ru.sberdevices.tvinputs", "ru.sberdevices.tvinputs.MainActivity")

        TifInputSwitcher(app).switchTo(hdmi1)

        assertTrue(shadowOf(app).broadcastIntents.isEmpty())
        assertNull(shadowOf(app).peekNextStartedService())
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Makes [packageName]/[className] the activity that resolves [intent]. Robolectric deprecates this shortcut in
     * favour of installing whole packages with intent filters; for "this intent resolves to that component" it is
     * still the most direct way to say it.
     */
    @Suppress("DEPRECATION")
    private fun answer(intent: Intent, packageName: String, className: String) {
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = className
                applicationInfo = ApplicationInfo().apply { this.packageName = packageName }
            }
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(intent, info)
    }
}
