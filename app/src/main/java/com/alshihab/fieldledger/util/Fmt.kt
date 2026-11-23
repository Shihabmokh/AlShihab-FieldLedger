package com.alshihab.fieldledger.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/** Converts Arabic-Indic / Persian digits and separators typed on Arabic keyboards to ASCII. */
fun normalizeDigits(s: String): String = buildString {
    for (ch in s) {
        append(
            when (ch) {
                in '٠'..'٩' -> '0' + (ch - '٠')
                in '۰'..'۹' -> '0' + (ch - '۰')
                '٫' -> '.'
                '٬', '،' -> ','
                else -> ch
            }
        )
    }
}

object Money {
    /** "1,250.5" -> 125050 (minor units). Empty -> 0. Invalid -> null. */
    fun parse(s: String): Long? {
        val n = normalizeDigits(s).replace(",", "").trim()
        if (n.isEmpty()) return 0L
        return try {
            BigDecimal(n).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
        } catch (e: Exception) {
            null
        }
    }

    fun fmt(minor: Long, decimals: Int = 2): String {
        val bd = BigDecimal.valueOf(minor).movePointLeft(2).setScale(decimals, RoundingMode.HALF_UP)
        val pattern = if (decimals > 0) "#,##0." + "0".repeat(decimals) else "#,##0"
        return DecimalFormat(pattern, DecimalFormatSymbols(Locale.US)).format(bd)
    }

    /** value for an edit field (no grouping, no trailing zeros) */
    fun toInput(minor: Long): String =
        if (minor == 0L) "" else BigDecimal.valueOf(minor).movePointLeft(2).stripTrailingZeros().toPlainString()
}

fun parseDecimal(s: String): Double? {
    val n = normalizeDigits(s).replace(",", "").trim()
    if (n.isEmpty()) return 0.0
    return n.toDoubleOrNull()
}

fun fmtNum(d: Double): String =
    if (d == Math.floor(d)) d.toLong().toString() else BigDecimal(d).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

object Dates {
    fun today(): String = LocalDate.now().toString()
    fun shift(date: String, days: Long): String = LocalDate.parse(date).plusDays(days).toString()
    fun isFuture(date: String): Boolean = LocalDate.parse(date).isAfter(LocalDate.now())

    fun dayName(date: String): String = when (LocalDate.parse(date).dayOfWeek) {
        DayOfWeek.SATURDAY -> "السبت"
        DayOfWeek.SUNDAY -> "الأحد"
        DayOfWeek.MONDAY -> "الاثنين"
        DayOfWeek.TUESDAY -> "الثلاثاء"
        DayOfWeek.WEDNESDAY -> "الأربعاء"
        DayOfWeek.THURSDAY -> "الخميس"
        DayOfWeek.FRIDAY -> "الجمعة"
        else -> ""
    }

    fun label(date: String) = "${dayName(date)}  $date"

    fun dateTime(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

    fun duration(ms: Long): String {
        val totalMin = ms / 60_000
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "$h ساعة و $m دقيقة" else "$m دقيقة"
    }
}
