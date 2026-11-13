package tv.monotv.launcher.data.inputs

import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.monotv.launcher.core.InputItem
import tv.monotv.launcher.core.InputKind

/**
 * The repository's own behaviour — when it reads, what it publishes, what it lets through to the switcher — with the
 * TV input framework replaced by a fake. Plain JVM: no Android class is instantiated.
 */
class InputsRepositoryLogicTest {

    private val hdmi1 = snapshot("hw5", "HDMI 1")
    private val hdmi2 = snapshot("hw6", "HDMI 2", state = TvInputManager.INPUT_STATE_DISCONNECTED)
    private val tuner = snapshot("dtv", "DTV", type = TvInputInfo.TYPE_TUNER, passthrough = false)

    private val source = FakeSource()
    private val switcher = RecordingSwitcher()

    private fun repository() = AndroidInputsRepository(source, switcher, Dispatchers.Unconfined)

    // -----------------------------------------------------------------------------------------------------------------
    // Reading
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `placeholders are shown until the repository is started`() {
        source.inputs = listOf(hdmi1)
        val repository = repository()
        assertEquals(InputMapper.placeholders, repository.inputs.value)
        assertEquals(0, source.reads.get())
        assertEquals(0, source.registrations)
    }

    @Test
    fun `start publishes the real inputs`() {
        source.inputs = listOf(hdmi2, tuner, hdmi1)
        val repository = repository()

        repository.start()

        assertEquals(
            listOf(
                InputItem("hw5", "HDMI 1", InputKind.Hdmi, connected = true, switchable = true),
                InputItem("hw6", "HDMI 2", InputKind.Hdmi, connected = false, switchable = true),
                InputItem(InputMapper.TUNER_ID, "Эфирное ТВ", InputKind.Tuner, connected = true, switchable = true),
            ),
            repository.inputs.value,
        )
    }

    @Test
    fun `a change reported by the framework is picked up`() {
        source.inputs = listOf(hdmi1)
        val repository = repository()
        repository.start()

        source.inputs = listOf(hdmi1.copy(state = TvInputManager.INPUT_STATE_DISCONNECTED), hdmi2)
        source.notifyChanged()

        assertEquals(listOf(false, false), repository.inputs.value.map { it.connected })
        assertEquals(listOf("hw5", "hw6"), repository.inputs.value.map { it.id })
    }

    @Test
    fun `a device without usable inputs shows the placeholders`() {
        val repository = repository()

        source.inputs = null // no TV input framework at all
        repository.start()
        assertEquals(InputMapper.placeholders, repository.inputs.value)

        source.inputs = emptyList()
        source.notifyChanged()
        assertEquals(InputMapper.placeholders, repository.inputs.value)

        source.inputs = listOf(snapshot("cec", "Player", parentId = "gone"))
        source.notifyChanged()
        assertEquals(InputMapper.placeholders, repository.inputs.value)
    }

    @Test
    fun `inputs appearing and disappearing switch between real rows and placeholders`() {
        val repository = repository()
        repository.start()
        assertEquals(InputMapper.placeholders, repository.inputs.value)

        source.inputs = listOf(hdmi1)
        source.notifyChanged()
        assertEquals(listOf("HDMI 1"), repository.inputs.value.map { it.label })

        source.inputs = emptyList()
        source.notifyChanged()
        assertEquals(InputMapper.placeholders, repository.inputs.value)
    }

    @Test
    fun `a failing read degrades to placeholders and recovers on the next change`() {
        source.inputs = listOf(hdmi1)
        val repository = repository()
        repository.start()

        source.readFailure = SecurityException("TV input service is off limits")
        source.notifyChanged()
        assertEquals(InputMapper.placeholders, repository.inputs.value)

        source.readFailure = null
        source.notifyChanged()
        assertEquals(listOf("HDMI 1"), repository.inputs.value.map { it.label })
    }

    @Test
    fun `the tuner row follows whether a live TV app exists`() {
        source.inputs = listOf(tuner)
        source.tunerViewable = false
        val repository = repository()
        repository.start()
        assertFalse(repository.inputs.value.single().switchable)

        source.tunerViewable = true
        source.notifyChanged()
        assertTrue(repository.inputs.value.single().switchable)
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `start and stop are idempotent`() {
        source.inputs = listOf(hdmi1)
        val repository = repository()

        repository.stop() // before any start
        assertEquals(0, source.unregistrations)

        repository.start()
        repository.start()
        assertEquals(1, source.registrations)
        assertEquals(1, source.reads.get())
        assertTrue(source.isRegistered)

        repository.stop()
        repository.stop()
        assertEquals(1, source.unregistrations)
        assertFalse(source.isRegistered)
    }

    @Test
    fun `nothing is read while stopped and a restart catches up`() {
        source.inputs = listOf(hdmi1)
        val repository = repository()
        repository.start()
        repository.stop()
        val readsWhenStopped = source.reads.get()

        source.inputs = listOf(hdmi1, hdmi2)
        assertEquals("the list last seen stays visible while stopped", listOf("hw5"), repository.inputs.value.map { it.id })
        assertEquals(readsWhenStopped, source.reads.get())

        repository.start()
        assertEquals(listOf("hw5", "hw6"), repository.inputs.value.map { it.id })
        assertEquals(2, source.registrations)
    }

    @Test
    fun `a source that cannot register callbacks still gets read`() {
        source.inputs = listOf(hdmi1)
        source.registerFailure = IllegalStateException("callback limit reached")
        val repository = repository()

        repository.start()

        assertEquals(listOf("hw5"), repository.inputs.value.map { it.id })
        repository.stop()
    }

    @Test
    fun `a burst of changes is folded into a single read that sees the latest state`() {
        val dispatcher = StandardTestDispatcher()
        val repository = AndroidInputsRepository(source, switcher, dispatcher)
        source.inputs = listOf(hdmi1)

        repository.start()
        repeat(10) { source.notifyChanged() }
        source.inputs = listOf(hdmi1, hdmi2)
        source.notifyChanged()
        assertEquals("nothing is read on the caller's thread", 0, source.reads.get())
        assertEquals(InputMapper.placeholders, repository.inputs.value)

        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, source.reads.get())
        assertEquals(listOf("hw5", "hw6"), repository.inputs.value.map { it.id })

        // Once that read has run, the next change triggers a new one.
        source.inputs = listOf(hdmi2)
        source.notifyChanged()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, source.reads.get())
        assertEquals(listOf("hw6"), repository.inputs.value.map { it.id })
    }

    @Test
    fun `changes racing in from several threads end with the final state published`() {
        val repository = AndroidInputsRepository(source, switcher, Dispatchers.Default)
        source.inputs = listOf(hdmi1)
        repository.start()

        val startTogether = CountDownLatch(1)
        val workers = (0 until 4).map { worker ->
            thread {
                startTogether.await()
                repeat(200) { round ->
                    source.inputs = listOf(snapshot("w$worker-$round", "HDMI $round"))
                    source.notifyChanged()
                }
            }
        }
        startTogether.countDown()
        workers.forEach { it.join(TimeUnit.SECONDS.toMillis(30)) }

        val last = listOf(hdmi1, hdmi2, tuner)
        source.inputs = last
        source.notifyChanged()

        val expected = InputMapper.toItems(last, tunerViewable = true)
        runBlocking { withTimeout(TimeUnit.SECONDS.toMillis(30)) { repository.inputs.first { it == expected } } }
        assertTrue("reads never overlap", source.maxConcurrentReads.get() <= 1)
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Switching
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a switchable input is handed to the switcher`() {
        source.inputs = listOf(hdmi1, hdmi2)
        val repository = repository()
        repository.start()
        val item = repository.inputs.value.first { it.id == "hw6" }

        assertTrue(repository.switchTo(item))

        assertEquals(listOf(item), switcher.requests)
    }

    @Test
    fun `the switcher's refusal is passed on`() {
        source.inputs = listOf(hdmi1)
        val repository = repository()
        repository.start()
        switcher.result = false

        assertFalse(repository.switchTo(repository.inputs.value.single()))
        assertEquals(1, switcher.requests.size)
    }

    @Test
    fun `a switcher that throws counts as a refusal`() {
        source.inputs = listOf(hdmi1)
        val repository = repository()
        repository.start()
        switcher.failure = SecurityException("not allowed")

        assertFalse(repository.switchTo(repository.inputs.value.single()))
    }

    @Test
    fun `placeholders never reach the switcher`() {
        val repository = repository()
        repository.start()

        for (placeholder in repository.inputs.value) {
            assertFalse(repository.switchTo(placeholder))
            // Not even when somebody hands in a doctored copy.
            assertFalse(repository.switchTo(placeholder.copy(switchable = true)))
        }
        assertEquals(emptyList<InputItem>(), switcher.requests)
    }

    @Test
    fun `an input that is not switchable is refused without asking the switcher`() {
        source.inputs = listOf(snapshot("virtual", "Virtual", type = TvInputInfo.TYPE_OTHER, passthrough = false), tuner)
        source.tunerViewable = false
        val repository = repository()
        repository.start()

        for (item in repository.inputs.value) assertFalse(repository.switchTo(item))
        assertEquals(emptyList<InputItem>(), switcher.requests)
    }

    @Test
    fun `a row from an old frame cannot switch to an input that is gone`() {
        source.inputs = listOf(hdmi1, hdmi2)
        val repository = repository()
        repository.start()
        val stale = repository.inputs.value.first { it.id == "hw6" }

        source.inputs = listOf(hdmi1)
        source.notifyChanged()

        assertFalse(repository.switchTo(stale))
        assertFalse(repository.switchTo(InputItem("made-up", "HDMI 9", InputKind.Hdmi, connected = true, switchable = true)))
        assertEquals(emptyList<InputItem>(), switcher.requests)
    }

    @Test
    fun `the switcher receives the current row, not the caller's stale copy`() {
        source.inputs = listOf(hdmi1)
        val repository = repository()
        repository.start()
        val stale = repository.inputs.value.single()

        source.inputs = listOf(hdmi1.copy(label = "PlayStation", state = TvInputManager.INPUT_STATE_DISCONNECTED))
        source.notifyChanged()

        assertTrue(repository.switchTo(stale))
        assertEquals(listOf(InputItem("hw5", "PlayStation", InputKind.Hdmi, connected = false, switchable = true)), switcher.requests)
    }

    @Test
    fun `the merged tuner row is switched as a tuner`() {
        source.inputs = listOf(tuner)
        val repository = repository()
        repository.start()

        assertTrue(repository.switchTo(repository.inputs.value.single()))

        assertEquals(InputKind.Tuner, switcher.requests.single().kind)
        assertEquals(InputMapper.TUNER_ID, switcher.requests.single().id)
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Fakes
    // -----------------------------------------------------------------------------------------------------------------

    private fun snapshot(
        id: String,
        label: String,
        type: Int = TvInputInfo.TYPE_HDMI,
        parentId: String? = null,
        passthrough: Boolean = true,
        state: Int? = TvInputManager.INPUT_STATE_CONNECTED,
    ) = TvInputSnapshot(id, type, parentId, label, passthrough, state)

    private class FakeSource : TvInputSource {
        @Volatile var inputs: List<TvInputSnapshot>? = emptyList()
        @Volatile var tunerViewable = true
        @Volatile var readFailure: RuntimeException? = null
        var registerFailure: RuntimeException? = null

        val reads = AtomicInteger()
        val maxConcurrentReads = AtomicInteger()
        private val concurrentReads = AtomicInteger()
        var registrations = 0
        var unregistrations = 0

        @Volatile private var listener: (() -> Unit)? = null
        val isRegistered: Boolean get() = listener != null

        override fun read(): TvInputReading? {
            val concurrent = concurrentReads.incrementAndGet()
            maxConcurrentReads.updateAndGet { maxOf(it, concurrent) }
            try {
                reads.incrementAndGet()
                readFailure?.let { throw it }
                return inputs?.let { TvInputReading(it, tunerViewable) }
            } finally {
                concurrentReads.decrementAndGet()
            }
        }

        override fun register(onChanged: () -> Unit) {
            registrations++
            registerFailure?.let { throw it }
            listener = onChanged
        }

        override fun unregister() {
            unregistrations++
            listener = null
        }

        fun notifyChanged() {
            listener?.invoke()
        }
    }

    private class RecordingSwitcher : InputSwitcher {
        val requests = ArrayList<InputItem>()
        var result = true
        var failure: RuntimeException? = null

        override fun switchTo(item: InputItem): Boolean {
            requests += item
            failure?.let { throw it }
            return result
        }
    }
}
