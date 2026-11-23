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

fun fmtRate(d: Double): String =
    BigDecimal(d).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

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

    /** Week starts on Saturday, as in the office forms. */
    fun weekStart(date: String): String {
        val d = LocalDate.parse(date)
        val offset = (d.dayOfWeek.value - DayOfWeek.SATURDAY.value + 7) % 7
        return d.minusDays(offset.toLong()).toString()
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

/** Arabic number-to-words ("تفقيط") for amounts, as written on Yemeni vouchers. */
object Tafqeet {
    private val ones = listOf("", "واحد", "اثنان", "ثلاثة", "أربعة", "خمسة", "ستة", "سبعة", "ثمانية", "تسعة")
    private val teens = listOf(
        "عشرة", "أحد عشر", "اثنا عشر", "ثلاثة عشر", "أربعة عشر", "خمسة عشر",
        "ستة عشر", "سبعة عشر", "ثمانية عشر", "تسعة عشر"
    )
    private val tens = listOf("", "", "عشرون", "ثلاثون", "أربعون", "خمسون", "ستون", "سبعون", "ثمانون", "تسعون")
    private val hundreds = listOf(
        "", "مائة", "مائتان", "ثلاثمائة", "أربعمائة", "خمسمائة", "ستمائة", "سبعمائة", "ثمانمائة", "تسعمائة"
    )

    private class Scale(val one: String, val two: String, val plural: String)

    private val scales = listOf(
        Scale("ألف", "ألفان", "آلاف"),
        Scale("مليون", "مليونان", "ملايين"),
        Scale("مليار", "ملياران", "مليارات")
    )

    private fun below1000(n: Int): String {
        val parts = mutableListOf<String>()
        val h = n / 100
        val r = n % 100
        if (h > 0) parts += hundreds[h]
        when {
            r == 0 -> {}
            r < 10 -> parts += ones[r]
            r < 20 -> parts += teens[r - 10]
            r % 10 == 0 -> parts += tens[r / 10]
            else -> parts += "${ones[r % 10]} و${tens[r / 10]}"
        }
        return parts.joinToString(" و")
    }

    private fun scaled(n: Int, s: Scale): String = when {
        n == 1 -> s.one
        n == 2 -> s.two
        n in 3..10 -> "${below1000(n)} ${s.plural}"
        else -> "${below1000(n)} ${s.one}"
    }

    fun number(value: Long): String {
        if (value == 0L) return "صفر"
        var n = value
        val groups = mutableListOf<Int>()
        while (n > 0) {
            groups += (n % 1000).toInt()
            n /= 1000
        }
        val parts = mutableListOf<String>()
        for (i in groups.indices.reversed()) {
            val g = groups[i]
            if (g == 0) continue
            parts += if (i == 0) below1000(g) else scaled(g, scales[minOf(i - 1, scales.size - 1)])
        }
        return parts.joinToString(" و")
    }

    /** minor = amount x 100 */
    fun amount(minor: Long, currencyName: String): String {
        if (minor <= 0) return ""
        val whole = minor / 100
        val frac = (minor % 100).toInt()
        val sb = StringBuilder("فقط ")
        if (whole > 0) sb.append(number(whole)).append(' ').append(currencyName)
        if (frac > 0) {
            if (whole > 0) sb.append(" و")
            sb.append(number(frac.toLong())).append(" من مائة")
        }
        sb.append(" لا غير")
        return sb.toString()
    }
}

/** keeps digits and a single decimal point (max 2 decimals); converts Arabic digits */
fun cleanAmountInput(s: String): String {
    val n = normalizeDigits(s)
    val sb = StringBuilder()
    var dot = false
    var decimals = 0
    for (ch in n) {
        when {
            ch.isDigit() && ch in '0'..'9' -> {
                if (dot) { if (decimals < 2) { sb.append(ch); decimals++ } } else sb.append(ch)
            }
            ch == '.' && !dot -> { dot = true; sb.append(if (sb.isEmpty()) "0." else ".") }
        }
    }
    return sb.toString().take(15)
}

/** Exchange-rate lookup over the dated rate table. A rate is used from its date until a newer one is entered. */
object Fx {
    /** 1 [from] = result [to], using the newest rate dated on/before [on] (direct or inverse); null if none. */
    fun factor(rates: List<com.alshihab.fieldledger.data.FxRate>, from: String, to: String, on: String): Double? {
        if (from == to) return 1.0
        val best = rates.filter {
            it.date <= on && ((it.fromCur == from && it.toCur == to) || (it.fromCur == to && it.toCur == from))
        }.maxWithOrNull(compareBy({ it.date }, { it.id })) ?: return null
        if (best.rate <= 0.0) return null
        return if (best.fromCur == from) best.rate else 1.0 / best.rate
    }

    /**
     * The natural way to write a pair: "1 USD = 530 YER" rather than "1 YER = 0.0019 USD".
     * Returns true when [a] should be on the left (1 a = x b).
     */
    fun aOnLeft(rates: List<com.alshihab.fieldledger.data.FxRate>, a: String, b: String, decimalsOf: (String) -> Int): Boolean {
        rates.firstOrNull { (it.fromCur == a && it.toCur == b) || (it.fromCur == b && it.toCur == a) }?.let { return it.fromCur == a }
        val da = decimalsOf(a)
        val db = decimalsOf(b)
        if (da != db) return da > db       // rials (0 decimals) go on the right
        return true
    }
}
