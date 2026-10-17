package com.nuvio.app.features.whatsnew

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.periodUntil
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

internal enum class LastCheckedUnit {
    JUST_NOW,
    MINUTE,
    HOUR,
    DAY,
    WEEK,
    MONTH,
    YEAR,
}

internal data class LastCheckedAge(
    val amount: Long,
    val unit: LastCheckedUnit,
)

internal fun calculateLastCheckedAge(
    fetchedAtMillis: Long,
    nowMillis: Long,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): LastCheckedAge {
    val effectiveNowMillis = nowMillis.coerceAtLeast(fetchedAtMillis)
    val elapsedMillis = effectiveNowMillis - fetchedAtMillis

    val minuteMillis = 60_000L
    val hourMillis = 60L * minuteMillis
    val dayMillis = 24L * hourMillis

    if (elapsedMillis < minuteMillis) {
        return LastCheckedAge(amount = 0L, unit = LastCheckedUnit.JUST_NOW)
    }

    if (elapsedMillis < hourMillis) {
        return LastCheckedAge(
            amount = elapsedMillis / minuteMillis,
            unit = LastCheckedUnit.MINUTE,
        )
    }

    val start = Instant.fromEpochMilliseconds(fetchedAtMillis)
    val end = Instant.fromEpochMilliseconds(effectiveNowMillis)
    val calendarPeriod = start.periodUntil(end, timeZone)

    // Whole calendar months use a clamped anchor day, so Jan 31 -> Feb 28 is one month.
    val months = completedCalendarMonths(
        start = start.toLocalDateTime(timeZone),
        end = end.toLocalDateTime(timeZone),
    )

    return when {
        months >= 12 -> LastCheckedAge(
            amount = (months / 12).toLong(),
            unit = LastCheckedUnit.YEAR,
        )
        months >= 1 -> LastCheckedAge(
            amount = months.toLong(),
            unit = LastCheckedUnit.MONTH,
        )
        calendarPeriod.days >= 7 -> LastCheckedAge(
            amount = (calendarPeriod.days / 7L),
            unit = LastCheckedUnit.WEEK,
        )
        calendarPeriod.days >= 1 -> LastCheckedAge(
            amount = calendarPeriod.days.toLong(),
            unit = LastCheckedUnit.DAY,
        )
        elapsedMillis >= dayMillis -> LastCheckedAge(
            amount = elapsedMillis / dayMillis,
            unit = LastCheckedUnit.DAY,
        )
        else -> LastCheckedAge(
            amount = elapsedMillis / hourMillis,
            unit = LastCheckedUnit.HOUR,
        )
    }
}

private fun completedCalendarMonths(start: LocalDateTime, end: LocalDateTime): Int {
    var months = (end.year - start.year) * 12 + (end.monthNumber - start.monthNumber)
    if (months <= 0) return 0
    val lastDayOfEndMonth = LocalDate(end.year, end.monthNumber, 1)
        .plus(DatePeriod(months = 1))
        .minus(DatePeriod(days = 1))
        .day
    val anchorDay = minOf(start.day, lastDayOfEndMonth)
    val endBeforeAnchor = end.day < anchorDay ||
        (end.day == anchorDay && end.time < start.time)
    if (endBeforeAnchor) months -= 1
    return months.coerceAtLeast(0)
}
