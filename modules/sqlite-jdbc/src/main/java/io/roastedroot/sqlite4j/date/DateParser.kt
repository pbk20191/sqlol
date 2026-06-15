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

import java.text.ParseException
import java.text.ParsePosition
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * DateParser is the "missing" interface for the parsing methods of [java.text.DateFormat].
 * 
 * @since 3.2
 */
interface DateParser {
    /**
     * Equivalent to DateFormat.parse(String).
     * 
     * 
     * See [java.text.DateFormat.parse] for more information.
     * 
     * @param source A `String` whose beginning should be parsed.
     * @return A `Date` parsed from the string
     * @throws ParseException if the beginning of the specified string cannot be parsed.
     */
    @Throws(ParseException::class)
    fun parse(source: String): Date?

    /**
     * Equivalent to DateFormat.parse(String, ParsePosition).
     * 
     * 
     * See [java.text.DateFormat.parse] for more information.
     * 
     * @param source A `String`, part of which should be parsed.
     * @param pos A `ParsePosition` object with index and error index information as
     * described above.
     * @return A `Date` parsed from the string. In case of error, returns null.
     * @throws NullPointerException if text or pos is null.
     */
    fun parse(source: String, pos: ParsePosition): Date?

    // Accessors
    // -----------------------------------------------------------------------
    /**
     * Get the pattern used by this parser.
     * 
     * @return the pattern, [java.text.SimpleDateFormat] compatible
     */
    val pattern: String

    /**
     * Get the time zone used by this parser.
     * 
     * 
     * The default [TimeZone] used to create a [Date] when the [TimeZone] is
     * not specified by the format pattern.
     * 
     * @return the time zone
     */
    val timeZone: TimeZone

    /**
     * Get the locale used by this parser.
     * 
     * @return the locale
     */
    val locale: Locale

    /**
     * Parses text from a string to produce a Date.
     * 
     * @param source A `String` whose beginning should be parsed.
     * @return a `java.util.Date` object
     * @throws ParseException if the beginning of the specified string cannot be parsed.
     * @see java.text.DateFormat.parseObject
     */
    @Throws(ParseException::class)
    fun parseObject(source: String): Any?

    /**
     * Parse a date/time string according to the given parse position.
     * 
     * @param source A `String` whose beginning should be parsed.
     * @param pos the parse position
     * @return a `java.util.Date` object
     * @see java.text.DateFormat.parseObject
     */
    fun parseObject(source: String, pos: ParsePosition): Any?
}
