package io.roastedroot.sqlite4j.util

import java.lang.String.join
import java.util.function.Consumer
import java.util.function.Function
import java.util.stream.Collectors
import kotlin.Any
import kotlin.collections.map
import kotlin.require

object QueryUtils {
    /**
     * Build a SQLite query using the VALUES clause to return arbitrary values.
     * 
     * @param columns list of column names
     * @param valuesList values to return as rows
     * @return SQL query as string
     */
    @JvmStatic
    fun valuesQuery(columns: List<String>, valuesList: List<List<Any?>>): String {
        valuesList.forEach(
            Consumer { list: List<Any?>? ->
                require(list!!.size == columns.size) { "values and columns must have the same size" }
            })
        return ("with cte("
                + join(",", columns)
                + ") as (values "
                + valuesList
            .map { values ->
                ("("
                        + values
                    .map
                        { o: Any? ->
                            if (o is String)  return@map "'$o'"
                            if (o == null)  return@map "null"
                            return@map o.toString()
                        }
                    .joinToString(",")
//                    .collect(Collectors.joining(","))
                        + ")")
            }.joinToString(",")
//            .collect(Collectors.joining(","))
                + ") select * from cte")
    }
}
