package org.example.sqlite.jdbc.jdbc4

import org.example.sqlite.jdbc.SQLiteConnection
import org.example.sqlite.jdbc.jdbc3.JDBC3DatabaseMetaData
import java.sql.ResultSet
import java.sql.RowIdLifetime
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException

class JDBC4DatabaseMetaData(conn: SQLiteConnection) : JDBC3DatabaseMetaData(conn) {
    // JDBC 4
    @Throws(ClassCastException::class)
    override fun <T> unwrap(iface: Class<T?>): T? {
        return iface.cast(this)
    }

    override fun isWrapperFor(iface: Class<*>): Boolean {
        return iface.isInstance(this)
    }

    @Throws(SQLException::class)
    override fun getRowIdLifetime(): RowIdLifetime? {
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun getSchemas(catalog: String?, schemaPattern: String?): ResultSet? {
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun supportsStoredFunctionsUsingCallSyntax(): Boolean {
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun autoCommitFailureClosesAllResultSets(): Boolean {
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun getClientInfoProperties(): ResultSet? {
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun getFunctions(
        catalog: String?,
        schemaPattern: String?,
        functionNamePattern: String?
    ): ResultSet? {
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun getPseudoColumns(
        catalog: String?,
        schemaPattern: String?,
        tableNamePattern: String?,
        columnNamePattern: String?
    ): ResultSet? {
        throw SQLFeatureNotSupportedException()
    }

    @Throws(SQLException::class)
    override fun generatedKeyAlwaysReturned(): Boolean {
        throw SQLFeatureNotSupportedException()
    }
}
