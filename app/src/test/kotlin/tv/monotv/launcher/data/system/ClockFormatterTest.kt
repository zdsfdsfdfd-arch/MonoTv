package tv.monotv.launcher.data.system

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.monotv.launcher.core.ClockState

class ClockFormatterTest {

    private val moscow = zone("Europe/Moscow")
    private val utc = zone("UTC")

    @Test
    fun `friday evening in Moscow`() {
        assertEquals(
            ClockState(time = "19:42", date = "13 ноября, пятница", greeting = "Добрый вечер"),
            format(2026, 11, 13, 19, 42, moscow),
        )
    }

    @Test
    fun `24-hour time is zero padded`() {
        assertEquals("07:05", format(2026, 11, 13, 7, 5, moscow).time)
        assertEquals("00:00", format(2026, 11, 13, 0, 0, moscow).time)
        assertEquals("12:00", format(2026, 11, 13, 12, 0, moscow).time)
        assertEquals("23:59", format(2026, 11, 13, 23, 59, moscow).time)
    }

    @Test
    fun `12-hour time has no leading zero and no suffix`() {
        assertEquals("7:42", format(2026, 11, 13, 19, 42, moscow, is24Hour = false).time)
        assertEquals("7:05", format(2026, 11, 13, 7, 5, moscow, is24Hour = false).time)
        assertEquals("12:00", format(2026, 11, 13, 0, 0, moscow, is24Hour = false).time)
        assertEquals("12:30", format(2026, 11, 13, 12, 30, moscow, is24Hour = false).time)
        assertEquals("1:00", format(2026, 11, 13, 13, 0, moscow, is24Hour = false).time)
        assertEquals("11:59", format(2026, 11, 13, 23, 59, moscow, is24Hour = false).time)
    }

    @Test
    fun `the hour format changes nothing but the time`() {
        val in24 = format(2026, 11, 13, 19, 42, moscow, is24Hour = true)
        val in12 = format(2026, 11, 13, 19, 42, moscow, is24Hour = false)
        assertEquals(in24.date, in12.date)
        assertEquals(in24.greeting, in12.greeting)
    }

    @Test
    fun `seconds and milliseconds are truncated, not rounded`() {
        val instant = ZonedDateTime.of(2026, 11, 13, 19, 42, 59, 999_000_000, moscow.toZoneId()).toInstant().toEpochMilli()
        assertEquals("19:42", ClockFormatter.format(instant, moscow, true).time)
    }

    @Test
    fun `the same instant reads differently in different zones`() {
        // 2026-11-13 21:30 UTC
        val instant = ZonedDateTime.of(2026, 11, 13, 21, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()

        assertEquals(ClockState("21:30", "13 ноября, пятница", "Добрый вечер"), ClockFormatter.format(instant, utc, true))
        assertEquals(ClockState("00:30", "14 ноября, суббота", "Доброй ночи"), ClockFormatter.format(instant, moscow, true))
        assertEquals(
            ClockState("07:30", "14 ноября, суббота", "Доброе утро"),
            ClockFormatter.format(instant, zone("Asia/Vladivostok"), true),
        )
        assertEquals(
            ClockState("16:30", "13 ноября, пятница", "Добрый день"),
            ClockFormatter.format(instant, zone("America/New_York"), true),
        )
        // Half-hour and quarter-hour offsets.
        assertEquals("03:00", ClockFormatter.format(instant, zone("Asia/Kolkata"), true).time)
        assertEquals("03:15", ClockFormatter.format(instant, zone("Asia/Kathmandu"), true).time)
        // UTC+14: already the day after tomorrow's eve.
        assertEquals(
            ClockState("11:30", "14 ноября, суббота", "Доброе утро"),
            ClockFormatter.format(instant, zone("Pacific/Kiritimati"), true),
        )
    }

    @Test
    fun `midnight starts a new day`() {
        assertEquals(ClockState("23:59", "13 ноября, пятница", "Доброй ночи"), format(2026, 11, 13, 23, 59, moscow))
        assertEquals(ClockState("00:00", "14 ноября, суббота", "Доброй ночи"), format(2026, 11, 14, 0, 0, moscow))
    }

    @Test
    fun `month and year boundaries`() {
        assertEquals("31 декабря, четверг", format(2026, 12, 31, 23, 59, moscow).date)
        assertEquals("1 января, пятница", format(2027, 1, 1, 0, 0, moscow).date)
        assertEquals("30 ноября, понедельник", format(2026, 11, 30, 12, 0, moscow).date)
        assertEquals("1 декабря, вторник", format(2026, 12, 1, 12, 0, moscow).date)
        assertEquals("31 января, воскресенье", format(2027, 1, 31, 12, 0, moscow).date)
        assertEquals("1 февраля, понедельник", format(2027, 2, 1, 12, 0, moscow).date)
    }

    @Test
    fun `leap days`() {
        assertEquals("28 февраля, воскресенье", format(2027, 2, 28, 12, 0, moscow).date)
        assertEquals("1 марта, понедельник", format(2027, 3, 1, 12, 0, moscow).date)
        assertEquals("29 февраля, вторник", format(2028, 2, 29, 12, 0, moscow).date)
        assertEquals("1 марта, среда", format(2028, 3, 1, 12, 0, moscow).date)
        // 2100 is divisible by 100 but not by 400: no leap day.
        assertEquals("28 февраля, воскресенье", format(2100, 2, 28, 12, 0, utc).date)
        assertEquals("1 марта, понедельник", format(2100, 3, 1, 0, 0, utc).date)
        // 2000 was a leap year.
        assertEquals("29 февраля, вторник", format(2000, 2, 29, 12, 0, utc).date)
    }

    @Test
    fun `every month has its genitive name`() {
        val expected = listOf(
            "января", "февраля", "марта", "апреля", "мая", "июня",
            "июля", "августа", "сентября", "октября", "ноября", "декабря",
        )
        for (month in 1..12) {
            assertEquals("15 ${expected[month - 1]}", format(2026, month, 15, 12, 0, moscow).date.substringBefore(','))
        }
    }

    @Test
    fun `every weekday has its name`() {
        // 9 November 2026 is a Monday.
        val expected = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")
        for (offset in 0..6) {
            assertEquals(expected[offset], format(2026, 11, 9 + offset, 12, 0, moscow).date.substringAfter(", "))
        }
    }

    @Test
    fun `greeting follows the local hour`() {
        val expected = mapOf(
            0 to "Доброй ночи", 4 to "Доброй ночи",
            5 to "Доброе утро", 11 to "Доброе утро",
            12 to "Добрый день", 17 to "Добрый день",
            18 to "Добрый вечер", 22 to "Добрый вечер",
            23 to "Доброй ночи",
        )
        for ((hour, greeting) in expected) {
            assertEquals("hour $hour", greeting, format(2026, 11, 13, hour, 59, moscow).greeting)
            assertEquals("hour $hour", greeting, ClockFormatter.greetingFor(hour))
        }
    }

    @Test
    fun `the epoch itself and instants before it`() {
        assertEquals(ClockState("00:00", "1 января, четверг", "Доброй ночи"), ClockFormatter.format(0L, utc, true))
        assertEquals(ClockState("23:59", "31 декабря, среда", "Доброй ночи"), ClockFormatter.format(-1L, utc, true))
        assertEquals("31 декабря, среда", ClockFormatter.format(0L, zone("America/New_York"), true).date)
    }

    @Test
    fun `daylight saving shifts come from the zone`() {
        val berlin = zone("Europe/Berlin")
        // 2026-03-29 00:30 UTC is 01:30 CET; an hour later the clocks have jumped to 03:30 CEST.
        val before = ZonedDateTime.of(2026, 3, 29, 0, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals("01:30", ClockFormatter.format(before, berlin, true).time)
        assertEquals("03:30", ClockFormatter.format(before + 3_600_000L, berlin, true).time)
    }

    @Test
    fun `the result does not depend on the default locale or zone`() {
        val savedLocale = Locale.getDefault()
        val savedZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-SA"))
            TimeZone.setDefault(zone("Pacific/Honolulu"))
            assertEquals(
                ClockState(time = "19:42", date = "13 ноября, пятница", greeting = "Добрый вечер"),
                format(2026, 11, 13, 19, 42, moscow),
            )
        } finally {
            Locale.setDefault(savedLocale)
            TimeZone.setDefault(savedZone)
        }
    }

    @Test
    fun `random instants agree with java time`() {
        val zones = listOf(
            "UTC", "Europe/Moscow", "Europe/Kaliningrad", "Asia/Yekaterinburg", "Asia/Vladivostok", "Asia/Kamchatka",
            "Asia/Kolkata", "Asia/Kathmandu", "America/New_York", "America/St_Johns", "Pacific/Kiritimati", "Pacific/Pago_Pago",
            "Europe/Berlin", "Australia/Lord_Howe",
        ).map(::zone)
        val months = listOf(
            "января", "февраля", "марта", "апреля", "мая", "июня",
            "июля", "августа", "сентября", "октября", "ноября", "декабря",
        )
        // java.time.DayOfWeek: 1 = Monday ... 7 = Sunday
        val weekdays = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")
        val random = Random(20261113)

        repeat(20_000) { round ->
            // Before 1970 only UTC is compared: for those years the two JDK zone implementations do not promise to
            // agree on local-mean-time offsets, and the point here is our arithmetic, not their history tables.
            val beforeEpoch = round % 4 == 0
            val instant = if (beforeEpoch) random.nextLong(FROM_MILLIS, 0L) else random.nextLong(0L, TO_MILLIS)
            val zone = if (beforeEpoch) utc else zones.random(random)
            val expected = java.time.Instant.ofEpochMilli(instant).atZone(zone.toZoneId())
            val actual = ClockFormatter.format(instant, zone, is24Hour = true)

            val context = "instant $instant in ${zone.id}"
            assertEquals(context, "%02d:%02d".format(Locale.ROOT, expected.hour, expected.minute), actual.time)
            assertEquals(
                context,
                "${expected.dayOfMonth} ${months[expected.monthValue - 1]}, ${weekdays[expected.dayOfWeek.value - 1]}",
                actual.date,
            )
            assertEquals(context, ClockFormatter.greetingFor(expected.hour), actual.greeting)

            val hour12 = if (expected.hour % 12 == 0) 12 else expected.hour % 12
            assertEquals(
                context,
                "%d:%02d".format(Locale.ROOT, hour12, expected.minute),
                ClockFormatter.format(instant, zone, is24Hour = false).time,
            )
        }
    }

    private fun format(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        zone: TimeZone,
        is24Hour: Boolean = true,
    ): ClockState {
        val instant = LocalDateTime.of(year, month, day, hour, minute).atZone(zone.toZoneId()).toInstant().toEpochMilli()
        return ClockFormatter.format(instant, zone, is24Hour)
    }

    private fun zone(id: String): TimeZone = TimeZone.getTimeZone(id).also { check(it.id == id) { "unknown zone $id" } }

    private companion object {
        /** 1600-01-01 .. 2200-01-01, wide enough to cross every kind of leap-year rule. */
        const val FROM_MILLIS = -11_676_096_000_000L
        const val TO_MILLIS = 7_258_118_400_000L
    }
}
