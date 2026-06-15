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

import java.io.IOException
import java.io.ObjectInputStream
import java.io.Serializable
import java.text.DateFormatSymbols
import java.text.ParseException
import java.text.ParsePosition
import java.util.*
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.Any
import kotlin.Array
import kotlin.Boolean
import kotlin.IllegalArgumentException
import kotlin.Int
import kotlin.Throws
import kotlin.arrayOfNulls
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.containsKey
import kotlin.collections.get
import kotlin.collections.toTypedArray
import kotlin.require
import kotlin.requireNotNull
import kotlin.synchronized
import kotlin.text.StringBuilder
import kotlin.text.startsWith
import kotlin.text.toInt

/**
 * FastDateParser is a fast and thread-safe version of [java.text.SimpleDateFormat].
 * 
 * 
 * To obtain a proxy to a FastDateParser, use [FastDateFormat.getInstance] or another variation of the factory methods of [FastDateFormat].
 * 
 * 
 * Since FastDateParser is thread safe, you can use a static member instance: `
 * private static final DateParser DATE_PARSER = FastDateFormat.getInstance("yyyy-MM-dd");
` * 
 * 
 * 
 * This class can be used as a direct replacement for `SimpleDateFormat` in most
 * parsing situations. This class is especially useful in multi-threaded server environments. `
 * SimpleDateFormat` is not thread-safe in any JDK version, nor will it be as Sun has closed
 * the [bug](http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=4228335)/RFE.
 * 
 * 
 * Only parsing is supported by this class, but all patterns are compatible with
 * SimpleDateFormat.
 * 
 * 
 * The class operates in lenient mode, so for example a time of 90 minutes is treated as 1 hour
 * 30 minutes.
 * 
 * 
 * Timing tests indicate this class is as about as fast as SimpleDateFormat in single thread
 * applications and about 25% faster in multi-thread applications.
 * 
 * @version $Id$
 * @since 3.2
 * @see FastDatePrinter
 */
class FastDateParser(// defining fields
    override val pattern: String,
    override val timeZone: TimeZone,
    override val locale: Locale,
    centuryStart: Date?
) : DateParser, Serializable {
    private val century: Int
    private val startYear: Int

    // derived fields
    @Transient
    private var parsePattern: Pattern? = null

    @Transient
    private lateinit var strategies: Array<Strategy>

    // dynamic fields to communicate with Strategy
    @Transient
    private var currentFormatField: String? = null

    @Transient
    private var nextStrategy: Strategy? = null

    /**
     * Constructs a new FastDateParser. Use [FastDateFormat.getInstance] or another variation of the factory methods of [FastDateFormat] to get a
     * cached FastDateParser instance.
     * 
     * @param pattern non-null [java.text.SimpleDateFormat] compatible pattern
     * @param timeZone non-null time zone to use
     * @param locale non-null locale
     */
    protected constructor(
        pattern: String,
        timeZone: TimeZone,
        locale: Locale
    ) : this(pattern, timeZone, locale, null)

    /**
     * Initialize derived fields from defining fields. This is called from constructor and from
     * readObject (de-serialization)
     * 
     * @param definingCalendar the [Calendar] instance used to initialize this
     * FastDateParser
     */
    private fun init(definingCalendar: Calendar) {
        val regex = StringBuilder()
        val collector: MutableList<Strategy> = ArrayList<Strategy>()

        val patternMatcher: Matcher = formatPattern.matcher(pattern)
        require(patternMatcher.lookingAt()) {
            ("Illegal pattern character '"
                    + pattern.get(patternMatcher.regionStart())
                    + "'")
        }

        currentFormatField = patternMatcher.group()
        var currentStrategy: Strategy? = getStrategy(currentFormatField!!, definingCalendar)
        while (true) {
            patternMatcher.region(patternMatcher.end(), patternMatcher.regionEnd())
            if (!patternMatcher.lookingAt()) {
                nextStrategy = null
                break
            }
            val nextFormatField = patternMatcher.group()
            nextStrategy = getStrategy(nextFormatField, definingCalendar)
            if (currentStrategy!!.addRegex(this, regex)) {
                collector.add(currentStrategy)
            }
            currentFormatField = nextFormatField
            currentStrategy = nextStrategy
        }
        require(patternMatcher.regionStart() == patternMatcher.regionEnd()) {
            ("Failed to parse \""
                    + pattern
                    + "\" ; gave up at index "
                    + patternMatcher.regionStart())
        }
        if (currentStrategy!!.addRegex(this, regex)) {
            collector.add(currentStrategy)
        }
        currentFormatField = null
        strategies = collector.toTypedArray<Strategy>()
        parsePattern = Pattern.compile(regex.toString())
    }

    // Accessors
    // -----------------------------------------------------------------------
    /* (non-Javadoc)
     * @see org.apache.commons.lang3.time.DateParser#getPattern()
     */
    /**
     * Returns the generated pattern (for testing purposes).
     * 
     * @return the generated pattern
     */
    fun getParsePattern(): Pattern {
        return parsePattern!!
    }

    // Basics
    // -----------------------------------------------------------------------
    /**
     * Compare another object for equality with this object.
     * 
     * @param obj the object to compare to
     * @return `true`if equal to this instance
     */
    override fun equals(obj: Any?): Boolean {
        if (obj !is FastDateParser) {
            return false
        }
        val other = obj
        return pattern == other.pattern
                && timeZone == other.timeZone
                && locale == other.locale
    }

    /**
     * Return a hashcode compatible with equals.
     * 
     * @return a hashcode compatible with equals
     */
    override fun hashCode(): Int {
        return pattern.hashCode() + 13 * (timeZone.hashCode() + 13 * locale.hashCode())
    }

    /**
     * Get a string version of this formatter.
     * 
     * @return a debugging string
     */
    override fun toString(): String {
        return "FastDateParser[" + pattern + "," + locale + "," + timeZone.getID() + "]"
    }

    // Serializing
    // -----------------------------------------------------------------------
    /**
     * Create the object after serialization. This implementation reinitializes the transient
     * properties.
     * 
     * @param in ObjectInputStream from which the object is being deserialized.
     * @throws IOException if there is an IO issue.
     * @throws ClassNotFoundException if a class cannot be found.
     */
    @Throws(IOException::class, ClassNotFoundException::class)
    private fun readObject(`in`: ObjectInputStream) {
        `in`.defaultReadObject()

        val definingCalendar = Calendar.getInstance(timeZone, locale)
        init(definingCalendar)
    }

    /* (non-Javadoc)
     * @see org.apache.commons.lang3.time.DateParser#parseObject(java.lang.String)
     */
    @Throws(ParseException::class)
    override fun parseObject(source: String): Any {
        return parse(source)
    }

    /* (non-Javadoc)
     * @see org.apache.commons.lang3.time.DateParser#parse(java.lang.String)
     */
    @Throws(ParseException::class)
    override fun parse(source: String): Date {
        val normalizedSource = if (source.length == 19) (source + ".000") else source
        val date = parse(normalizedSource, ParsePosition(0))
        if (date == null) {
            // Add a note re supported date range
            if (locale == JAPANESE_IMPERIAL) {
                throw ParseException(
                    ("(The "
                            + locale
                            + " locale does not support dates before 1868 AD)\n"
                            + "Unparseable date: \""
                            + normalizedSource
                            + "\" does not match "
                            + parsePattern!!.pattern()),
                    0
                )
            }
            throw ParseException(
                ("Unparseable date: \""
                        + normalizedSource
                        + "\" does not match "
                        + parsePattern!!.pattern()),
                0
            )
        }
        return date
    }

    /* (non-Javadoc)
     * @see org.apache.commons.lang3.time.DateParser#parseObject(java.lang.String, java.text.ParsePosition)
     */
    override fun parseObject(
        source: String, pos: ParsePosition
    ): Any? {
        return parse(source, pos)
    }

    /**
     * This implementation updates the ParsePosition if the parse succeeds. However, unlike the
     * method [java.text.SimpleDateFormat.parse] it is not able to set
     * the error Index - i.e. [ParsePosition.getErrorIndex] - if the parse fails.
     * 
     * 
     * To determine if the parse has succeeded, the caller must check if the current parse
     * position given by [ParsePosition.getIndex] has been updated. If the input buffer has
     * been fully parsed, then the index will point to just after the end of the input buffer.
     * 
     * 
     * See org.apache.commons.lang3.time.DateParser#parse(java.lang.String,
     * java.text.ParsePosition) {@inheritDoc}
     */
    override fun parse(source: String, pos: ParsePosition): Date? {
        val offset = pos.getIndex()
        val matcher = parsePattern!!.matcher(source.substring(offset))
        if (!matcher.lookingAt()) {
            return null
        }
        // timing tests indicate getting new instance is 19% faster than cloning
        val cal = Calendar.getInstance(timeZone, locale)
        cal.clear()

        var i = 0
        while (i < strategies.size) {
            val strategy = strategies[i++]
            strategy.setCalendar(this, cal, matcher.group(i))
        }
        pos.setIndex(offset + matcher.end())
        return cal.getTime()
    }

    /**
     * Adjust dates to be within appropriate century
     * 
     * @param twoDigitYear The year to adjust
     * @return A value between centuryStart(inclusive) to centuryStart+100(exclusive)
     */
    private fun adjustYear(twoDigitYear: Int): Int {
        val trial = century + twoDigitYear
        return if (twoDigitYear >= startYear) trial else trial + 100
    }

    val isNextNumber: Boolean
        /**
         * Is the next field a number?
         * 
         * @return true, if next field will be a number
         */
        get() = nextStrategy != null && nextStrategy!!.isNumber

    val fieldWidth: Int
        /**
         * What is the width of the current field?
         * 
         * @return The number of characters in the current format field
         */
        get() = currentFormatField!!.length

    /** A strategy to parse a single field from the parsing pattern  */
    private abstract class Strategy {
        open val isNumber: Boolean
            /**
             * Is this field a number? The default implementation returns false.
             * 
             * @return true, if field is a number
             */
            get() = false

        /**
         * Set the Calendar with the parsed field.
         * 
         * 
         * The default implementation does nothing.
         * 
         * @param parser The parser calling this strategy
         * @param cal The `Calendar` to set
         * @param value The parsed field to translate and set in cal
         */
        open fun setCalendar(
            parser: FastDateParser,
            cal: Calendar,
            value: String
        ) {
        }

        /**
         * Generate a `Pattern` regular expression to the `StringBuilder`
         * which will accept this field
         * 
         * @param parser The parser calling this strategy
         * @param regex The `StringBuilder` to append to
         * @return true, if this field will set the calendar; false, if this field is a constant
         * value
         */
        abstract fun addRegex(parser: FastDateParser, regex: StringBuilder): Boolean
    }

    /**
     * Obtain a Strategy given a field from a SimpleDateFormat pattern
     * 
     * @param formatField A sub-sequence of the SimpleDateFormat pattern
     * @param definingCalendar The calendar to obtain the short and long values
     * @return The Strategy that will handle parsing for the field
     */
    private fun getStrategy(
        formatField: String, definingCalendar: Calendar
    ): Strategy {
        when (formatField.get(0)) {
            '\'' -> {
                if (formatField.length > 2) {
                    return CopyQuotedStrategy(
                        formatField.substring(1, formatField.length - 1)
                    )
                }
                return CopyQuotedStrategy(formatField)
            }

            'D' -> return DAY_OF_YEAR_STRATEGY
            'E' -> return getLocaleSpecificStrategy(Calendar.DAY_OF_WEEK, definingCalendar)
            'F' -> return DAY_OF_WEEK_IN_MONTH_STRATEGY
            'G' -> return getLocaleSpecificStrategy(Calendar.ERA, definingCalendar)
            'H' -> return HOUR_OF_DAY_STRATEGY
            'K' -> return HOUR_STRATEGY
            'M' -> return if (formatField.length >= 3)
                getLocaleSpecificStrategy(Calendar.MONTH, definingCalendar)
            else
                NUMBER_MONTH_STRATEGY

            'S' -> return MILLISECOND_STRATEGY
            'W' -> return WEEK_OF_MONTH_STRATEGY
            'a' -> return getLocaleSpecificStrategy(Calendar.AM_PM, definingCalendar)
            'd' -> return DAY_OF_MONTH_STRATEGY
            'h' -> return HOUR12_STRATEGY
            'k' -> return HOUR24_OF_DAY_STRATEGY
            'm' -> return MINUTE_STRATEGY
            's' -> return SECOND_STRATEGY
            'w' -> return WEEK_OF_YEAR_STRATEGY
            'y' -> return if (formatField.length > 2) LITERAL_YEAR_STRATEGY else ABBREVIATED_YEAR_STRATEGY
            'X' -> return ISO8601TimeZoneStrategy.Companion.getStrategy(formatField.length)
            'Z' -> {
                if (formatField == "ZZ") {
                    return ISO_8601_STRATEGY
                }
                return getLocaleSpecificStrategy(Calendar.ZONE_OFFSET, definingCalendar)
            }

            'z' -> return getLocaleSpecificStrategy(Calendar.ZONE_OFFSET, definingCalendar)
            else -> return CopyQuotedStrategy(formatField)
        }
    }

    /**
     * Construct a Strategy that parses a Text field
     * 
     * @param field The Calendar field
     * @param definingCalendar The calendar to obtain the short and long values
     * @return a TextStrategy for the field and Locale
     */
    private fun getLocaleSpecificStrategy(
        field: Int, definingCalendar: Calendar
    ): Strategy {
        val cache: ConcurrentMap<Locale, Strategy> = getCache(field)
        var strategy = cache.get(locale)
        if (strategy == null) {
            strategy =
                if (field == Calendar.ZONE_OFFSET)
                    TimeZoneStrategy(locale)
                else
                    CaseInsensitiveTextStrategy(field, definingCalendar, locale)
            val inCache = cache.putIfAbsent(locale, strategy)
            if (inCache != null) {
                return inCache
            }
        }
        return strategy
    }

    /** A strategy that copies the static or quoted field in the parsing pattern  */
    private class CopyQuotedStrategy
    /**
     * Construct a Strategy that ensures the formatField has literal text
     * 
     * @param formatField The literal text to match
     */(private val formatField: String) : Strategy() {
        /** {@inheritDoc}  */
        override val isNumber: Boolean
            get() {
                var c = formatField.get(0)
                if (c == '\'') {
                    c = formatField.get(1)
                }
                return Character.isDigit(c)
            }

        /** {@inheritDoc}  */
        override fun addRegex(parser: FastDateParser, regex: StringBuilder): Boolean {
            escapeRegex(regex, formatField, true)
            return false
        }
    }

    /** A strategy that handles a text field in the parsing pattern  */
    private class CaseInsensitiveTextStrategy(
        private val field: Int,
        definingCalendar: Calendar,
        private val locale: Locale
    ) : Strategy() {
        private val lKeyValues: MutableMap<String, Int>

        /**
         * Construct a Strategy that parses a Text field
         * 
         * @param field The Calendar field
         * @param definingCalendar The Calendar to use
         * @param locale The Locale to use
         */
        init {
            val keyValues: MutableMap<String, Int> = getDisplayNames(
                field, definingCalendar,
                locale
            )
            this.lKeyValues = HashMap<String, Int>()

            for (entry in keyValues.entries) {
                lKeyValues.put(entry.key.lowercase(locale), entry.value)
            }
        }

        /** {@inheritDoc}  */
        override fun addRegex(parser: FastDateParser, regex: StringBuilder): Boolean {
            regex.append("((?iu)")
            for (textKeyValue in lKeyValues.keys) {
                escapeRegex(regex, textKeyValue, false).append('|')
            }
            regex.setCharAt(regex.length - 1, ')')
            return true
        }

        /** {@inheritDoc}  */
        override fun setCalendar(
            parser: FastDateParser,
            cal: Calendar,
            value: String
        ) {
            val iVal = lKeyValues.get(value.lowercase(locale))
            if (iVal == null) {
                val sb = StringBuilder(value)
                sb.append(" not in (")
                for (textKeyValue in lKeyValues.keys) {
                    sb.append(textKeyValue).append(' ')
                }
                sb.setCharAt(sb.length - 1, ')')
                throw IllegalArgumentException(sb.toString())
            }
            cal.set(field, iVal)
        }
    }

    /** A strategy that handles a number field in the parsing pattern  */
    private open class NumberStrategy
    /**
     * Construct a Strategy that parses a Number field
     * 
     * @param field The Calendar field
     */(private val field: Int) : Strategy() {
        /** {@inheritDoc}  */
        override val isNumber: Boolean
            get() = true

        /** {@inheritDoc}  */
        override fun addRegex(parser: FastDateParser, regex: StringBuilder): Boolean {
            // See LANG-954: We use {Nd} rather than {IsNd} because Android does not support the Is
            // prefix
            if (parser.isNextNumber) {
                regex.append("(\\p{Nd}{").append(parser.fieldWidth).append("}+)")
            } else {
                regex.append("(\\p{Nd}++)")
            }
            return true
        }

        /** {@inheritDoc}  */
        override fun setCalendar(
            parser: FastDateParser,
            cal: Calendar,
            value: String
        ) {
            cal.set(field, modify(value.toInt()))
        }

        /**
         * Make any modifications to parsed integer
         * 
         * @param iValue The parsed integer
         * @return The modified value
         */
        open fun modify(iValue: Int): Int {
            return iValue
        }
    }

    /** A strategy that handles a timezone field in the parsing pattern  */
    private class TimeZoneStrategy(locale: Locale) : Strategy() {
        private val validTimeZoneChars: String
        private val tzNames: SortedMap<String, TimeZone> = TreeMap<String, TimeZone>(java.lang.String.CASE_INSENSITIVE_ORDER)

        /**
         * Construct a Strategy that parses a TimeZone
         * 
         * @param locale The Locale
         */
        init {
            val zones = DateFormatSymbols.getInstance(locale).getZoneStrings()
            for (zone in zones) {
                if (zone!![ID].startsWith("GMT")) {
                    continue
                }
                val tz = TimeZone.getTimeZone(zone[ID])
                if (!tzNames.containsKey(zone[LONG_STD])) {
                    tzNames.put(zone[LONG_STD], tz)
                }
                if (!tzNames.containsKey(zone[SHORT_STD])) {
                    tzNames.put(zone[SHORT_STD], tz)
                }
                if (tz.useDaylightTime()) {
                    if (!tzNames.containsKey(zone[LONG_DST])) {
                        tzNames.put(zone[LONG_DST], tz)
                    }
                    if (!tzNames.containsKey(zone[SHORT_DST])) {
                        tzNames.put(zone[SHORT_DST], tz)
                    }
                }
            }

            val sb = StringBuilder()
            sb.append("(GMT[+-]\\d{1,2}:\\d{2}").append('|')
            sb.append("[+-]\\d{4}").append('|')
            for (id in tzNames.keys) {
                escapeRegex(sb, id, false).append('|')
            }
            sb.setCharAt(sb.length - 1, ')')
            validTimeZoneChars = sb.toString()
        }

        /** {@inheritDoc}  */
        override fun addRegex(parser: FastDateParser, regex: StringBuilder): Boolean {
            regex.append(validTimeZoneChars)
            return true
        }

        /** {@inheritDoc}  */
        override fun setCalendar(
            parser: FastDateParser,
            cal: Calendar,
            value: kotlin.String
        ) {
            val tz: TimeZone
            if (value.get(0) == '+' || value.get(0) == '-') {
                tz = TimeZone.getTimeZone("GMT" + value)
            } else if (value.startsWith("GMT")) {
                tz = TimeZone.getTimeZone(value)
            } else {
                tz = tzNames.get(value)!!
                requireNotNull(tz) { value + " is not a supported timezone name" }
            }
            cal.setTimeZone(tz)
        }

        companion object {
            /** Index of zone id  */
            private const val ID = 0

            /** Index of the long name of zone in standard time  */
            private const val LONG_STD = 1

            /** Index of the short name of zone in standard time  */
            private const val SHORT_STD = 2

            /** Index of the long name of zone in daylight saving time  */
            private const val LONG_DST = 3

            /** Index of the short name of zone in daylight saving time  */
            private const val SHORT_DST = 4
        }
    }

    private class ISO8601TimeZoneStrategy
    /**
     * Construct a Strategy that parses a TimeZone
     * 
     * @param pattern The Pattern
     */(// Z, +hh, -hh, +hhmm, -hhmm, +hh:mm or -hh:mm
        private val pattern: kotlin.String
    ) : Strategy() {
        /** {@inheritDoc}  */
        override fun addRegex(parser: FastDateParser, regex: StringBuilder): Boolean {
            regex.append(pattern)
            return true
        }

        /** {@inheritDoc}  */
        override fun setCalendar(
            parser: FastDateParser, cal: Calendar, value: kotlin.String
        ) {
            if (value == "Z") {
                cal.setTimeZone(TimeZone.getTimeZone("UTC"))
            } else {
                cal.setTimeZone(TimeZone.getTimeZone("GMT" + value))
            }
        }

        companion object {
            private val ISO_8601_1_STRATEGY: Strategy = ISO8601TimeZoneStrategy("(Z|(?:[+-]\\d{2}))")
            private val ISO_8601_2_STRATEGY: Strategy = ISO8601TimeZoneStrategy("(Z|(?:[+-]\\d{2}\\d{2}))")
            private val ISO_8601_3_STRATEGY: Strategy = ISO8601TimeZoneStrategy("(Z|(?:[+-]\\d{2}(?::)\\d{2}))")

            /**
             * Factory method for ISO8601TimeZoneStrategies.
             * 
             * @param tokenLen a token indicating the length of the TimeZone String to be formatted.
             * @return a ISO8601TimeZoneStrategy that can format TimeZone String of length `tokenLen`. If no such strategy exists, an IllegalArgumentException will be thrown.
             */
            fun getStrategy(tokenLen: Int): Strategy {
                when (tokenLen) {
                    1 -> return ISO_8601_1_STRATEGY
                    2 -> return ISO_8601_2_STRATEGY
                    3 -> return ISO_8601_3_STRATEGY
                    else -> throw IllegalArgumentException("invalid number of X")
                }
            }
        }
    }

    /**
     * Constructs a new FastDateParser.
     * 
     * @param pattern non-null [java.text.SimpleDateFormat] compatible pattern
     * @param timeZone non-null time zone to use
     * @param locale non-null locale
     * @param centuryStart The start of the century for 2 digit year parsing
     * @since 3.3
     */
    init {
        val definingCalendar = Calendar.getInstance(timeZone, locale)
        val centuryStartYear: Int
        if (centuryStart != null) {
            definingCalendar.setTime(centuryStart)
            centuryStartYear = definingCalendar.get(Calendar.YEAR)
        } else if (locale == JAPANESE_IMPERIAL) {
            centuryStartYear = 0
        } else {
            // from 80 years ago to 20 years from now
            definingCalendar.setTime(Date())
            centuryStartYear = definingCalendar.get(Calendar.YEAR) - 80
        }
        century = centuryStartYear / 100 * 100
        startYear = centuryStartYear - century

        init(definingCalendar)
    }

    companion object {
        /**
         * Required for serialization support.
         * 
         * @see Serializable
         */
        private const val serialVersionUID = 2L

        val JAPANESE_IMPERIAL: Locale = Locale("ja", "JP", "JP")

        // Support for strategies
        // -----------------------------------------------------------------------
        /**
         * Escape constant fields into regular expression
         * 
         * @param regex The destination regex
         * @param value The source field
         * @param unquote If true, replace two success quotes ('') with single quote (')
         * @return The `StringBuilder`
         */
        private fun escapeRegex(
            regex: StringBuilder,
            value: kotlin.String,
            unquote: Boolean
        ): StringBuilder {
            regex.append("\\Q")
            var i = 0
            while (i < value.length) {
                var c = value.get(i)
                when (c) {
                    '\'' -> if (unquote) {
                        if (++i == value.length) {
                            return regex
                        }
                        c = value.get(i)
                    }

                    '\\' -> {
                        if (++i == value.length) {
                            break
                        }
                        /*
                     * If we have found \E, we replace it with \E\\E\Q, i.e. we stop the quoting,
                     * quote the \ in \E, then restart the quoting.
                     *
                     * Otherwise we just output the two characters.
                     * In each case the initial \ needs to be output and the final char is done at the end
                     */
                        regex.append(c) // we always want the original \
                        c = value.get(i) // Is it followed by E ?
                        if (c == 'E') { // \E detected
                            regex.append("E\\\\E\\") // see comment above
                            c = 'Q' // appended below
                        }
                    }

                    else -> {}
                }
                regex.append(c)
                ++i
            }
            regex.append("\\E")
            return regex
        }

        /**
         * Get the short and long values displayed for a field
         * 
         * @param field The field of interest
         * @param definingCalendar The calendar to obtain the short and long values
         * @param locale The locale of display names
         * @return A Map of the field key / value pairs
         */
        private fun getDisplayNames(
            field: Int,
            definingCalendar: Calendar,
            locale: Locale
        ): MutableMap<kotlin.String, Int> {
            return definingCalendar.getDisplayNames(field, Calendar.ALL_STYLES, locale)
        }

        /** A `Pattern` to parse the user supplied SimpleDateFormat pattern  */
        private val formatPattern: Pattern = Pattern.compile(
            "D+|E+|F+|G+|H+|K+|M+|S+|W+|X+|Z+|a+|d+|h+|k+|m+|s+|w+|y+|z+|''|'[^']++(''[^']*+)*+'|[^'A-Za-z]++"
        )

        // OK because we are creating an array with no entries
        @Suppress("UNCHECKED_CAST")
        private val caches: Array<ConcurrentMap<Locale, Strategy>?> =
            arrayOfNulls<ConcurrentMap<Locale, Strategy>>(Calendar.FIELD_COUNT)

        /**
         * Get a cache of Strategies for a particular field
         * 
         * @param field The Calendar field
         * @return a cache of Locale to Strategy
         */
        private fun getCache(field: Int): ConcurrentMap<Locale, Strategy> {
            synchronized(caches) {
                if (caches[field] == null) {
                    caches[field] = ConcurrentHashMap<Locale, Strategy>(3)
                }
                return caches[field]!!
            }
        }

        private val ABBREVIATED_YEAR_STRATEGY: Strategy = object : NumberStrategy(Calendar.YEAR) {
            /** {@inheritDoc}  */
            override fun setCalendar(
                parser: FastDateParser,
                cal: Calendar,
                value: kotlin.String
            ) {
                var iValue = value.toInt()
                if (iValue < 100) {
                    iValue = parser.adjustYear(iValue)
                }
                cal.set(Calendar.YEAR, iValue)
            }
        }

        private val NUMBER_MONTH_STRATEGY: Strategy = object : NumberStrategy(Calendar.MONTH) {
            override fun modify(iValue: Int): Int {
                return iValue - 1
            }
        }
        private val LITERAL_YEAR_STRATEGY: Strategy = NumberStrategy(Calendar.YEAR)
        private val WEEK_OF_YEAR_STRATEGY: Strategy = NumberStrategy(Calendar.WEEK_OF_YEAR)
        private val WEEK_OF_MONTH_STRATEGY: Strategy = NumberStrategy(Calendar.WEEK_OF_MONTH)
        private val DAY_OF_YEAR_STRATEGY: Strategy = NumberStrategy(Calendar.DAY_OF_YEAR)
        private val DAY_OF_MONTH_STRATEGY: Strategy = NumberStrategy(Calendar.DAY_OF_MONTH)
        private val DAY_OF_WEEK_IN_MONTH_STRATEGY: Strategy = NumberStrategy(Calendar.DAY_OF_WEEK_IN_MONTH)
        private val HOUR_OF_DAY_STRATEGY: Strategy = NumberStrategy(Calendar.HOUR_OF_DAY)
        private val HOUR24_OF_DAY_STRATEGY: Strategy = object : NumberStrategy(Calendar.HOUR_OF_DAY) {
            override fun modify(iValue: Int): Int {
                return if (iValue == 24) 0 else iValue
            }
        }
        private val HOUR12_STRATEGY: Strategy = object : NumberStrategy(Calendar.HOUR) {
            override fun modify(iValue: Int): Int {
                return if (iValue == 12) 0 else iValue
            }
        }
        private val HOUR_STRATEGY: Strategy = NumberStrategy(Calendar.HOUR)
        private val MINUTE_STRATEGY: Strategy = NumberStrategy(Calendar.MINUTE)
        private val SECOND_STRATEGY: Strategy = NumberStrategy(Calendar.SECOND)
        private val MILLISECOND_STRATEGY: Strategy = NumberStrategy(Calendar.MILLISECOND)
        private val ISO_8601_STRATEGY: Strategy = ISO8601TimeZoneStrategy("(Z|(?:[+-]\\d{2}(?::?\\d{2})?))")
    }
}
