package tv.monotv.launcher.data.system

import java.util.TimeZone
import tv.monotv.launcher.core.ClockState

/**
 * Turns an instant into the three strings of the home screen clock: time, Russian date line and greeting.
 *
 * The calendar arithmetic and the month / weekday names are our own. TV firmwares ship trimmed or outdated locale
 * data (and occasionally start in English after a reset), while the launcher's interface is Russian regardless of
 * the system language — so nothing here may depend on what `SimpleDateFormat` happens to know on a given device.
 * Only the UTC offset comes from the platform ([TimeZone.getOffset]), because time-zone rules do change.
 *
 * Pure and allocation-light: it runs on the main thread once a minute.
 */
object ClockFormatter {

    private const val MILLIS_PER_MINUTE = 60_000L
    private const val MILLIS_PER_HOUR = 3_600_000L
    private const val MILLIS_PER_DAY = 86_400_000L

    /** 1 January 1970 was a Thursday; index 0 of [WEEKDAYS] is Sunday. */
    private const val EPOCH_WEEKDAY = 4

    /** Genitive case — "13 ноября", not "13 ноябрь". */
    private val MONTHS_GENITIVE = arrayOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )

    private val WEEKDAYS = arrayOf("воскресенье", "понедельник", "вторник", "среда", "четверг", "пятница", "суббота")

    /**
     * @param epochMillis instant to show, milliseconds since the Unix epoch
     * @param zone        the device time zone; daylight-saving shifts are taken from it
     * @param is24Hour    the system "24-hour format" setting. In 12-hour mode the hour is shown without a leading
     *                    zero and without an AM / PM suffix — the greeting next to it already tells morning from
     *                    evening, and a suffix has no natural Russian form.
     */
    fun format(epochMillis: Long, zone: TimeZone, is24Hour: Boolean): ClockState {
        val localMillis = epochMillis + zone.getOffset(epochMillis)
        val epochDay = localMillis.floorDiv(MILLIS_PER_DAY)
        val millisOfDay = localMillis.mod(MILLIS_PER_DAY)
        val hour = (millisOfDay / MILLIS_PER_HOUR).toInt()
        val minute = ((millisOfDay % MILLIS_PER_HOUR) / MILLIS_PER_MINUTE).toInt()

        return ClockState(
            time = formatTime(hour, minute, is24Hour),
            date = formatDate(epochDay),
            greeting = greetingFor(hour),
        )
    }

    /** Greeting for a local hour of day (0..23). Night spans midnight: 23:00 up to, but not including, 05:00. */
    fun greetingFor(hour: Int): String = when (hour) {
        in 5..11 -> "Доброе утро"
        in 12..17 -> "Добрый день"
        in 18..22 -> "Добрый вечер"
        else -> "Доброй ночи"
    }

    private fun formatTime(hour: Int, minute: Int, is24Hour: Boolean): String {
        val text = StringBuilder(5)
        if (is24Hour) {
            if (hour < 10) text.append('0')
            text.append(hour)
        } else {
            // 0:xx and 12:xx are both shown as "12:xx", the way every 12-hour clock does.
            text.append(if (hour % 12 == 0) 12 else hour % 12)
        }
        text.append(':')
        if (minute < 10) text.append('0')
        text.append(minute)
        return text.toString()
    }

    /**
     * "13 ноября, пятница" for a day counted from 1 January 1970.
     *
     * Days-to-civil-date conversion for the proleptic Gregorian calendar (the well-known era / day-of-era scheme:
     * 400-year eras of 146 097 days, years counted from 1 March so the leap day falls at the end).
     */
    private fun formatDate(epochDay: Long): String {
        val shifted = epochDay + 719_468 // days from 0000-03-01 to 1970-01-01
        val dayOfEra = shifted.mod(146_097L)
        val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100) // 0 = 1 March
        val monthFromMarch = (5 * dayOfYear + 2) / 153 // 0 = March ... 11 = February
        val dayOfMonth = (dayOfYear - (153 * monthFromMarch + 2) / 5 + 1).toInt()
        val month = ((monthFromMarch + 2) % 12).toInt() // 0 = January
        val weekday = (epochDay + EPOCH_WEEKDAY).mod(7L).toInt()
        return "$dayOfMonth ${MONTHS_GENITIVE[month]}, ${WEEKDAYS[weekday]}"
    }
}
