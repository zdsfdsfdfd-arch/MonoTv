package tv.monotv.launcher.data.inputs

import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.monotv.launcher.core.InputItem
import tv.monotv.launcher.core.InputKind

/**
 * The TvInputInfo → menu row rules, on plain values. Only compile-time constants of the TV input framework are
 * used, so this runs as an ordinary JVM test.
 */
class InputMapperTest {

    private fun input(
        id: String,
        type: Int = TvInputInfo.TYPE_HDMI,
        label: String = id,
        parentId: String? = null,
        passthrough: Boolean = true,
        state: Int? = TvInputManager.INPUT_STATE_CONNECTED,
    ) = TvInputSnapshot(id = id, type = type, parentId = parentId, label = label, isPassthrough = passthrough, state = state)

    private fun map(vararg inputs: TvInputSnapshot, tunerViewable: Boolean = true): List<InputItem> =
        InputMapper.toItems(inputs.toList(), tunerViewable)

    // -----------------------------------------------------------------------------------------------------------------
    // Kinds
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `every framework type has its kind`() {
        val expected = mapOf(
            TvInputInfo.TYPE_HDMI to InputKind.Hdmi,
            TvInputInfo.TYPE_COMPOSITE to InputKind.Av,
            TvInputInfo.TYPE_SVIDEO to InputKind.Av,
            TvInputInfo.TYPE_SCART to InputKind.Av,
            TvInputInfo.TYPE_COMPONENT to InputKind.Component,
            TvInputInfo.TYPE_VGA to InputKind.Component,
            TvInputInfo.TYPE_DVI to InputKind.Component,
            TvInputInfo.TYPE_DISPLAY_PORT to InputKind.Component,
            TvInputInfo.TYPE_TUNER to InputKind.Tuner,
            TvInputInfo.TYPE_OTHER to InputKind.Other,
            -1 to InputKind.Other,
            4242 to InputKind.Other,
        )
        for ((type, kind) in expected) assertEquals("type $type", kind, InputMapper.kindOf(type))
    }

    @Test
    fun `connection state maps to connected, disconnected or unknown`() {
        assertEquals(true, InputMapper.connectedOf(TvInputManager.INPUT_STATE_CONNECTED))
        assertEquals(true, InputMapper.connectedOf(TvInputManager.INPUT_STATE_CONNECTED_STANDBY))
        assertEquals(false, InputMapper.connectedOf(TvInputManager.INPUT_STATE_DISCONNECTED))
        assertNull(InputMapper.connectedOf(null))
        assertNull(InputMapper.connectedOf(99))
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Rows
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a hardware HDMI port becomes a switchable row`() {
        val id = "com.droidlogic.tvinput/.services.Hdmi1InputService/HW5"
        assertEquals(
            listOf(InputItem(id = id, label = "HDMI 1", kind = InputKind.Hdmi, connected = true, switchable = true)),
            map(input(id, label = "HDMI 1")),
        )
    }

    @Test
    fun `an unplugged port is listed as not connected but stays switchable`() {
        val item = map(input("hdmi2", label = "HDMI 2", state = TvInputManager.INPUT_STATE_DISCONNECTED)).single()
        assertEquals(false, item.connected)
        assertTrue(item.switchable)
    }

    @Test
    fun `a sleeping device counts as connected and an unknown state stays unknown`() {
        assertEquals(true, map(input("a", state = TvInputManager.INPUT_STATE_CONNECTED_STANDBY)).single().connected)
        assertNull(map(input("a", state = null)).single().connected)
    }

    @Test
    fun `only passthrough inputs are switchable`() {
        val items = map(
            input("hdmi1", passthrough = true),
            input("virtual", type = TvInputInfo.TYPE_OTHER, passthrough = false),
        )
        assertEquals(listOf(true, false), items.map { it.switchable })
    }

    @Test
    fun `CEC devices behind a port are hidden`() {
        val items = map(
            input("hdmi1", label = "HDMI 1"),
            input("hdmi1/cec4", label = "PlayStation 5", parentId = "hdmi1"),
            input("hdmi2", label = "HDMI 2"),
        )
        assertEquals(listOf("hdmi1", "hdmi2"), items.map { it.id })
    }

    @Test
    fun `only child devices means no hardware inputs`() {
        assertEquals(emptyList<InputItem>(), map(input("cec", parentId = "gone")))
        assertEquals(emptyList<InputItem>(), map())
    }

    @Test
    fun `a blank label is replaced by a generic one and labels are trimmed`() {
        val items = map(
            input("h", type = TvInputInfo.TYPE_HDMI, label = "  "),
            input("a", type = TvInputInfo.TYPE_COMPOSITE, label = ""),
            input("c", type = TvInputInfo.TYPE_VGA, label = "\t"),
            input("o", type = TvInputInfo.TYPE_OTHER, label = ""),
            input("p", type = TvInputInfo.TYPE_HDMI, label = "  PlayStation "),
        )
        assertEquals(listOf("HDMI", "PlayStation", "AV", "Компонентный вход", "Вход"), items.map { it.label })
    }

    @Test
    fun `a repeated input id is listed once`() {
        val items = map(input("hdmi1", label = "HDMI 1"), input("hdmi1", label = "HDMI 1 again"))
        assertEquals(listOf("HDMI 1"), items.map { it.label })
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Tuner
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `all tuners collapse into one row with a fixed name`() {
        val items = map(
            input("dtv", type = TvInputInfo.TYPE_TUNER, label = "DTV", passthrough = false),
            input("atv", type = TvInputInfo.TYPE_TUNER, label = "ATV", passthrough = false),
            input("iptv", type = TvInputInfo.TYPE_TUNER, label = "Some IPTV app", passthrough = false),
        )
        assertEquals(
            listOf(InputItem(InputMapper.TUNER_ID, "Эфирное ТВ", InputKind.Tuner, connected = true, switchable = true)),
            items,
        )
    }

    @Test
    fun `the tuner is switchable only when something can show the channel list`() {
        val tuner = input("dtv", type = TvInputInfo.TYPE_TUNER, passthrough = false)
        assertTrue(map(tuner, tunerViewable = true).single().switchable)
        assertFalse(map(tuner, tunerViewable = false).single().switchable)
        // Being passthrough or not is irrelevant for a tuner.
        assertFalse(map(tuner.copy(isPassthrough = true), tunerViewable = false).single().switchable)
    }

    @Test
    fun `a viewable channel list does not conjure up a tuner`() {
        assertTrue(map(input("hdmi1"), tunerViewable = true).none { it.kind == InputKind.Tuner })
    }

    @Test
    fun `the merged tuner is connected when any tuner is`() {
        fun tuner(id: String, state: Int?) = input(id, type = TvInputInfo.TYPE_TUNER, passthrough = false, state = state)
        val connected = TvInputManager.INPUT_STATE_CONNECTED
        val disconnected = TvInputManager.INPUT_STATE_DISCONNECTED

        assertEquals(true, map(tuner("a", disconnected), tuner("b", connected)).single().connected)
        assertEquals(true, map(tuner("a", null), tuner("b", connected)).single().connected)
        assertEquals(false, map(tuner("a", disconnected), tuner("b", disconnected)).single().connected)
        assertNull(map(tuner("a", disconnected), tuner("b", null)).single().connected)
        assertNull(map(tuner("a", null)).single().connected)
    }

    @Test
    fun `a tuner hanging off another input is not a tuner row`() {
        assertEquals(emptyList<InputItem>(), map(input("child", type = TvInputInfo.TYPE_TUNER, parentId = "parent")))
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Order
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `rows are ordered HDMI by name, AV, component, tuner, other`() {
        val items = map(
            input("other", type = TvInputInfo.TYPE_OTHER, label = "Miracast", passthrough = false),
            input("dtv", type = TvInputInfo.TYPE_TUNER, label = "DTV", passthrough = false),
            input("vga", type = TvInputInfo.TYPE_VGA, label = "PC"),
            input("ypbpr", type = TvInputInfo.TYPE_COMPONENT, label = "Component"),
            input("av", type = TvInputInfo.TYPE_COMPOSITE, label = "AV"),
            input("hw7", label = "HDMI 3"),
            input("hw5", label = "hdmi 1"),
            input("hw6", label = "HDMI 2"),
        )
        assertEquals(
            listOf("hdmi 1", "HDMI 2", "HDMI 3", "AV", "Component", "PC", "Эфирное ТВ", "Miracast"),
            items.map { it.label },
        )
        assertEquals(
            listOf(
                InputKind.Hdmi, InputKind.Hdmi, InputKind.Hdmi, InputKind.Av,
                InputKind.Component, InputKind.Component, InputKind.Tuner, InputKind.Other,
            ),
            items.map { it.kind },
        )
    }

    @Test
    fun `equal labels are ordered by id so the list never flickers`() {
        val forwards = map(input("b", label = "HDMI"), input("a", label = "HDMI"), input("c", label = "HDMI"))
        val backwards = map(input("c", label = "HDMI"), input("a", label = "HDMI"), input("b", label = "HDMI"))
        assertEquals(listOf("a", "b", "c"), forwards.map { it.id })
        assertEquals(forwards, backwards)
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Placeholders
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `placeholders have the familiar shape and are never actionable`() {
        val placeholders = InputMapper.placeholders
        assertEquals(listOf("HDMI 1", "HDMI 2", "HDMI 3", "AV", "Эфирное ТВ"), placeholders.map { it.label })
        assertEquals(
            listOf(InputKind.Hdmi, InputKind.Hdmi, InputKind.Hdmi, InputKind.Av, InputKind.Tuner),
            placeholders.map { it.kind },
        )
        assertTrue(placeholders.none { it.switchable })
        assertTrue(placeholders.all { it.connected == null })
        assertEquals(placeholders.size, placeholders.map { it.id }.toSet().size)
        assertTrue("a placeholder id can never collide with a real input id", placeholders.all { it.id.startsWith("monotv:") })
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Properties
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `random input lists keep every invariant`() {
        val types = listOf(
            TvInputInfo.TYPE_HDMI, TvInputInfo.TYPE_COMPOSITE, TvInputInfo.TYPE_SVIDEO, TvInputInfo.TYPE_SCART,
            TvInputInfo.TYPE_COMPONENT, TvInputInfo.TYPE_VGA, TvInputInfo.TYPE_DVI, TvInputInfo.TYPE_DISPLAY_PORT,
            TvInputInfo.TYPE_TUNER, TvInputInfo.TYPE_OTHER, 7,
        )
        val states = listOf(
            TvInputManager.INPUT_STATE_CONNECTED, TvInputManager.INPUT_STATE_CONNECTED_STANDBY,
            TvInputManager.INPUT_STATE_DISCONNECTED, null, 42,
        )
        val kindOrder = listOf(InputKind.Hdmi, InputKind.Av, InputKind.Component, InputKind.Tuner, InputKind.Other)

        for (seed in 0 until 500) {
            val random = Random(seed)
            val inputs = List(random.nextInt(0, 12)) { index ->
                input(
                    id = "in${random.nextInt(0, 9)}",
                    type = types.random(random),
                    label = listOf("HDMI ${index % 4}", "", "AV", "hdmi ${index % 3}").random(random),
                    parentId = if (random.nextInt(5) == 0) "in0" else null,
                    passthrough = random.nextBoolean(),
                    state = states.random(random),
                )
            }
            val tunerViewable = random.nextBoolean()
            val items = InputMapper.toItems(inputs, tunerViewable)
            val context = "seed $seed"

            assertEquals("$context: deterministic", items, InputMapper.toItems(inputs, tunerViewable))
            assertEquals("$context: unique ids", items.size, items.map { it.id }.toSet().size)
            assertTrue("$context: at most one tuner", items.count { it.kind == InputKind.Tuner } <= 1)
            assertEquals("$context: grouped by kind", items.map { kindOrder.indexOf(it.kind) }.sorted(), items.map { kindOrder.indexOf(it.kind) })
            assertTrue("$context: no blank labels", items.none { it.label.isBlank() })
            assertTrue("$context: no children", items.none { item -> inputs.any { it.id == item.id && it.parentId != null } && inputs.none { it.id == item.id && it.parentId == null } })
            for (item in items) {
                if (item.kind == InputKind.Tuner) {
                    assertEquals(context, InputMapper.TUNER_ID, item.id)
                    assertEquals(context, tunerViewable, item.switchable)
                } else {
                    val source = inputs.first { it.id == item.id && it.parentId == null }
                    assertEquals(context, source.isPassthrough, item.switchable)
                    assertEquals(context, InputMapper.connectedOf(source.state), item.connected)
                }
            }
            val hasTopLevel = inputs.any { it.parentId == null }
            assertEquals("$context: empty exactly when there is no top-level input", !hasTopLevel, items.isEmpty())
        }
    }
}
