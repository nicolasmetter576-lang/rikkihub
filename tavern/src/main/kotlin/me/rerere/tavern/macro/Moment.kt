package me.rerere.tavern.macro

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.round

/**
 * moment.js 的最小移植（en 区域），只为 `{{time}}`/`{{date}}`/`{{weekday}}`/`{{isotime}}`/`{{isodate}}`
 * /`{{datetimeformat}}`/`{{idleDuration}}`/`{{timeDiff}}` 服务。
 * 覆盖：格式化令牌（含 LT/LL/LLL/LLLL/l 系、Do 序数、A/a、X/x、Z/ZZ、[literal]）、
 * `utcOffset`（|n|<16 按小时，否则按分钟，moment 语义）、`humanize`（默认阈值 ss=44,m=45,h=22,d=26,M=11）。
 * 解析只支持 ISO / `yyyy-MM-dd HH:mm:ss` 等规范形态（够差分用）。
 */
internal object Moment {
    private val MONTHS = arrayOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
    private val MONTHS_SHORT = MONTHS.map { it.take(3) }
    private val DAYS = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    private val DAYS_SHORT = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    private val DAYS_MIN = arrayOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")

    // en locale 的本地化格式；与 moment 相同
    private val LOCALE_FORMATS = mapOf(
        "LTS" to "h:mm:ss A", "LT" to "h:mm A", "L" to "MM/DD/YYYY",
        "LL" to "MMMM D, YYYY", "LLL" to "MMMM D, YYYY h:mm A", "LLLL" to "dddd, MMMM D, YYYY h:mm A",
        "l" to "M/D/YYYY", "ll" to "MMM D, YYYY", "lll" to "MMM D, YYYY h:mm A", "llll" to "ddd, MMM D, YYYY h:mm A",
    )

    // moment 令牌：长令牌优先；\S 兜底让裸字母按令牌走（moment 语义：[literal] 才原样输出）
    private val TOKEN = Regex(
        "LTS|LT|LLLL|LLL|LL|llll|lll|ll|l" +
            "|YYYY|YY|Y|x|X" +
            "|Mo|MMMM|MMM|MM|M" +
            "|Do|DD|D|do|dddd|ddd|dd|d" +
            "|HH|H|hh|h|kk|k|mm|m|ss|s" +
            "|SSSS|SSS|SS|S" +
            "|A|a|ZZ|Z|zz|z" +
            "|\\[[^\\[\\]]*\\]|\\S",
    )

    fun format(millis: Long, zone: java.time.ZoneId, pattern: String): String {
        val dt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)
        // moment expandFormat：先把本地化令牌展开成普通格式串，再统一格式化（长令牌优先）
        var expanded = pattern
        for ((k, v) in LOCALE_FORMATS.entries.sortedByDescending { it.key.length }) {
            if (expanded.contains(k)) expanded = expanded.replace(k, v)
        }
        val out = StringBuilder()
        var i = 0
        while (i < expanded.length) {
            val m = TOKEN.find(expanded, i) ?: break
            out.append(expanded.substring(i, m.range.first))
            out.append(formatToken(m.value, dt, millis))
            i = m.range.last + 1
        }
        if (i < expanded.length) out.append(expanded.substring(i))
        return out.toString()
    }

    private fun formatToken(tok: String, dt: ZonedDateTime, millis: Long): String = when {
        tok.startsWith('[') && tok.endsWith(']') -> tok.substring(1, tok.length - 1)
        else -> when (tok) {
            "YYYY" -> "%04d".format(dt.year)
            "YY" -> "%02d".format(dt.year % 100)
            "Y" -> dt.year.toString()
            "MMMM" -> MONTHS[dt.monthValue - 1]
            "MMM" -> MONTHS_SHORT[dt.monthValue - 1]
            "MM" -> "%02d".format(dt.monthValue)
            "Mo" -> ordinal(dt.monthValue)
            "M" -> dt.monthValue.toString()
            "DD" -> "%02d".format(dt.dayOfMonth)
            "Do" -> ordinal(dt.dayOfMonth)
            "D" -> dt.dayOfMonth.toString()
            "dddd" -> DAYS[dt.dayOfWeek.value % 7]
            "ddd" -> DAYS_SHORT[dt.dayOfWeek.value % 7]
            "dd" -> DAYS_MIN[dt.dayOfWeek.value % 7]
            "do" -> ordinal(dt.dayOfWeek.value % 7)
            "d" -> (dt.dayOfWeek.value % 7).toString()
            "HH" -> "%02d".format(dt.hour)
            "H" -> dt.hour.toString()
            "hh" -> "%02d".format(h12(dt.hour))
            "h" -> h12(dt.hour).toString()
            "kk" -> "%02d".format(if (dt.hour == 0) 24 else dt.hour)
            "k" -> (if (dt.hour == 0) 24 else dt.hour).toString()
            "mm" -> "%02d".format(dt.minute)
            "m" -> dt.minute.toString()
            "ss" -> "%02d".format(dt.second)
            "s" -> dt.second.toString()
            "SSSS" -> "%04d".format(dt.nano / 100_000)
            "SSS" -> "%03d".format(dt.nano / 1_000_000)
            "SS" -> "%02d".format(dt.nano / 10_000_000)
            "S" -> (dt.nano / 100_000_000).toString()
            "A" -> if (dt.hour < 12) "AM" else "PM"
            "a" -> if (dt.hour < 12) "am" else "pm"
            "X" -> (millis / 1000).toString()
            "x" -> millis.toString()
            "Z", "ZZ" -> offsetString(dt.offset, tok == "Z")
            "z", "zz" -> dt.zone.id
            else -> tok
        }
    }

    private fun h12(h: Int): Int = when {
        h == 0 -> 12
        h > 12 -> h - 12
        else -> h
    }

    private fun offsetString(offset: ZoneOffset, colon: Boolean): String {
        val total = offset.totalSeconds
        val sign = if (total < 0) "-" else "+"
        val a = abs(total)
        return if (colon) "%s%02d:%02d".format(sign, a / 3600, (a % 3600) / 60)
        else "%s%02d%02d".format(sign, a / 3600, (a % 3600) / 60)
    }

    private fun ordinal(n: Int): String {
        val b = n % 10
        val suffix = when {
            n in 11..13 -> "th"
            b == 1 -> "st"
            b == 2 -> "nd"
            b == 3 -> "rd"
            else -> "th"
        }
        return "$n$suffix"
    }

    /** `moment().utcOffset(n)`：|n|<16 按小时，否则按分钟。 */
    fun offsetZone(offsetArg: Int): ZoneOffset {
        val minutes = if (abs(offsetArg) < 16) offsetArg * 60L else offsetArg.toLong()
        return ZoneOffset.ofTotalSeconds((minutes * 60).toInt())
    }

    /** `moment(string)`：覆盖差分用例用的规范写法；解析不出返回 null（= moment.invalid()）。 */
    fun parse(text: String, zone: java.time.ZoneId): Long? {
        val t = text.trim()
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mmXXX",
            "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm",
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd",
            "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd",
        )
        for (p in patterns) {
            try {
                val ldt = LocalDateTime.parse(t, java.time.format.DateTimeFormatter.ofPattern(p))
                return ldt.atZone(zone).toInstant().toEpochMilli()
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * moment.duration.humanize（en、默认阈值）。`withSuffix` = humanize(true)：正数 'in X'，负数 'X ago'。
     * 与 moment 一致：先对时长取绝对值分级，再按原始符号决定 in/ago。
     */
    fun humanize(durationMillis: Double, withSuffix: Boolean): String {
        val d = abs(durationMillis)
        val seconds = round(d / 1000).toLong()
        val minutes = round(d / 60000).toLong()
        val hours = round(d / 3600000).toLong()
        val days = round(d / 86400000).toLong()
        // moment 的 daysToMonths: days * 4800 / 146097；years ≈ days / 365.2425
        val months = round(d / 86400000 * 4800 / 146097).toLong()
        val years = round(d / 86400000 * 400 / 146097).toLong()

        val text = when {
            seconds <= 44 -> "a few seconds"
            seconds < 45 -> "$seconds seconds"
            minutes <= 1 -> "a minute"
            minutes < 45 -> "$minutes minutes"
            hours <= 1 -> "an hour"
            hours < 22 -> "$hours hours"
            days <= 1 -> "a day"
            days < 26 -> "$days days"
            months <= 1 -> "a month"
            months < 11 -> "$months months"
            years <= 1 -> "a year"
            else -> "$years years"
        }
        if (!withSuffix) return text
        return if (durationMillis < 0) "$text ago" else "in $text"
    }
}
