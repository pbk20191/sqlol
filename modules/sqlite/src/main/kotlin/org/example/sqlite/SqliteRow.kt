package org.example.sqlite

/**
 * materialized 결과 행 1개 — `query()` 가 반환하는 List 의 원소 (JDBC ResultSet 의 행 대응).
 *
 * 값은 sqlite **storage class 그대로** 디코드된다: INTEGER→[Long], FLOAT→[Double], TEXT→[String],
 * BLOB→[ByteArray], NULL→null. 정확한 타입/NULL 판별은 [value]/[isNull], get* 접근자는 sqlite
 * 형변환 시맨틱 근사 (NULL→0/""/빈 배열, 숫자↔텍스트 변환 — Double 텍스트화는 Kotlin toString 으로
 * sqlite %.15g 와 미세하게 다를 수 있음).
 */
class SqliteRow internal constructor(
    private val columns: List<String>,   // 결과 전체가 같은 리스트를 공유
    private val values: Array<Any?>,
) {
    val columnCount: Int get() = values.size

    fun columnName(i: Int): String = columns[i]

    fun indexOf(name: String): Int {
        val i = columns.indexOf(name)
        require(i >= 0) { "컬럼 없음: $name (컬럼: $columns)" }
        return i
    }

    fun isNull(i: Int): Boolean = values[i] == null
    fun isNull(name: String): Boolean = isNull(indexOf(name))

    /** 저장 클래스 그대로 (Long/Double/String/ByteArray/null). */
    fun value(i: Int): Any? = values[i]
    fun value(name: String): Any? = value(indexOf(name))

    fun getLong(i: Int): Long = when (val v = values[i]) {
        null -> 0L
        is Long -> v
        is Double -> v.toLong()
        is String -> v.trim().toLongOrNull() ?: v.trim().toDoubleOrNull()?.toLong() ?: 0L
        else -> 0L
    }

    fun getDouble(i: Int): Double = when (val v = values[i]) {
        null -> 0.0
        is Long -> v.toDouble()
        is Double -> v
        is String -> v.trim().toDoubleOrNull() ?: 0.0
        else -> 0.0
    }

    fun getText(i: Int): String = when (val v = values[i]) {
        null -> ""
        is String -> v
        is ByteArray -> String(v, Charsets.UTF_8)
        else -> v.toString()
    }

    fun getBlob(i: Int): ByteArray = when (val v = values[i]) {
        null -> ByteArray(0)
        is ByteArray -> v
        is String -> v.toByteArray(Charsets.UTF_8)
        else -> v.toString().toByteArray(Charsets.UTF_8)
    }

    fun getLong(name: String): Long = getLong(indexOf(name))
    fun getDouble(name: String): Double = getDouble(indexOf(name))
    fun getText(name: String): String = getText(indexOf(name))
    fun getBlob(name: String): ByteArray = getBlob(indexOf(name))

    override fun toString(): String = columns.indices.joinToString(", ", "{", "}") {
        val v = values[it]
        "${columns[it]}=" + if (v is ByteArray) "blob(${v.size}B)" else v.toString()
    }
}
