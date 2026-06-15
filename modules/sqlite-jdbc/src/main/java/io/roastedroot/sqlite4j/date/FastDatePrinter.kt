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
import java.text.DateFormat
import java.text.DateFormatSymbols
import java.text.FieldPosition
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import kotlin.math.max

/**
 * FastDatePrinter is a fast and thread-safe version of [java.text.SimpleDateFormat].
 * 
 * 
 * To obtain a FastDatePrinter, use [FastDateFormat.getInstance]
 * or another variation of the factory methods of [FastDateFormat].
 * 
 * 
 * Since FastDatePrinter is thread safe, you can use a static member instance: `
 * private static final DatePrinter DATE_PRINTER = FastDateFormat.getInstance("yyyy-MM-dd");
` * 
 * 
 * 
 * This class can be used as a direct replacement to `SimpleDateFormat` in most formatting
 * situations. This class is especially useful in multi-threaded server environments. `SimpleDateFormat` is not thread-safe in any JDK version, nor will it be as Sun have closed the
 * bug/RFE.
 * 
 * 
 * Only formatting is supported by this class, but all patterns are compatible with
 * SimpleDateFormat (except time zones and some year patterns - see below).
 * 
 * 
 * Java 1.4 introduced a new pattern letter, `'Z'`, to represent time zones in RFC822
 * format (eg. `+0800` or `-1100`). This pattern letter can be used here (on all JDK
 * versions).
 * 
 * 
 * In addition, the pattern `'ZZ'` has been made to represent ISO 8601 full format time
 * zones (eg. `+08:00` or `-11:00`). This introduces a minor incompatibility with Java
 * 1.4, but at a gain of useful functionality.
 * 
 * 
 * Starting with JDK7, ISO 8601 support was added using the pattern `'X'`. To maintain
 * compatibility, `'ZZ'` will continue to be supported, but using one of the `'X'`
 * formats is recommended.
 * 
 * 
 * Javadoc cites for the year pattern: *For formatting, if the number of pattern letters is 2,
 * the year is truncated to 2 digits; otherwise it is interpreted as a number.* Starting with
 * Java 1.7 a pattern of 'Y' or 'YYY' will be formatted as '2003', while it was '03' in former Java
 * versions. FastDatePrinter implements the behavior of Java 7.
 * 
 * @version $Id$
 * @since 3.2
 * @see FastDateParser
 */
class FastDatePrinter(
    /** The pattern.  */
    private val mPattern: String,
    /** The time zone.  */
    private val mTimeZone: TimeZone,
    /** The locale.  */
    private val mLocale: Locale
) : DatePrinter, Serializable {
    /** The parsed rules.  */
    @Transient
    private lateinit var mRules: Array<Rule>

    /**
     * Gets an estimate for the maximum string length that the formatter will produce.
     * 
     * 
     * The actual formatted length will almost always be less than or equal to this amount.
     * 
     * @return the maximum formatted length
     */
    /** The estimated maximum length.  */
    @Transient
    var maxLengthEstimate: Int = 0
        private set

    /** Initializes the instance for first use.  */
    private fun init() {
        val rulesList = parsePattern()
        mRules = rulesList.toTypedArray<Rule>()

        var len = 0
        var i = mRules.size
        while (--i >= 0) {
            len += mRules[i].estimateLength()
        }

        this.maxLengthEstimate = len
    }

    // Parse the pattern
    // -----------------------------------------------------------------------
    /**
     * Returns a list of Rules given a pattern.
     * 
     * @return a `List` of Rule objects
     * @throws IllegalArgumentException if pattern is invalid
     */
    internal fun parsePattern(): MutableList<Rule> {
        val symbols = DateFormatSymbols(mLocale)
        val rules: MutableList<Rule> = ArrayList<Rule>()

        val ERAs = symbols.getEras()
        val months = symbols.getMonths()
        val shortMonths = symbols.getShortMonths()
        val weekdays = symbols.getWeekdays()
        val shortWeekdays = symbols.getShortWeekdays()
        val AmPmStrings = symbols.getAmPmStrings()

        val length = mPattern.length
        val indexRef = IntArray(1)

        var i = 0
        while (i < length) {
            indexRef[0] = i
            val token = parseToken(mPattern, indexRef)
            i = indexRef[0]

            val tokenLen = token.length
            if (tokenLen == 0) {
                break
            }

            val rule: Rule?
            val c = token.get(0)

            when (c) {
                'G' -> rule = TextField(Calendar.ERA, ERAs)
                'y' -> if (tokenLen == 2) {
                    rule = TwoDigitYearField.Companion.INSTANCE
                } else {
                    rule = selectNumberRule(Calendar.YEAR, if (tokenLen < 4) 4 else tokenLen)
                }

                'M' -> if (tokenLen >= 4) {
                    rule = TextField(Calendar.MONTH, months)
                } else if (tokenLen == 3) {
                    rule = TextField(Calendar.MONTH, shortMonths)
                } else if (tokenLen == 2) {
                    rule = TwoDigitMonthField.Companion.INSTANCE
                } else {
                    rule = UnpaddedMonthField.Companion.INSTANCE
                }

                'd' -> rule = selectNumberRule(Calendar.DAY_OF_MONTH, tokenLen)
                'h' -> rule = TwelveHourField(selectNumberRule(Calendar.HOUR, tokenLen))
                'H' -> rule = selectNumberRule(Calendar.HOUR_OF_DAY, tokenLen)
                'm' -> rule = selectNumberRule(Calendar.MINUTE, tokenLen)
                's' -> rule = selectNumberRule(Calendar.SECOND, tokenLen)
                'S' -> rule = selectNumberRule(Calendar.MILLISECOND, tokenLen)
                'E' -> rule =
                    FastDatePrinter.TextField(
                        Calendar.DAY_OF_WEEK, (if (tokenLen < 4) shortWeekdays else weekdays)!!
                    )

                'D' -> rule = selectNumberRule(Calendar.DAY_OF_YEAR, tokenLen)
                'F' -> rule = selectNumberRule(Calendar.DAY_OF_WEEK_IN_MONTH, tokenLen)
                'w' -> rule = selectNumberRule(Calendar.WEEK_OF_YEAR, tokenLen)
                'W' -> rule = selectNumberRule(Calendar.WEEK_OF_MONTH, tokenLen)
                'a' -> rule = TextField(Calendar.AM_PM, AmPmStrings)
                'k' -> rule =
                    TwentyFourHourField(
                        selectNumberRule(Calendar.HOUR_OF_DAY, tokenLen)
                    )

                'K' -> rule = selectNumberRule(Calendar.HOUR, tokenLen)
                'X' -> rule = Iso8601_Rule.Companion.getRule(tokenLen)
                'z' -> if (tokenLen >= 4) {
                    rule = TimeZoneNameRule(mTimeZone, mLocale, TimeZone.LONG)
                } else {
                    rule = TimeZoneNameRule(mTimeZone, mLocale, TimeZone.SHORT)
                }

                'Z' -> if (tokenLen == 1) {
                    rule = TimeZoneNumberRule.Companion.INSTANCE_NO_COLON
                } else if (tokenLen == 2) {
                    rule = TimeZoneNumberRule.Companion.INSTANCE_ISO_8601
                } else {
                    rule = TimeZoneNumberRule.Companion.INSTANCE_COLON
                }

                '\'' -> {
                    val sub = token.substring(1)
                    if (sub.length == 1) {
                        rule = CharacterLiteral(sub.get(0))
                    } else {
                        rule = StringLiteral(sub)
                    }
                }

                else -> throw IllegalArgumentException("Illegal pattern component: " + token)
            }

            rules.add(rule)
            i++
        }

        return rules
    }

    /**
     * Performs the parsing of tokens.
     * 
     * @param pattern the pattern
     * @param indexRef index references
     * @return parsed token
     */
    protected fun parseToken(pattern: String, indexRef: IntArray): String {
        val buf = StringBuilder()

        var i = indexRef[0]
        val length = pattern.length

        var c = pattern.get(i)
        if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z') {
            // Scan a run of the same character, which indicates a time
            // pattern.
            buf.append(c)

            while (i + 1 < length) {
                val peek = pattern.get(i + 1)
                if (peek == c) {
                    buf.append(c)
                    i++
                } else {
                    break
                }
            }
        } else {
            // This will identify token as text.
            buf.append('\'')

            var inLiteral = false

            while (i < length) {
                c = pattern.get(i)

                if (c == '\'') {
                    if (i + 1 < length && pattern.get(i + 1) == '\'') {
                        // '' is treated as escaped '
                        i++
                        buf.append(c)
                    } else {
                        inLiteral = !inLiteral
                    }
                } else if (!inLiteral && (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z')) {
                    i--
                    break
                } else {
                    buf.append(c)
                }
                i++
            }
        }

        indexRef[0] = i
        return buf.toString()
    }

    /**
     * Gets an appropriate rule for the padding required.
     * 
     * @param field the field to get a rule for
     * @param padding the padding required
     * @return a new rule with the correct padding
     */
    internal fun selectNumberRule(field: Int, padding: Int): NumberRule {
        when (padding) {
            1 -> return UnpaddedNumberField(field)
            2 -> return TwoDigitNumberField(field)
            else -> return PaddedNumberField(field, padding)
        }
    }

    // Format methods
    // -----------------------------------------------------------------------
    /**
     * Formats a `Date`, `Calendar` or `Long` (milliseconds) object.
     * 
     * @param obj the object to format
     * @param toAppendTo the buffer to append to
     * @param pos the position - ignored
     * @return the buffer passed in
     */
    override fun format(
        obj: Any?,
        toAppendTo: StringBuffer,
        pos: FieldPosition
    ): StringBuffer {
        if (obj is Date) {
            return format(obj, toAppendTo)
        } else if (obj is Calendar) {
            return format(obj, toAppendTo)
        } else if (obj is Long) {
            return format(obj, toAppendTo)
        } else {
            throw IllegalArgumentException(
                "Unknown class: " + (if (obj == null) "<null>" else obj.javaClass.getName())
            )
        }
    }

    /* (non-Javadoc)
     * @see org.apache.commons.lang3.time.DatePrinter#format(long)
     */
    override fun format(millis: Long): String {
        val c: Calendar = newCalendar() // hard code GregorianCalendar
        c.setTimeInMillis(millis)
        return applyRulesToString(c)
    }

    /**
     * Creates a String representation of the given Calendar by applying the rules of this printer
     * to it.
     * 
     * @param c the Calender to apply the rules to.
     * @return a String representation of the given Calendar.
     */
    private fun applyRulesToString(c: Calendar): String {
        return applyRules(c, StringBuffer(this.maxLengthEstimate)).toString()
    }

    /**
     * Creation method for ne calender instances.
     * 
     * @return a new Calendar instance.
     */
    private fun newCalendar(): GregorianCalendar {
        // hard code GregorianCalendar
        return GregorianCalendar(mTimeZone, mLocale)
    }

    /* (non-Javadoc)
     * @see org.apache.commons.lang3.time.DatePrinter#format(java.util.Date)
     */
    override fun format(date: Date): String {
        val c: Calendar = newCalendar() // hard code GregorianCalendar
        c.setTime(date)
        return applyRulesToString(c)
    }

    /* (non-Javadoc)
     * @see org.apache.commons.lang3.time.DatePrinter#format(java.util.Calendar)
     */
    override fun format(calendar: Calendar): String {
        return format(calendar, StringBuffer(this.maxLengthEstimate)).toString()
    }

    /* (non-Javadoc)
     * @see org.apache.commons.lang3.time.DatePrinter#format(long, java.lang.StringBuffer)
     */
    override fun format(millis: Long, buf: StringBuffer): StringBuffer {
        return format(Date(millis), buf)
    }

    /* (non-Javadoc)
     * @see org.apache.commons.lang3.time.DatePrinter#format(java.util.Date, java.lang.StringBuffer)
     */
    override fun format(date: Date, buf: StringBuffer): StringBuffer {
        val c: Calendar = newCalendar() // hard code GregorianCalendar
        c.setTime(date)
        return applyRules(c, buf)
    }

    /* (non-Javadoc)
     * @see org.apache.commons.lang3.time.DatePrinter#format(java.util.Calendar, java.lang.StringBuffer)
     */
    override fun format(calendar: Calendar, buf: StringBuffer): StringBuffer {
        return applyRules(calendar, buf)
    }

    /**
     * Performs the formatting by applying the rules to the specified calendar.
     * 
     * @param calendar the calendar to format
     * @param buf the buffer to format into
     * @return the specified string buffer
     */
    fun applyRules(
        calendar: Calendar, buf: StringBuffer
    ): StringBuffer {
        for (rule in mRules) {
            rule.appendTo(buf, calendar)
        }
        return buf
    }

    // Accessors
    // -----------------------------------------------------------------------
    /* (non-Javadoc)
     * @see org.apache.commons.lang3.time.DatePrinter#getPattern()
     */
    override val pattern: String
        get() = mPattern

    /* (non-Javadoc)
     * @see org.apache.commons.lang3.time.DatePrinter#getTimeZone()
     */
    override val timeZone: TimeZone
        get() = mTimeZone

    /* (non-Javadoc)
     * @see org.apache.commons.lang3.time.DatePrinter#getLocale()
     */
    override val locale: Locale
        get() = mLocale

    // Basics
    // -----------------------------------------------------------------------
    /**
     * Compares two objects for equality.
     * 
     * @param obj the object to compare to
     * @return `true` if equal
     */
    override fun equals(obj: Any?): Boolean {
        if (obj is FastDatePrinter == false) {
            return false
        }
        val other = obj
        return mPattern == other.mPattern
                && mTimeZone == other.mTimeZone
                && mLocale == other.mLocale
    }

    /**
     * Returns a hashcode compatible with equals.
     * 
     * @return a hashcode compatible with equals
     */
    override fun hashCode(): Int {
        return mPattern.hashCode() + 13 * (mTimeZone.hashCode() + 13 * mLocale.hashCode())
    }

    /**
     * Gets a debugging string version of this formatter.
     * 
     * @return a debugging string
     */
    override fun toString(): String {
        return "FastDatePrinter[" + mPattern + "," + mLocale + "," + mTimeZone.getID() + "]"
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
        init()
    }

    // Rules
    // -----------------------------------------------------------------------
    /** Inner class defining a rule.  */
    internal interface Rule {
        /**
         * Returns the estimated length of the result.
         * 
         * @return the estimated length
         */
        fun estimateLength(): Int

        /**
         * Appends the value of the specified calendar to the output buffer based on the rule
         * implementation.
         * 
         * @param buffer the output buffer
         * @param calendar calendar to be appended
         */
        fun appendTo(buffer: StringBuffer, calendar: Calendar)
    }

    /** Inner class defining a numeric rule.  */
    internal interface NumberRule : Rule {
        /**
         * Appends the specified value to the output buffer based on the rule implementation.
         * 
         * @param buffer the output buffer
         * @param value the value to be appended
         */
        fun appendTo(buffer: StringBuffer, value: Int)
    }

    /** Inner class to output a constant single character.  */
    private class CharacterLiteral
    /**
     * Constructs a new instance of `CharacterLiteral` to hold the specified value.
     * 
     * @param value the character literal
     */(private val mValue: Char) : Rule {
        /** {@inheritDoc}  */
        override fun estimateLength(): Int {
            return 1
        }

        /** {@inheritDoc}  */
        override fun appendTo(buffer: StringBuffer, calendar: Calendar) {
            buffer.append(mValue)
        }
    }

    /** Inner class to output a constant string.  */
    private class StringLiteral
    /**
     * Constructs a new instance of `StringLiteral` to hold the specified value.
     * 
     * @param value the string literal
     */(private val mValue: String) : Rule {
        /** {@inheritDoc}  */
        override fun estimateLength(): Int {
            return mValue.length
        }

        /** {@inheritDoc}  */
        override fun appendTo(buffer: StringBuffer, calendar: Calendar) {
            buffer.append(mValue)
        }
    }

    /** Inner class to output one of a set of values.  */
    private class TextField
    /**
     * Constructs an instance of `TextField` with the specified field and values.
     * 
     * @param field the field
     * @param values the field values
     */(private val mField: Int, private val mValues: Array<String>) : Rule {
        /** {@inheritDoc}  */
        override fun estimateLength(): Int {
            var max = 0
            var i = mValues.size
            while (--i >= 0) {
                val len = mValues[i].length
                if (len > max) {
                    max = len
                }
            }
            return max
        }

        /** {@inheritDoc}  */
        override fun appendTo(buffer: StringBuffer, calendar: Calendar) {
            buffer.append(mValues[calendar.get(mField)])
        }
    }

    /** Inner class to output an unpadded number.  */
    private class UnpaddedNumberField
    /**
     * Constructs an instance of `UnpaddedNumberField` with the specified field.
     * 
     * @param field the field
     */(private val mField: Int) : NumberRule {
        /** {@inheritDoc}  */
        override fun estimateLength(): Int {
            return 4
        }

        /** {@inheritDoc}  */
        override fun appendTo(buffer: StringBuffer, calendar: Calendar) {
            appendTo(buffer, calendar.get(mField))
        }

        /** {@inheritDoc}  */
        override fun appendTo(buffer: StringBuffer, value: Int) {
            if (value < 10) {
                buffer.append((value + '0'.code).toChar())
            } else if (value < 100) {
                appendDigits(buffer, value)
            } else {
                buffer.append(value)
            }
        }
    }

    /** Inner class to output an unpadded month.  */
    private class UnpaddedMonthField
    /** Constructs an instance of `UnpaddedMonthField`.  */
        : NumberRule {
        /** {@inheritDoc}  */
        override fun estimateLength(): Int {
            return 2
        }

        /** {@inheritDoc}  */
        override fun appendTo(buffer: StringBuffer, calendar: Calendar) {
            appendTo(buffer, calendar.get(Calendar.MONTH) + 1)
        }

        /** {@inheritDoc}  */
        override fun appendTo(buffer: StringBuffer, value: Int) {
            if (value < 10) {
                buffer.append((value + '0'.code).toChar())
            } else {
                appendDigits(buffer, value)
            }
        }

        companion object {
            val INSTANCE: UnpaddedMonthField = UnpaddedMonthField()
        }
    }

    /** Inner class to output a padded number.  */
    private class PaddedNumberField(field: Int, size: Int) : NumberRule {
        private val mField: Int
        private val mSize: Int

        /**
         * Constructs an instance of `PaddedNumberField`.
         * 
         * @param field the field
         * @param size size of the output field
         */
        init {
            require(size >= 3)
            mField = field
            mSize = size
        }

        /** {@inheritDoc}  */
        override fun estimateLength(): Int {
            return mSize
        }

        /** {@inheritDoc}  */
        override fun appendTo(buffer: StringBuffer, calendar: Calendar) {
            appendTo(buffer, calendar.get(mField))
        }

        /** {@inheritDoc}  */
        override fun appendTo(buffer: StringBuffer, value: Int) {
            // pad the buffer with adequate zeros
            var value = value
            for (digit in 0..<mSize) {
                buffer.append('0')
            }
            // backfill the buffer with non-zero digits
            var index = buffer.length
            while (value > 0) {
                buffer.setCharAt(--index, ('0'.code + value % 10).toChar())
                value /= 10
            }
        }
    }

    /** Inner class to output a two digit number.  */
    private class TwoDigitNumberField
    /**
     * Constructs an instance of `TwoDigitNumberField` with the specified field.
     * 
     * @param field the field
     */(private val mField: Int) : NumberRule {
        /** {@inheritDoc}  */
        override fun estimateLength(): Int {
            return 2
        }

        /** {@inheritDoc}  */
        override fun appendTo(buffer: StringBuffer, calendar: Calendar) {
            appendTo(buffer, calendar.get(mField))
        }

        /** {@inheritDoc}  */
        override fun appendTo(buffer: StringBuffer, value: Int) {
            if (value < 100) {
                appendDigits(buffer, value)
            } else {
                buffer.append(value)
            }
        }
    }

    /** Inner class to output a two digit year.  */
    private class TwoDigitYearField
    /** Constructs an instance of `TwoDigitYearField`.  */
        : NumberRule {
        /** {@inheritDoc}  */
        override fun estimateLength(): Int {
            return 2
        }

        /** {@inheritDoc}  */
        override fun appendTo(buffer: StringBuffer, calendar: Calendar) {
            appendTo(buffer, calendar.get(Calendar.YEAR) % 100)
        }

        /** {@inheritDoc}  */
        override fun appendTo(buffer: StringBuffer, value: Int) {
            appendDigits(buffer, value)
        }

        companion object {
            val INSTANCE: TwoDigitYearField = TwoDigitYearField()
        }
    }

    /** Inner class to output a two digit month.  */
    private class TwoDigitMonthField
    /** Constructs an instance of `TwoDigitMonthField`.  */
        : NumberRule {
        /** {@inheritDoc}  */
        override fun estimateLength(): Int {
            return 2
        }

        /** {@inheritDoc}  */
        override fun appendTo(buffer: StringBuffer, calendar: Calendar) {
            appendTo(buffer, calendar.get(Calendar.MONTH) + 1)
        }

        /** {@inheritDoc}  */
        override fun appendTo(buffer: StringBuffer, value: Int) {
            appendDigits(buffer, value)
        }

        companion object {
            val INSTANCE: TwoDigitMonthField = TwoDigitMonthField()
        }
    }

    /** Inner class to output the twelve hour field.  */
    private class TwelveHourField
    /**
     * Constructs an instance of `TwelveHourField` with the specified `NumberRule`.
     * 
     * @param rule the rule
     */(private val mRule: NumberRule) : NumberRule {
        /** {@inheritDoc}  */
        override fun estimateLength(): Int {
            return mRule.estimateLength()
        }

        /** {@inheritDoc}  */
        override fun appendTo(buffer: StringBuffer, calendar: Calendar) {
            var value = calendar.get(Calendar.HOUR)
            if (value == 0) {
                value = calendar.getLeastMaximum(Calendar.HOUR) + 1
            }
            mRule.appendTo(buffer, value)
        }

        /** {@inheritDoc}  */
        override fun appendTo(buffer: StringBuffer, value: Int) {
            mRule.appendTo(buffer, value)
        }
    }

    /** Inner class to output the twenty four hour field.  */
    private class TwentyFourHourField
    /**
     * Constructs an instance of `TwentyFourHourField` with the specified `NumberRule`.
     * 
     * @param rule the rule
     */(private val mRule: NumberRule) : NumberRule {
        /** {@inheritDoc}  */
        override fun estimateLength(): Int {
            return mRule.estimateLength()
        }

        /** {@inheritDoc}  */
        override fun appendTo(buffer: StringBuffer, calendar: Calendar) {
            var value = calendar.get(Calendar.HOUR_OF_DAY)
            if (value == 0) {
                value = calendar.getMaximum(Calendar.HOUR_OF_DAY) + 1
            }
            mRule.appendTo(buffer, value)
        }

        /** {@inheritDoc}  */
        override fun appendTo(buffer: StringBuffer, value: Int) {
            mRule.appendTo(buffer, value)
        }
    }

    // Constructor
    // -----------------------------------------------------------------------
    /**
     * Constructs a new FastDatePrinter. Use [FastDateFormat.getInstance] or another variation of the factory methods of [FastDateFormat] to get a
     * cached FastDatePrinter instance.
     * 
     * @param pattern [java.text.SimpleDateFormat] compatible pattern
     * @param timeZone non-null time zone to use
     * @param locale non-null locale to use
     * @throws NullPointerException if pattern, timeZone, or locale is null.
     */
    init {
        init()
    }

    /** Inner class to output a time zone name.  */
    private class TimeZoneNameRule(timeZone: TimeZone, private val mLocale: Locale, private val mStyle: Int) : Rule {
        private val mStandard: String
        private val mDaylight: String

        /**
         * Constructs an instance of `TimeZoneNameRule` with the specified properties.
         * 
         * @param timeZone the time zone
         * @param locale the locale
         * @param style the style
         */
        init {
            mStandard = getTimeZoneDisplay(
                timeZone, false,
                mStyle,
                mLocale
            )
            mDaylight = getTimeZoneDisplay(
                timeZone, true,
                mStyle,
                mLocale
            )
        }

        /** {@inheritDoc}  */
        override fun estimateLength(): Int {
            // We have no access to the Calendar object that will be passed to
            // appendTo so base estimate on the TimeZone passed to the
            // constructor
            return max(mStandard.length, mDaylight.length)
        }

        /** {@inheritDoc}  */
        override fun appendTo(buffer: StringBuffer, calendar: Calendar) {
            val zone = calendar.getTimeZone()
            if (calendar.get(Calendar.DST_OFFSET) != 0) {
                buffer.append(getTimeZoneDisplay(zone, true, mStyle, mLocale))
            } else {
                buffer.append(getTimeZoneDisplay(zone, false, mStyle, mLocale))
            }
        }
    }

    /** Inner class to output a time zone as a number `+/-HHMM` or `+/-HH:MM`.  */
    private class TimeZoneNumberRule
    /**
     * Constructs an instance of `TimeZoneNumberRule` with the specified properties.
     * 
     * @param colon add colon between HH and MM in the output if `true`
     * @param iso8601 create an ISO 8601 format output
     */(val mColon: Boolean, val mISO8601: Boolean) : Rule {
        /** {@inheritDoc}  */
        override fun estimateLength(): Int {
            return 5
        }

        /** {@inheritDoc}  */
        override fun appendTo(buffer: StringBuffer, calendar: Calendar) {
            if (mISO8601 && calendar.getTimeZone().getID() == "UTC") {
                buffer.append("Z")
                return
            }

            var offset = calendar.get(Calendar.ZONE_OFFSET) + calendar.get(Calendar.DST_OFFSET)

            if (offset < 0) {
                buffer.append('-')
                offset = -offset
            } else {
                buffer.append('+')
            }

            val hours = offset / (60 * 60 * 1000)
            appendDigits(buffer, hours)

            if (mColon) {
                buffer.append(':')
            }

            val minutes = offset / (60 * 1000) - 60 * hours
            appendDigits(buffer, minutes)
        }

        companion object {
            val INSTANCE_COLON: TimeZoneNumberRule = TimeZoneNumberRule(true, false)
            val INSTANCE_NO_COLON: TimeZoneNumberRule = TimeZoneNumberRule(false, false)
            val INSTANCE_ISO_8601: TimeZoneNumberRule = TimeZoneNumberRule(true, true)
        }
    }

    /** Inner class to output a time zone as a number `+/-HHMM` or `+/-HH:MM`.  */
    private class Iso8601_Rule
    /**
     * Constructs an instance of `Iso8601_Rule` with the specified properties.
     * 
     * @param length The number of characters in output (unless Z is output)
     */(val length: Int) : Rule {
        /** {@inheritDoc}  */
        override fun estimateLength(): Int {
            return length
        }

        /** {@inheritDoc}  */
        override fun appendTo(buffer: StringBuffer, calendar: Calendar) {
            val zoneOffset = calendar.get(Calendar.ZONE_OFFSET)
            if (zoneOffset == 0) {
                buffer.append("Z")
                return
            }

            var offset = zoneOffset + calendar.get(Calendar.DST_OFFSET)

            if (offset < 0) {
                buffer.append('-')
                offset = -offset
            } else {
                buffer.append('+')
            }

            val hours = offset / (60 * 60 * 1000)
            appendDigits(buffer, hours)

            if (length < 5) {
                return
            }

            if (length == 6) {
                buffer.append(':')
            }

            val minutes = offset / (60 * 1000) - 60 * hours
            appendDigits(buffer, minutes)
        }

        companion object {
            // Sign TwoDigitHours or Z
            val ISO8601_HOURS: Iso8601_Rule = Iso8601_Rule(3)

            // Sign TwoDigitHours Minutes or Z
            val ISO8601_HOURS_MINUTES: Iso8601_Rule = Iso8601_Rule(5)

            // Sign TwoDigitHours : Minutes or Z
            val ISO8601_HOURS_COLON_MINUTES: Iso8601_Rule = Iso8601_Rule(6)

            /**
             * Factory method for Iso8601_Rules.
             * 
             * @param tokenLen a token indicating the length of the TimeZone String to be formatted.
             * @return a Iso8601_Rule that can format TimeZone String of length `tokenLen`. If no
             * such rule exists, an IllegalArgumentException will be thrown.
             */
            fun getRule(tokenLen: Int): Iso8601_Rule {
                when (tokenLen) {
                    1 -> return ISO8601_HOURS
                    2 -> return ISO8601_HOURS_MINUTES
                    3 -> return ISO8601_HOURS_COLON_MINUTES
                    else -> throw IllegalArgumentException("invalid number of X")
                }
            }
        }
    }

    // ----------------------------------------------------------------------
    /** Inner class that acts as a compound key for time zone names.  */
    private class TimeZoneDisplayKey(
        private val mTimeZone: TimeZone,
        daylight: Boolean,
        style: Int,
        locale: Locale
    ) {
        private val mStyle: Int
        private val mLocale: Locale

        /**
         * Constructs an instance of `TimeZoneDisplayKey` with the specified properties.
         * 
         * @param timeZone the time zone
         * @param daylight adjust the style for daylight saving time if `true`
         * @param style the timezone style
         * @param locale the timezone locale
         */
        init {
            if (daylight) {
                mStyle = style or -0x80000000
            } else {
                mStyle = style
            }
            mLocale = locale
        }

        /** {@inheritDoc}  */
        override fun hashCode(): Int {
            return (mStyle * 31 + mLocale.hashCode()) * 31 + mTimeZone.hashCode()
        }

        /** {@inheritDoc}  */
        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            if (obj is TimeZoneDisplayKey) {
                val other = obj
                return mTimeZone == other.mTimeZone
                        && mStyle == other.mStyle && mLocale == other.mLocale
            }
            return false
        }
    }

    companion object {
        // A lot of the speed in this class comes from caching, but some comes
        // from the special int to StringBuffer conversion.
        //
        // The following produces a padded 2 digit number:
        //   buffer.append((char)(value / 10 + '0'));
        //   buffer.append((char)(value % 10 + '0'));
        //
        // Note that the fastest append to StringBuffer is a single char (used here).
        // Note that Integer.toString() is not called, the conversion is simply
        // taking the value and adding (mathematically) the ASCII value for '0'.
        // So, don't change this code! It works and is very fast.
        /**
         * Required for serialization support.
         * 
         * @see Serializable
         */
        private const val serialVersionUID = 1L

        /** FULL locale dependent date or time style.  */
        val FULL: Int = DateFormat.FULL

        /** LONG locale dependent date or time style.  */
        val LONG: Int = DateFormat.LONG

        /** MEDIUM locale dependent date or time style.  */
        val MEDIUM: Int = DateFormat.MEDIUM

        /** SHORT locale dependent date or time style.  */
        val SHORT: Int = DateFormat.SHORT

        /**
         * Appends digits to the given buffer.
         * 
         * @param buffer the buffer to append to.
         * @param value the value to append digits from.
         */
        private fun appendDigits(buffer: StringBuffer, value: Int) {
            buffer.append((value / 10 + '0'.code).toChar())
            buffer.append((value % 10 + '0'.code).toChar())
        }

        // -----------------------------------------------------------------------
        private val cTimeZoneDisplayCache: ConcurrentMap<TimeZoneDisplayKey, String> =
            ConcurrentHashMap<TimeZoneDisplayKey, String>(7)

        /**
         * Gets the time zone display name, using a cache for performance.
         * 
         * @param tz the zone to query
         * @param daylight true if daylight savings
         * @param style the style to use `TimeZone.LONG` or `TimeZone.SHORT`
         * @param locale the locale to use
         * @return the textual name of the time zone
         */
        fun getTimeZoneDisplay(
            tz: TimeZone,
            daylight: Boolean,
            style: Int,
            locale: Locale
        ): String {
            val key = TimeZoneDisplayKey(tz, daylight, style, locale)
            var value: String? = cTimeZoneDisplayCache.get(key)
            if (value == null) {
                // This is a very slow call, so cache the results.
                value = tz.getDisplayName(daylight, style, locale)
                val prior: String? = cTimeZoneDisplayCache.putIfAbsent(key, value)
                if (prior != null) {
                    value = prior
                }
            }
            return value
        }
    }
}
