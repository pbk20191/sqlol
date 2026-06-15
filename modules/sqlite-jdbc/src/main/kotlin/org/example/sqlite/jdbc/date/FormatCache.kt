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
package org.example.sqlite.jdbc.date

import java.text.DateFormat
import java.text.Format
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/**
 * FormatCache is a cache and factory for [Format]s.
 * 
 * @since 3.0
 * @version $Id: FormatCache 892161 2009-12-18 07:21:10Z $
 */
// TODO: Before making public move from getDateTimeInstance(Integer,...) to int; or some other
// approach.
internal abstract class FormatCache<F : Format?> {
    private val cInstanceCache: ConcurrentMap<MultipartKey, F?> = ConcurrentHashMap<MultipartKey, F?>(7)

    val instance: F
        /**
         * Gets a formatter instance using the default pattern in the default timezone and locale.
         *
         * @return a date/time formatter
         */
        get() = getDateTimeInstance(
            DateFormat.SHORT,
            DateFormat.SHORT,
            TimeZone.getDefault(),
            Locale.getDefault()
        )

    /**
     * Gets a formatter instance using the specified pattern, time zone and locale.
     * 
     * @param pattern [SimpleDateFormat] compatible pattern, non-null
     * @param timeZone the time zone, null means use the default TimeZone
     * @param locale the locale, null means use the default Locale
     * @return a pattern based date/time formatter
     * @throws IllegalArgumentException if pattern is invalid or `null`
     */
    fun getInstance(
        pattern: String, timeZone: TimeZone?, locale: Locale?
    ): F {
        var timeZone = timeZone
        var locale = locale
        if (pattern == null) {
            throw NullPointerException("pattern must not be null")
        }
        if (timeZone == null) {
            timeZone = TimeZone.getDefault()
        }
        if (locale == null) {
            locale = Locale.getDefault()
        }
        val key = MultipartKey(pattern, timeZone, locale)
        var format = cInstanceCache.get(key)
        if (format == null) {
            format = createInstance(pattern, timeZone, locale)
            val previousValue = cInstanceCache.putIfAbsent(key, format)
            if (previousValue != null) {
                // another thread snuck in and did the same work
                // we should return the instance that is in ConcurrentMap
                format = previousValue
            }
        }
        return format!!
    }

    /**
     * Create a format instance using the specified pattern, time zone and locale.
     * 
     * @param pattern [SimpleDateFormat] compatible pattern, this will not be null.
     * @param timeZone time zone, this will not be null.
     * @param locale locale, this will not be null.
     * @return a pattern based date/time formatter
     * @throws IllegalArgumentException if pattern is invalid or `null`
     */
    protected abstract fun createInstance(
        pattern: String, timeZone: TimeZone, locale: Locale
    ): F

    /**
     * Gets a date/time formatter instance using the specified style, time zone and locale.
     * 
     * @param dateStyle date style: FULL, LONG, MEDIUM, or SHORT, null indicates no date in format
     * @param timeStyle time style: FULL, LONG, MEDIUM, or SHORT, null indicates no time in format
     * @param timeZone optional time zone, overrides time zone of formatted date, null means use
     * default Locale
     * @param locale optional locale, overrides system locale
     * @return a localized standard date/time formatter
     * @throws IllegalArgumentException if the Locale has no date/time pattern defined
     */
    // This must remain private, see LANG-884
    private fun getDateTimeInstance(
        dateStyle: Int?,
        timeStyle: Int?,
        timeZone: TimeZone?,
        locale: Locale?
    ): F {
        var locale = locale
        if (locale == null) {
            locale = Locale.getDefault()
        }
        val pattern: String = getPatternForStyle(dateStyle, timeStyle, locale)
        return getInstance(pattern, timeZone, locale)
    }

    /**
     * Gets a date/time formatter instance using the specified style, time zone and locale.
     * 
     * @param dateStyle date style: FULL, LONG, MEDIUM, or SHORT
     * @param timeStyle time style: FULL, LONG, MEDIUM, or SHORT
     * @param timeZone optional time zone, overrides time zone of formatted date, null means use
     * default Locale
     * @param locale optional locale, overrides system locale
     * @return a localized standard date/time formatter
     * @throws IllegalArgumentException if the Locale has no date/time pattern defined
     */
    // package protected, for access from FastDateFormat; do not make public or protected
    fun getDateTimeInstance(
        dateStyle: Int,
        timeStyle: Int,
        timeZone: TimeZone?,
        locale: Locale?
    ): F {
        return getDateTimeInstance(
            dateStyle as Int?, timeStyle as Int?, timeZone, locale
        )
    }

    /**
     * Gets a date formatter instance using the specified style, time zone and locale.
     * 
     * @param dateStyle date style: FULL, LONG, MEDIUM, or SHORT
     * @param timeZone optional time zone, overrides time zone of formatted date, null means use
     * default Locale
     * @param locale optional locale, overrides system locale
     * @return a localized standard date/time formatter
     * @throws IllegalArgumentException if the Locale has no date/time pattern defined
     */
    // package protected, for access from FastDateFormat; do not make public or protected
    fun getDateInstance(dateStyle: Int, timeZone: TimeZone?, locale: Locale?): F {
        return getDateTimeInstance(dateStyle, null, timeZone, locale)
    }

    /**
     * Gets a time formatter instance using the specified style, time zone and locale.
     * 
     * @param timeStyle time style: FULL, LONG, MEDIUM, or SHORT
     * @param timeZone optional time zone, overrides time zone of formatted date, null means use
     * default Locale
     * @param locale optional locale, overrides system locale
     * @return a localized standard date/time formatter
     * @throws IllegalArgumentException if the Locale has no date/time pattern defined
     */
    // package protected, for access from FastDateFormat; do not make public or protected
    fun getTimeInstance(timeStyle: Int, timeZone: TimeZone?, locale: Locale?): F {
        return getDateTimeInstance(null, timeStyle, timeZone, locale)
    }

    // ----------------------------------------------------------------------
    /** Helper class to hold multi-part Map keys  */
    private class MultipartKey(vararg keys: Any?) {
        private val keys: Array<out Any?>
        private var hashCode = 0

        /**
         * Constructs an instance of `MultipartKey` to hold the specified objects.
         * 
         * @param keys the set of objects that make up the key. Each key may be null.
         */
        init {
            this.keys = keys
        }

        /** {@inheritDoc}  */
        override fun equals(obj: Any?): Boolean {
            // Eliminate the usual boilerplate because
            // this inner static class is only used in a generic ConcurrentHashMap
            // which will not compare against other Object types
            return keys.contentEquals((obj as MultipartKey).keys)
        }

        /** {@inheritDoc}  */
        override fun hashCode(): Int {
            if (hashCode == 0) {
                var rc = 0
                for (key in keys) {
                    if (key != null) {
                        rc = rc * 7 + key.hashCode()
                    }
                }
                hashCode = rc
            }
            return hashCode
        }
    }

    companion object {
        /** No date or no time. Used in same parameters as DateFormat.SHORT or DateFormat.LONG  */
        val NONE: Int = -1

        private val cDateTimeInstanceCache: ConcurrentMap<MultipartKey, String> =
            ConcurrentHashMap<MultipartKey, String>(7)

        /**
         * Gets a date/time format for the specified styles and locale.
         * 
         * @param dateStyle date style: FULL, LONG, MEDIUM, or SHORT, null indicates no date in format
         * @param timeStyle time style: FULL, LONG, MEDIUM, or SHORT, null indicates no time in format
         * @param locale The non-null locale of the desired format
         * @return a localized standard date/time format
         * @throws IllegalArgumentException if the Locale has no date/time pattern defined
         */
        // package protected, for access from test code; do not make public or protected
        fun getPatternForStyle(
            dateStyle: Int?,
            timeStyle: Int?,
            locale: Locale
        ): String {
            val key = MultipartKey(dateStyle, timeStyle, locale)

            var pattern: String? = cDateTimeInstanceCache.get(key)
            if (pattern == null) {
                try {
                    val formatter: DateFormat
                    if (dateStyle == null) {
                        formatter = DateFormat.getTimeInstance(timeStyle!!, locale)
                    } else if (timeStyle == null) {
                        formatter = DateFormat.getDateInstance(dateStyle, locale)
                    } else {
                        formatter =
                            DateFormat.getDateTimeInstance(
                                dateStyle, timeStyle, locale
                            )
                    }
                    pattern = (formatter as SimpleDateFormat).toPattern()
                    val previous: String? = cDateTimeInstanceCache.putIfAbsent(key, pattern)
                    if (previous != null) {
                        // even though it doesn't matter if another thread put the pattern
                        // it's still good practice to return the String instance that is
                        // actually in the ConcurrentMap
                        pattern = previous
                    }
                } catch (ex: ClassCastException) {
                    throw IllegalArgumentException("No date time pattern for locale: " + locale)
                }
            }
            return pattern
        }
    }
}
