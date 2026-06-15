/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.roastedroot.sqlite4j.date

import java.util.*

/**
 * Date and time formatting utilities and constants.
 * 
 * 
 * Formatting is performed using the thread-safe org.apache.commons.lang3.time.FastDateFormat
 * class.
 * 
 * 
 * Note that the JDK has a bug wherein calling Calendar.get(int) will override any previously
 * called Calendar.clear() calls. See LANG-755.
 * 
 * @since 2.0
 * @version $Id$
 */
object DateFormatUtils {
    /** The UTC time zone (often referred to as GMT). This is private as it is mutable.  */
    private val UTC_TIME_ZONE: TimeZone = TimeZone.getTimeZone("GMT")

    /**
     * ISO 8601 formatter for date-time without time zone. The format used is `yyyy-MM-dd'T'HH:mm:ss`.
     */
    val ISO_DATETIME_FORMAT: FastDateFormat = FastDateFormat.Companion.getInstance("yyyy-MM-dd'T'HH:mm:ss")

    /**
     * ISO 8601 formatter for date-time with time zone. The format used is `yyyy-MM-dd'T'HH:mm:ssZZ`.
     */
    val ISO_DATETIME_TIME_ZONE_FORMAT: FastDateFormat = FastDateFormat.Companion.getInstance("yyyy-MM-dd'T'HH:mm:ssZZ")

    /** ISO 8601 formatter for date without time zone. The format used is `yyyy-MM-dd`.  */
    val ISO_DATE_FORMAT: FastDateFormat = FastDateFormat.Companion.getInstance("yyyy-MM-dd")

    /**
     * ISO 8601-like formatter for date with time zone. The format used is `yyyy-MM-ddZZ`.
     * This pattern does not comply with the formal ISO 8601 specification as the standard does not
     * allow a time zone without a time.
     */
    val ISO_DATE_TIME_ZONE_FORMAT: FastDateFormat = FastDateFormat.Companion.getInstance("yyyy-MM-ddZZ")

    /** ISO 8601 formatter for time without time zone. The format used is `'T'HH:mm:ss`.  */
    val ISO_TIME_FORMAT: FastDateFormat = FastDateFormat.Companion.getInstance("'T'HH:mm:ss")

    /** ISO 8601 formatter for time with time zone. The format used is `'T'HH:mm:ssZZ`.  */
    val ISO_TIME_TIME_ZONE_FORMAT: FastDateFormat = FastDateFormat.Companion.getInstance("'T'HH:mm:ssZZ")

    /**
     * ISO 8601-like formatter for time without time zone. The format used is `HH:mm:ss`. This
     * pattern does not comply with the formal ISO 8601 specification as the standard requires the
     * 'T' prefix for times.
     */
    val ISO_TIME_NO_T_FORMAT: FastDateFormat = FastDateFormat.Companion.getInstance("HH:mm:ss")

    /**
     * ISO 8601-like formatter for time with time zone. The format used is `HH:mm:ssZZ`. This
     * pattern does not comply with the formal ISO 8601 specification as the standard requires the
     * 'T' prefix for times.
     */
    val ISO_TIME_NO_T_TIME_ZONE_FORMAT: FastDateFormat = FastDateFormat.Companion.getInstance("HH:mm:ssZZ")

    /**
     * SMTP (and probably other) date headers. The format used is `EEE, dd MMM yyyy HH:mm:ss Z` in US locale.
     */
    val SMTP_DATETIME_FORMAT: FastDateFormat =
        FastDateFormat.Companion.getInstance("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)

    /**
     * Formats a date/time into a specific pattern using the UTC time zone.
     * 
     * @param millis the date to format expressed in milliseconds
     * @param pattern the pattern to use to format the date, not null
     * @return the formatted date
     */
    fun formatUTC(millis: Long, pattern: String): String {
        return format(Date(millis), pattern, UTC_TIME_ZONE, null)
    }

    /**
     * Formats a date/time into a specific pattern using the UTC time zone.
     * 
     * @param date the date to format, not null
     * @param pattern the pattern to use to format the date, not null
     * @return the formatted date
     */
    fun formatUTC(date: Date, pattern: String): String {
        return format(date, pattern, UTC_TIME_ZONE, null)
    }

    /**
     * Formats a date/time into a specific pattern using the UTC time zone.
     * 
     * @param millis the date to format expressed in milliseconds
     * @param pattern the pattern to use to format the date, not null
     * @param locale the locale to use, may be `null`
     * @return the formatted date
     */
    fun formatUTC(
        millis: Long, pattern: String, locale: Locale?
    ): String {
        return format(Date(millis), pattern, UTC_TIME_ZONE, locale)
    }

    /**
     * Formats a date/time into a specific pattern using the UTC time zone.
     * 
     * @param date the date to format, not null
     * @param pattern the pattern to use to format the date, not null
     * @param locale the locale to use, may be `null`
     * @return the formatted date
     */
    fun formatUTC(
        date: Date,
        pattern: String,
        locale: Locale?
    ): String {
        return format(date, pattern, UTC_TIME_ZONE, locale)
    }

    /**
     * Formats a date/time into a specific pattern.
     * 
     * @param millis the date to format expressed in milliseconds
     * @param pattern the pattern to use to format the date, not null
     * @return the formatted date
     */
    fun format(millis: Long, pattern: String): String {
        return format(Date(millis), pattern, null, null)
    }

    /**
     * Formats a date/time into a specific pattern in a time zone.
     * 
     * @param millis the time expressed in milliseconds
     * @param pattern the pattern to use to format the date, not null
     * @param timeZone the time zone to use, may be `null`
     * @return the formatted date
     */
    fun format(
        millis: Long, pattern: String, timeZone: TimeZone?
    ): String {
        return format(Date(millis), pattern, timeZone, null)
    }

    /**
     * Formats a date/time into a specific pattern in a locale.
     * 
     * @param millis the date to format expressed in milliseconds
     * @param pattern the pattern to use to format the date, not null
     * @param locale the locale to use, may be `null`
     * @return the formatted date
     */
    fun format(
        millis: Long, pattern: String, locale: Locale?
    ): String {
        return format(Date(millis), pattern, null, locale)
    }

    /**
     * Formats a date/time into a specific pattern in a locale.
     * 
     * @param date the date to format, not null
     * @param pattern the pattern to use to format the date, not null
     * @param locale the locale to use, may be `null`
     * @return the formatted date
     */
    fun format(
        date: Date,
        pattern: String,
        locale: Locale?
    ): String {
        return format(date, pattern, null, locale)
    }

    /**
     * Formats a calendar into a specific pattern in a locale.
     * 
     * @param calendar the calendar to format, not null
     * @param pattern the pattern to use to format the calendar, not null
     * @param locale the locale to use, may be `null`
     * @return the formatted calendar
     * @see FastDateFormat.format
     * @since 2.4
     */
    fun format(
        calendar: Calendar,
        pattern: String,
        locale: Locale?
    ): String {
        return format(calendar, pattern, null, locale)
    }

    /**
     * Formats a date/time into a specific pattern in a time zone and locale.
     * 
     * @param millis the date to format expressed in milliseconds
     * @param pattern the pattern to use to format the date, not null
     * @param timeZone the time zone to use, may be `null`
     * @param locale the locale to use, may be `null`
     * @return the formatted date
     */
    fun format(
        millis: Long,
        pattern: String,
        timeZone: TimeZone?,
        locale: Locale?
    ): String {
        return format(Date(millis), pattern, timeZone, locale)
    }

    /**
     * Formats a date/time into a specific pattern in a time zone and locale.
     * 
     * @param date the date to format, not null
     * @param pattern the pattern to use to format the date, not null, not null
     * @param timeZone the time zone to use, may be `null`
     * @param locale the locale to use, may be `null`
     * @return the formatted date
     */
    /**
     * Formats a date/time into a specific pattern.
     * 
     * @param date the date to format, not null
     * @param pattern the pattern to use to format the date, not null
     * @return the formatted date
     */
    /**
     * Formats a date/time into a specific pattern in a time zone.
     * 
     * @param date the date to format, not null
     * @param pattern the pattern to use to format the date, not null
     * @param timeZone the time zone to use, may be `null`
     * @return the formatted date
     */
    @JvmOverloads
    fun format(
        date: Date,
        pattern: String,
        timeZone: TimeZone? = null,
        locale: Locale? = null
    ): String {
        val df: FastDateFormat = FastDateFormat.Companion.getInstance(pattern, timeZone, locale)
        return df.format(date)
    }

    /**
     * Formats a calendar into a specific pattern in a time zone and locale.
     * 
     * @param calendar the calendar to format, not null
     * @param pattern the pattern to use to format the calendar, not null
     * @param timeZone the time zone to use, may be `null`
     * @param locale the locale to use, may be `null`
     * @return the formatted calendar
     * @see FastDateFormat.format
     * @since 2.4
     */
    /**
     * Formats a calendar into a specific pattern.
     * 
     * @param calendar the calendar to format, not null
     * @param pattern the pattern to use to format the calendar, not null
     * @return the formatted calendar
     * @see FastDateFormat.format
     * @since 2.4
     */
    /**
     * Formats a calendar into a specific pattern in a time zone.
     * 
     * @param calendar the calendar to format, not null
     * @param pattern the pattern to use to format the calendar, not null
     * @param timeZone the time zone to use, may be `null`
     * @return the formatted calendar
     * @see FastDateFormat.format
     * @since 2.4
     */
    @JvmOverloads
    fun format(
        calendar: Calendar,
        pattern: String,
        timeZone: TimeZone? = null,
        locale: Locale? = null
    ): String {
        val df: FastDateFormat = FastDateFormat.Companion.getInstance(pattern, timeZone, locale)
        return df.format(calendar)
    }
}
