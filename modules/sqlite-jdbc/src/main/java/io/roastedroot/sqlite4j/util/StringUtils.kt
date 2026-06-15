package io.roastedroot.sqlite4j.util

object StringUtils {
    @JvmStatic
    fun join(list: List<String>, separator: String): String {
        val sb = StringBuilder()
        var first = true
        for (item in list) {
            if (first) first = false
            else sb.append(separator)

            sb.append(item)
        }
        return sb.toString()
    }
}
