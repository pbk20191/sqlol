package org.example.sqlite.jdbc.jdbc3

import org.example.sqlite.jdbc.SQLiteConnection
import org.example.sqlite.jdbc.core.CoreDatabaseMetaData
import org.example.sqlite.jdbc.core.CoreStatement
import org.example.sqlite.jdbc.util.LoggerFactory.getLogger
import org.example.sqlite.jdbc.util.QueryUtils.valuesQuery
import org.example.sqlite.jdbc.util.StringUtils.join
import java.io.IOException
import java.sql.*
import java.util.*
import java.util.function.Supplier
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.stream.Collectors

abstract class JDBC3DatabaseMetaData protected constructor(conn: SQLiteConnection) : CoreDatabaseMetaData(conn) {
    /**
     * @see DatabaseMetaData.getConnection
     */
    override fun getConnection(): Connection {
        return conn!!
    }

    /**
     * @see DatabaseMetaData.getDatabaseMajorVersion
     */
    @Throws(SQLException::class)
    override fun getDatabaseMajorVersion(): Int {
        return conn!!.libversion().split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()[0].toInt()
    }

    /**
     * @see DatabaseMetaData.getDatabaseMinorVersion
     */
    @Throws(SQLException::class)
    override fun getDatabaseMinorVersion(): Int {
        return conn!!.libversion().split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()[1].toInt()
    }

    /**
     * @see DatabaseMetaData.getDriverMajorVersion
     */
    override fun getDriverMajorVersion(): Int {
        return Companion.driverVersion!!.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()[0].toInt()
    }

    /**
     * @see DatabaseMetaData.getDriverMinorVersion
     */
    override fun getDriverMinorVersion(): Int {
        return Companion.driverVersion!!.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()[1].toInt()
    }

    /**
     * @see DatabaseMetaData.getJDBCMajorVersion
     */
    override fun getJDBCMajorVersion(): Int {
        return 4
    }

    /**
     * @see DatabaseMetaData.getJDBCMinorVersion
     */
    override fun getJDBCMinorVersion(): Int {
        return 2
    }

    /**
     * @see DatabaseMetaData.getDefaultTransactionIsolation
     */
    override fun getDefaultTransactionIsolation(): Int {
        return Connection.TRANSACTION_SERIALIZABLE
    }

    /**
     * @see DatabaseMetaData.getMaxBinaryLiteralLength
     */
    override fun getMaxBinaryLiteralLength(): Int {
        return 0
    }

    /**
     * @see DatabaseMetaData.getMaxCatalogNameLength
     */
    override fun getMaxCatalogNameLength(): Int {
        return 0
    }

    /**
     * @see DatabaseMetaData.getMaxCharLiteralLength
     */
    override fun getMaxCharLiteralLength(): Int {
        return 0
    }

    /**
     * @see DatabaseMetaData.getMaxColumnNameLength
     */
    override fun getMaxColumnNameLength(): Int {
        return 0
    }

    /**
     * @see DatabaseMetaData.getMaxColumnsInGroupBy
     */
    override fun getMaxColumnsInGroupBy(): Int {
        return 0
    }

    /**
     * @see DatabaseMetaData.getMaxColumnsInIndex
     */
    override fun getMaxColumnsInIndex(): Int {
        return 0
    }

    /**
     * @see DatabaseMetaData.getMaxColumnsInOrderBy
     */
    override fun getMaxColumnsInOrderBy(): Int {
        return 0
    }

    /**
     * @see DatabaseMetaData.getMaxColumnsInSelect
     */
    override fun getMaxColumnsInSelect(): Int {
        return 0
    }

    /**
     * @see DatabaseMetaData.getMaxColumnsInTable
     */
    override fun getMaxColumnsInTable(): Int {
        return 0
    }

    /**
     * @see DatabaseMetaData.getMaxConnections
     */
    override fun getMaxConnections(): Int {
        return 0
    }

    /**
     * @see DatabaseMetaData.getMaxCursorNameLength
     */
    override fun getMaxCursorNameLength(): Int {
        return 0
    }

    /**
     * @see DatabaseMetaData.getMaxIndexLength
     */
    override fun getMaxIndexLength(): Int {
        return 0
    }

    /**
     * @see DatabaseMetaData.getMaxProcedureNameLength
     */
    override fun getMaxProcedureNameLength(): Int {
        return 0
    }

    /**
     * @see DatabaseMetaData.getMaxRowSize
     */
    override fun getMaxRowSize(): Int {
        return 0
    }

    /**
     * @see DatabaseMetaData.getMaxSchemaNameLength
     */
    override fun getMaxSchemaNameLength(): Int {
        return 0
    }

    /**
     * @see DatabaseMetaData.getMaxStatementLength
     */
    override fun getMaxStatementLength(): Int {
        return 0
    }

    /**
     * @see DatabaseMetaData.getMaxStatements
     */
    override fun getMaxStatements(): Int {
        return 0
    }

    /**
     * @see DatabaseMetaData.getMaxTableNameLength
     */
    override fun getMaxTableNameLength(): Int {
        return 0
    }

    /**
     * @see DatabaseMetaData.getMaxTablesInSelect
     */
    override fun getMaxTablesInSelect(): Int {
        return 0
    }

    /**
     * @see DatabaseMetaData.getMaxUserNameLength
     */
    override fun getMaxUserNameLength(): Int {
        return 0
    }

    /**
     * @see DatabaseMetaData.getResultSetHoldability
     */
    override fun getResultSetHoldability(): Int {
        return ResultSet.CLOSE_CURSORS_AT_COMMIT
    }

    /**
     * @see DatabaseMetaData.getSQLStateType
     */
    override fun getSQLStateType(): Int {
        return DatabaseMetaData.sqlStateSQL99
    }

    /**
     * @see DatabaseMetaData.getDatabaseProductName
     */
    override fun getDatabaseProductName(): String {
        return "SQLite"
    }

    /**
     * @see DatabaseMetaData.getDatabaseProductVersion
     */
    @Throws(SQLException::class)
    override fun getDatabaseProductVersion(): String {
        return conn!!.libversion()
    }

    /**
     * @see DatabaseMetaData.getDriverName
     */
    override fun getDriverName(): String? {
        return Companion.driverName
    }

    /**
     * @see DatabaseMetaData.getDriverVersion
     */
    override fun getDriverVersion(): String? {
        return Companion.driverVersion
    }

    /**
     * @see DatabaseMetaData.getExtraNameCharacters
     */
    override fun getExtraNameCharacters(): String {
        return ""
    }

    /**
     * @see DatabaseMetaData.getCatalogSeparator
     */
    override fun getCatalogSeparator(): String {
        return "."
    }

    /**
     * @see DatabaseMetaData.getCatalogTerm
     */
    override fun getCatalogTerm(): String {
        return "catalog"
    }

    /**
     * @see DatabaseMetaData.getSchemaTerm
     */
    override fun getSchemaTerm(): String {
        return "schema"
    }

    /**
     * @see DatabaseMetaData.getProcedureTerm
     */
    override fun getProcedureTerm(): String {
        return "not_implemented"
    }

    /**
     * @see DatabaseMetaData.getSearchStringEscape
     */
    override fun getSearchStringEscape(): String {
        return "\\"
    }

    /**
     * @see DatabaseMetaData.getIdentifierQuoteString
     */
    override fun getIdentifierQuoteString(): String {
        return "\""
    }

    /**
     * @see DatabaseMetaData.getSQLKeywords
     * @see [SQLite Keywords](https://www.sqlite.org/lang_keywords.html)
     */
    override fun getSQLKeywords(): String {
        return ("ABORT,ACTION,AFTER,ANALYZE,ATTACH,AUTOINCREMENT,BEFORE,"
                + "CASCADE,CONFLICT,DATABASE,DEFERRABLE,DEFERRED,DESC,DETACH,"
                + "EXCLUSIVE,EXPLAIN,FAIL,GLOB,IGNORE,INDEX,INDEXED,INITIALLY,INSTEAD,ISNULL,"
                + "KEY,LIMIT,NOTNULL,OFFSET,PLAN,PRAGMA,QUERY,"
                + "RAISE,REGEXP,REINDEX,RENAME,REPLACE,RESTRICT,"
                + "TEMP,TEMPORARY,TRANSACTION,VACUUM,VIEW,VIRTUAL")
    }

    /**
     * @see DatabaseMetaData.getNumericFunctions
     */
    override fun getNumericFunctions(): String {
        return ""
    }

    /**
     * @see DatabaseMetaData.getStringFunctions
     */
    override fun getStringFunctions(): String {
        return ""
    }

    /**
     * @see DatabaseMetaData.getSystemFunctions
     */
    override fun getSystemFunctions(): String {
        return ""
    }

    /**
     * @see DatabaseMetaData.getTimeDateFunctions
     */
    override fun getTimeDateFunctions(): String {
        return "DATE,TIME,DATETIME,JULIANDAY,STRFTIME"
    }

    /**
     * @see DatabaseMetaData.getURL
     */
    override fun getURL(): String {
        return conn!!.url
    }

    /**
     * @see DatabaseMetaData.getUserName
     */
    override fun getUserName(): String? {
        return null
    }

    /**
     * @see DatabaseMetaData.allProceduresAreCallable
     */
    override fun allProceduresAreCallable(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.allTablesAreSelectable
     */
    override fun allTablesAreSelectable(): Boolean {
        return true
    }

    /**
     * @see DatabaseMetaData.dataDefinitionCausesTransactionCommit
     */
    override fun dataDefinitionCausesTransactionCommit(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.dataDefinitionIgnoredInTransactions
     */
    override fun dataDefinitionIgnoredInTransactions(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.doesMaxRowSizeIncludeBlobs
     */
    override fun doesMaxRowSizeIncludeBlobs(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.deletesAreDetected
     */
    override fun deletesAreDetected(type: Int): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.insertsAreDetected
     */
    override fun insertsAreDetected(type: Int): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.isCatalogAtStart
     */
    override fun isCatalogAtStart(): Boolean {
        return true
    }

    /**
     * @see DatabaseMetaData.locatorsUpdateCopy
     */
    override fun locatorsUpdateCopy(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.nullPlusNonNullIsNull
     */
    override fun nullPlusNonNullIsNull(): Boolean {
        return true
    }

    /**
     * @see DatabaseMetaData.nullsAreSortedAtEnd
     */
    override fun nullsAreSortedAtEnd(): Boolean {
        return !nullsAreSortedAtStart()
    }

    /**
     * @see DatabaseMetaData.nullsAreSortedAtStart
     */
    override fun nullsAreSortedAtStart(): Boolean {
        return true
    }

    /**
     * @see DatabaseMetaData.nullsAreSortedHigh
     */
    override fun nullsAreSortedHigh(): Boolean {
        return true
    }

    /**
     * @see DatabaseMetaData.nullsAreSortedLow
     */
    override fun nullsAreSortedLow(): Boolean {
        return !nullsAreSortedHigh()
    }

    /**
     * @see DatabaseMetaData.othersDeletesAreVisible
     */
    override fun othersDeletesAreVisible(type: Int): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.othersInsertsAreVisible
     */
    override fun othersInsertsAreVisible(type: Int): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.othersUpdatesAreVisible
     */
    override fun othersUpdatesAreVisible(type: Int): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.ownDeletesAreVisible
     */
    override fun ownDeletesAreVisible(type: Int): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.ownInsertsAreVisible
     */
    override fun ownInsertsAreVisible(type: Int): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.ownUpdatesAreVisible
     */
    override fun ownUpdatesAreVisible(type: Int): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.storesLowerCaseIdentifiers
     */
    override fun storesLowerCaseIdentifiers(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.storesLowerCaseQuotedIdentifiers
     */
    override fun storesLowerCaseQuotedIdentifiers(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.storesMixedCaseIdentifiers
     */
    override fun storesMixedCaseIdentifiers(): Boolean {
        return true
    }

    /**
     * @see DatabaseMetaData.storesMixedCaseQuotedIdentifiers
     */
    override fun storesMixedCaseQuotedIdentifiers(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.storesUpperCaseIdentifiers
     */
    override fun storesUpperCaseIdentifiers(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.storesUpperCaseQuotedIdentifiers
     */
    override fun storesUpperCaseQuotedIdentifiers(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsAlterTableWithAddColumn
     */
    override fun supportsAlterTableWithAddColumn(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsAlterTableWithDropColumn
     */
    override fun supportsAlterTableWithDropColumn(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsANSI92EntryLevelSQL
     */
    override fun supportsANSI92EntryLevelSQL(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsANSI92FullSQL
     */
    override fun supportsANSI92FullSQL(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsANSI92IntermediateSQL
     */
    override fun supportsANSI92IntermediateSQL(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsBatchUpdates
     */
    override fun supportsBatchUpdates(): Boolean {
        return true
    }

    /**
     * @see DatabaseMetaData.supportsCatalogsInDataManipulation
     */
    override fun supportsCatalogsInDataManipulation(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsCatalogsInIndexDefinitions
     */
    override fun supportsCatalogsInIndexDefinitions(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsCatalogsInPrivilegeDefinitions
     */
    override fun supportsCatalogsInPrivilegeDefinitions(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsCatalogsInProcedureCalls
     */
    override fun supportsCatalogsInProcedureCalls(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsCatalogsInTableDefinitions
     */
    override fun supportsCatalogsInTableDefinitions(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsColumnAliasing
     */
    override fun supportsColumnAliasing(): Boolean {
        return true
    }

    /**
     * @see DatabaseMetaData.supportsConvert
     */
    override fun supportsConvert(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsConvert
     */
    override fun supportsConvert(fromType: Int, toType: Int): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsCorrelatedSubqueries
     */
    override fun supportsCorrelatedSubqueries(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsDataDefinitionAndDataManipulationTransactions
     */
    override fun supportsDataDefinitionAndDataManipulationTransactions(): Boolean {
        return true
    }

    /**
     * @see DatabaseMetaData.supportsDataManipulationTransactionsOnly
     */
    override fun supportsDataManipulationTransactionsOnly(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsDifferentTableCorrelationNames
     */
    override fun supportsDifferentTableCorrelationNames(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsExpressionsInOrderBy
     */
    override fun supportsExpressionsInOrderBy(): Boolean {
        return true
    }

    /**
     * @see DatabaseMetaData.supportsMinimumSQLGrammar
     */
    override fun supportsMinimumSQLGrammar(): Boolean {
        return true
    }

    /**
     * @see DatabaseMetaData.supportsCoreSQLGrammar
     */
    override fun supportsCoreSQLGrammar(): Boolean {
        return true
    }

    /**
     * @see DatabaseMetaData.supportsExtendedSQLGrammar
     */
    override fun supportsExtendedSQLGrammar(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsLimitedOuterJoins
     */
    override fun supportsLimitedOuterJoins(): Boolean {
        return true
    }

    /**
     * @see DatabaseMetaData.supportsFullOuterJoins
     */
    @Throws(SQLException::class)
    override fun supportsFullOuterJoins(): Boolean {
        val version = conn!!.libversion().split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        return version[0].toInt() >= 3 && version[1].toInt() >= 39
    }

    /**
     * @see DatabaseMetaData.supportsGetGeneratedKeys
     */
    override fun supportsGetGeneratedKeys(): Boolean {
        return true
    }

    /**
     * @see DatabaseMetaData.supportsGroupBy
     */
    override fun supportsGroupBy(): Boolean {
        return true
    }

    /**
     * @see DatabaseMetaData.supportsGroupByBeyondSelect
     */
    override fun supportsGroupByBeyondSelect(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsGroupByUnrelated
     */
    override fun supportsGroupByUnrelated(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsIntegrityEnhancementFacility
     */
    override fun supportsIntegrityEnhancementFacility(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsLikeEscapeClause
     */
    override fun supportsLikeEscapeClause(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsMixedCaseIdentifiers
     */
    override fun supportsMixedCaseIdentifiers(): Boolean {
        return true
    }

    /**
     * @see DatabaseMetaData.supportsMixedCaseQuotedIdentifiers
     */
    override fun supportsMixedCaseQuotedIdentifiers(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsMultipleOpenResults
     */
    override fun supportsMultipleOpenResults(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsMultipleResultSets
     */
    override fun supportsMultipleResultSets(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsMultipleTransactions
     */
    override fun supportsMultipleTransactions(): Boolean {
        return true
    }

    /**
     * @see DatabaseMetaData.supportsNamedParameters
     */
    override fun supportsNamedParameters(): Boolean {
        return true
    }

    /**
     * @see DatabaseMetaData.supportsNonNullableColumns
     */
    override fun supportsNonNullableColumns(): Boolean {
        return true
    }

    /**
     * @see DatabaseMetaData.supportsOpenCursorsAcrossCommit
     */
    override fun supportsOpenCursorsAcrossCommit(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsOpenCursorsAcrossRollback
     */
    override fun supportsOpenCursorsAcrossRollback(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsOpenStatementsAcrossCommit
     */
    override fun supportsOpenStatementsAcrossCommit(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsOpenStatementsAcrossRollback
     */
    override fun supportsOpenStatementsAcrossRollback(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsOrderByUnrelated
     */
    override fun supportsOrderByUnrelated(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsOuterJoins
     */
    override fun supportsOuterJoins(): Boolean {
        return true
    }

    /**
     * @see DatabaseMetaData.supportsPositionedDelete
     */
    override fun supportsPositionedDelete(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsPositionedUpdate
     */
    override fun supportsPositionedUpdate(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsResultSetConcurrency
     */
    override fun supportsResultSetConcurrency(t: Int, c: Int): Boolean {
        return t == ResultSet.TYPE_FORWARD_ONLY && c == ResultSet.CONCUR_READ_ONLY
    }

    /**
     * @see DatabaseMetaData.supportsResultSetHoldability
     */
    override fun supportsResultSetHoldability(h: Int): Boolean {
        return h == ResultSet.CLOSE_CURSORS_AT_COMMIT
    }

    /**
     * @see DatabaseMetaData.supportsResultSetType
     */
    override fun supportsResultSetType(t: Int): Boolean {
        return t == ResultSet.TYPE_FORWARD_ONLY
    }

    /**
     * @see DatabaseMetaData.supportsSavepoints
     */
    override fun supportsSavepoints(): Boolean {
        return true
    }

    /**
     * @see DatabaseMetaData.supportsSchemasInDataManipulation
     */
    override fun supportsSchemasInDataManipulation(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsSchemasInIndexDefinitions
     */
    override fun supportsSchemasInIndexDefinitions(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsSchemasInPrivilegeDefinitions
     */
    override fun supportsSchemasInPrivilegeDefinitions(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsSchemasInProcedureCalls
     */
    override fun supportsSchemasInProcedureCalls(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsSchemasInTableDefinitions
     */
    override fun supportsSchemasInTableDefinitions(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsSelectForUpdate
     */
    override fun supportsSelectForUpdate(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsStatementPooling
     */
    override fun supportsStatementPooling(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsStoredProcedures
     */
    override fun supportsStoredProcedures(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsSubqueriesInComparisons
     */
    override fun supportsSubqueriesInComparisons(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsSubqueriesInExists
     */
    override fun supportsSubqueriesInExists(): Boolean {
        return true
    } // TODO: check

    /**
     * @see DatabaseMetaData.supportsSubqueriesInIns
     */
    override fun supportsSubqueriesInIns(): Boolean {
        return true
    } // TODO: check

    /**
     * @see DatabaseMetaData.supportsSubqueriesInQuantifieds
     */
    override fun supportsSubqueriesInQuantifieds(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsTableCorrelationNames
     */
    override fun supportsTableCorrelationNames(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.supportsTransactionIsolationLevel
     */
    override fun supportsTransactionIsolationLevel(level: Int): Boolean {
        return level == Connection.TRANSACTION_SERIALIZABLE
    }

    /**
     * @see DatabaseMetaData.supportsTransactions
     */
    override fun supportsTransactions(): Boolean {
        return true
    }

    /**
     * @see DatabaseMetaData.supportsUnion
     */
    override fun supportsUnion(): Boolean {
        return true
    }

    /**
     * @see DatabaseMetaData.supportsUnionAll
     */
    override fun supportsUnionAll(): Boolean {
        return true
    }

    /**
     * @see DatabaseMetaData.updatesAreDetected
     */
    override fun updatesAreDetected(type: Int): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.usesLocalFilePerTable
     */
    override fun usesLocalFilePerTable(): Boolean {
        return false
    }

    /**
     * @see DatabaseMetaData.usesLocalFiles
     */
    override fun usesLocalFiles(): Boolean {
        return true
    }

    /**
     * @see DatabaseMetaData.isReadOnly
     */
    @Throws(SQLException::class)
    override fun isReadOnly(): Boolean {
        return conn!!.isReadOnly()
    }

    /**
     * @see DatabaseMetaData.getAttributes
     */
    @Throws(SQLException::class)
    override fun getAttributes(c: String?, s: String?, t: String?, a: String?): ResultSet {
        if (getAttributes == null) {
            getAttributes =
                conn!!.prepareStatement(
                    ("select null as TYPE_CAT, null as TYPE_SCHEM, null as TYPE_NAME, null"
                            + " as ATTR_NAME, null as DATA_TYPE, null as ATTR_TYPE_NAME, null"
                            + " as ATTR_SIZE, null as DECIMAL_DIGITS, null as NUM_PREC_RADIX,"
                            + " null as NULLABLE, null as REMARKS, null as ATTR_DEF, null as"
                            + " SQL_DATA_TYPE, null as SQL_DATETIME_SUB, null as"
                            + " CHAR_OCTET_LENGTH, null as ORDINAL_POSITION, null as"
                            + " IS_NULLABLE, null as SCOPE_CATALOG, null as SCOPE_SCHEMA, null"
                            + " as SCOPE_TABLE, null as SOURCE_DATA_TYPE limit 0;")
                )
        }

        return getAttributes!!.executeQuery()
    }

    /**
     * @see DatabaseMetaData.getBestRowIdentifier
     */
    @Throws(SQLException::class)
    override fun getBestRowIdentifier(c: String?, s: String?, t: String?, scope: Int, n: Boolean): ResultSet {
        if (getBestRowIdentifier == null) {
            getBestRowIdentifier =
                conn!!.prepareStatement(
                    ("select null as SCOPE, null as COLUMN_NAME, null as DATA_TYPE, null as"
                            + " TYPE_NAME, null as COLUMN_SIZE, null as BUFFER_LENGTH, null as"
                            + " DECIMAL_DIGITS, null as PSEUDO_COLUMN limit 0;")
                )
        }

        return getBestRowIdentifier!!.executeQuery()
    }

    /**
     * @see DatabaseMetaData.getColumnPrivileges
     */
    @Throws(SQLException::class)
    override fun getColumnPrivileges(c: String?, s: String?, t: String?, colPat: String?): ResultSet {
        if (getColumnPrivileges == null) {
            getColumnPrivileges =
                conn!!.prepareStatement(
                    ("select null as TABLE_CAT, null as TABLE_SCHEM, null as TABLE_NAME,"
                            + " null as COLUMN_NAME, null as GRANTOR, null as GRANTEE, null as"
                            + " PRIVILEGE, null as IS_GRANTABLE limit 0;")
                )
        }

        return getColumnPrivileges!!.executeQuery()
    }

    /**
     * @see DatabaseMetaData.getColumns
     */
    @Throws(SQLException::class)
    override fun getColumns(c: String?, s: String?, tblNamePattern: String?, colNamePattern: String?): ResultSet {
        // get the list of tables matching the pattern (getTables)
        // create a Matrix Cursor for each of the tables
        // create a merge cursor from all the Matrix Cursors
        // and return the columname and type from:
        //    "PRAGMA table_xinfo(tablename)"
        // which returns data like this:
        //        sqlite> PRAGMA lastyear.table_info(gross_sales);
        //        cid|name|type|notnull|dflt_value|pk
        //        0|year|INTEGER|0|'2006'|0
        //        1|month|TEXT|0||0
        //        2|monthlygross|REAL|0||0
        //        3|sortcol|INTEGER|0||0
        //        sqlite>

        // and then make the cursor have these columns
        //        TABLE_CAT String => table catalog (may be null)
        //        TABLE_SCHEM String => table schema (may be null)
        //        TABLE_NAME String => table name
        //        COLUMN_NAME String => column name
        //        DATA_TYPE int => SQL type from java.sql.Types
        //        TYPE_NAME String => Data source dependent type name, for a UDT the type name is
        // fully qualified
        //        COLUMN_SIZE int => column size.
        //        BUFFER_LENGTH is not used.
        //        DECIMAL_DIGITS int => the number of fractional digits. Null is returned for data
        // types where DECIMAL_DIGITS is not applicable.
        //        NUM_PREC_RADIX int => Radix (typically either 10 or 2)
        //        NULLABLE int => is NULL allowed.
        //        columnNoNulls - might not allow NULL values
        //        columnNullable - definitely allows NULL values
        //        columnNullableUnknown - nullability unknown
        //        REMARKS String => comment describing column (may be null)
        //        COLUMN_DEF String => default value for the column, which should be interpreted as
        // a string when the value is enclosed in single quotes (may be null)
        //        SQL_DATA_TYPE int => unused
        //        SQL_DATETIME_SUB int => unused
        //        CHAR_OCTET_LENGTH int => for char types the maximum number of bytes in the column
        //        ORDINAL_POSITION int => index of column in table (starting at 1)
        //        IS_NULLABLE String => ISO rules are used to determine the nullability for a
        // column.
        //        YES --- if the parameter can include NULLs
        //        NO --- if the parameter cannot include NULLs
        //        empty string --- if the nullability for the parameter is unknown
        //        SCOPE_CATALOG String => catalog of table that is the scope of a reference
        // attribute
        // (null if DATA_TYPE isn't REF)
        //        SCOPE_SCHEMA String => schema of table that is the scope of a reference attribute
        // (null if the DATA_TYPE isn't REF)
        //        SCOPE_TABLE String => table name that this the scope of a reference attribute
        // (null if the DATA_TYPE isn't REF)
        //        SOURCE_DATA_TYPE short => source type of a distinct type or user-generated Ref
        // type, SQL type from java.sql.Types (null if DATA_TYPE isn't DISTINCT or user-generated
        // REF)
        //        IS_AUTOINCREMENT String => Indicates whether this column is auto incremented
        //        YES --- if the column is auto incremented
        //        NO --- if the column is not auto incremented
        //        empty string --- if it cannot be determined whether the column is auto incremented
        // parameter is unknown
        //        IS_GENERATEDCOLUMN String => Indicates whether this column is auto incremented
        //        YES --- if the column is generated
        //        NO --- if the column is not generated
        //        empty string --- if it cannot be determined whether the column is auto incremented
        // parameter is unknown

        checkOpen()

        val sql = StringBuilder(700)
        sql.append("select null as TABLE_CAT, null as TABLE_SCHEM, tblname as TABLE_NAME, ")
            .append(
                "cn as COLUMN_NAME, ct as DATA_TYPE, tn as TYPE_NAME, colSize as"
                        + " COLUMN_SIZE, "
            )
            .append(
                "2000000000 as BUFFER_LENGTH, colDecimalDigits as DECIMAL_DIGITS, 10   as"
                        + " NUM_PREC_RADIX, "
            )
            .append("colnullable as NULLABLE, null as REMARKS, colDefault as COLUMN_DEF, ")
            .append(
                "0    as SQL_DATA_TYPE, 0    as SQL_DATETIME_SUB, 2000000000 as"
                        + " CHAR_OCTET_LENGTH, "
            )
            .append(
                "ordpos as ORDINAL_POSITION, (case colnullable when 0 then 'NO' when 1 then"
                        + " 'YES' else '' end)"
            )
            .append("    as IS_NULLABLE, null as SCOPE_CATALOG, null as SCOPE_SCHEMA, ")
            .append("null as SCOPE_TABLE, null as SOURCE_DATA_TYPE, ")
            .append(
                "(case colautoincrement when 0 then 'NO' when 1 then 'YES' else '' end) as"
                        + " IS_AUTOINCREMENT, "
            )
            .append(
                "(case colgenerated when 0 then 'NO' when 1 then 'YES' else '' end) as"
                        + " IS_GENERATEDCOLUMN from ("
            )

        var colFound = false

        var rs: ResultSet? = null
        try {
            // Get all tables implied by the input
            rs = getTables(c, s, tblNamePattern, null)
            while (rs.next()) {
                val tableName = rs.getString(3)

                var isAutoIncrement: Boolean

                var statColAutoinc = conn!!.createStatement()
                var rsColAutoinc: ResultSet? = null
                try {
                    statColAutoinc = conn!!.createStatement()
                    rsColAutoinc =
                        statColAutoinc.executeQuery(
                            ("SELECT LIKE('%autoincrement%', LOWER(sql)) FROM sqlite_schema "
                                    + "WHERE LOWER(name) = LOWER('"
                                    + escape(tableName)
                                    + "') AND TYPE IN ('table', 'view')")
                        )
                    rsColAutoinc.next()
                    isAutoIncrement = rsColAutoinc.getInt(1) == 1
                } finally {
                    if (rsColAutoinc != null) {
                        try {
                            rsColAutoinc.close()
                        } catch (e: Exception) {
                            LogHolder.logger.error(Supplier { "Could not close ResultSet" }, e)
                        }
                    }
                    if (statColAutoinc != null) {
                        try {
                            statColAutoinc.close()
                        } catch (e: Exception) {
                            LogHolder.logger.error(Supplier { "Could not close statement" }, e)
                        }
                    }
                }

                // For each table, get the column info and build into overall SQL
                val pragmaStatement = "PRAGMA table_xinfo('" + escape(tableName) + "')"
                conn!!.createStatement().use { colstat ->
                    colstat.executeQuery(pragmaStatement).use { rscol ->
                        var i = 0
                        while (rscol.next()) {
                            val colName = rscol.getString(2)
                            var colType = rscol.getString(3)
                            val colNotNull = rscol.getString(4)
                            val colDefault = rscol.getString(5)
                            val isPk = "1" == rscol.getString(6)
                            val colHidden = rscol.getString(7)

                            var colNullable = 2
                            if (colNotNull != null) {
                                colNullable = if (colNotNull == "0") 1 else 0
                            }

                            if (colFound) {
                                sql.append(" union all ")
                            }
                            colFound = true

                            // default values
                            var iColumnSize = 2000000000
                            var iDecimalDigits = 10

                            /*
                         * improved column types
                         * ref https://www.sqlite.org/datatype3.html - 2.1 Determination Of Column Affinity
                         * plus some degree of artistic-license applied
                         */
                            colType = if (colType == null) "TEXT" else colType.uppercase(Locale.getDefault())

                            var colAutoIncrement = 0
                            if (isPk && isAutoIncrement) {
                                colAutoIncrement = 1
                            }
                            val colJavaType: Int
                            // rule #1 + boolean
                            if (TYPE_INTEGER.matcher(colType).find()) {
                                colJavaType = Types.INTEGER
                                // there are no decimal digits
                                iDecimalDigits = 0
                            } else if (TYPE_VARCHAR.matcher(colType).find()) {
                                colJavaType = Types.VARCHAR
                                // there are no decimal digits
                                iDecimalDigits = 0
                            } else if (TYPE_FLOAT.matcher(colType).find()) {
                                colJavaType = Types.FLOAT
                            } else {
                                // catch-all
                                colJavaType = Types.VARCHAR
                            }
                            // try to find an (optional) length/dimension of the column
                            val iStartOfDimension = colType.indexOf('(')
                            if (iStartOfDimension > 0) {
                                // find end of dimension
                                val iEndOfDimension = colType.indexOf(')', iStartOfDimension)
                                if (iEndOfDimension > 0) {
                                    val sInteger: String?
                                    val sDecimal: String?
                                    // check for two values (integer part, fraction) divided by
                                    // comma
                                    val iDimensionSeparator = colType.indexOf(',', iStartOfDimension)
                                    if (iDimensionSeparator > 0) {
                                        sInteger =
                                            colType.substring(
                                                iStartOfDimension + 1, iDimensionSeparator
                                            )
                                        sDecimal =
                                            colType.substring(
                                                iDimensionSeparator + 1, iEndOfDimension
                                            )
                                    } else {
                                        sInteger =
                                            colType.substring(
                                                iStartOfDimension + 1, iEndOfDimension
                                            )
                                        sDecimal = null
                                    }
                                    // try to parse the values
                                    try {
                                        val iInteger = Integer.parseUnsignedInt(sInteger.trim { it <= ' ' })
                                        // parse decimals?
                                        if (sDecimal != null) {
                                            iDecimalDigits = Integer.parseUnsignedInt(sDecimal.trim { it <= ' ' })
                                            // columns size equals sum of integer and decimal part
                                            // of dimension
                                            iColumnSize = iInteger + iDecimalDigits
                                        } else {
                                            // no decimals
                                            iDecimalDigits = 0
                                            // columns size equals dimension
                                            iColumnSize = iInteger
                                        }
                                    } catch (ex: NumberFormatException) {
                                        // just ignore invalid dimension formats here
                                    }
                                }
                                // "TYPE_NAME" (colType) is without the length/ dimension
                                colType = colType.substring(0, iStartOfDimension).trim { it <= ' ' }
                            }

                            var colGenerated = 0
                            if ("2" == colHidden || "3" == colHidden) {
                                colGenerated = 1
                            }

                            sql.append("select ")
                                .append(i + 1)
                                .append(" as ordpos, ")
                                .append(colNullable)
                                .append(" as colnullable,")
                                .append(colJavaType)
                                .append(" as ct, ")
                                .append(iColumnSize)
                                .append(" as colSize, ")
                                .append(iDecimalDigits)
                                .append(" as colDecimalDigits, ")
                                .append("'")
                                .append(tableName)
                                .append("' as tblname, ")
                                .append("'")
                                .append(escape(colName))
                                .append("' as cn, ")
                                .append("'")
                                .append(escape(colType))
                                .append("' as tn, ")
                                .append(quote(if (colDefault == null) null else escape(colDefault)))
                                .append(" as colDefault,")
                                .append(colAutoIncrement)
                                .append(" as colautoincrement,")
                                .append(colGenerated)
                                .append(" as colgenerated")

                            if (colNamePattern != null) {
                                sql.append(" where upper(cn) like upper('")
                                    .append(escape(colNamePattern))
                                    .append("') ESCAPE '")
                                    .append(getSearchStringEscape())
                                    .append("'")
                            }
                            i++
                        }
                    }
                }
            }
        } finally {
            if (rs != null) {
                try {
                    rs.close()
                } catch (e: Exception) {
                    LogHolder.logger.error(Supplier { "Could not close ResultSet" }, e)
                }
            }
        }

        if (colFound) {
            sql.append(") order by TABLE_SCHEM, TABLE_NAME, ORDINAL_POSITION;")
        } else {
            sql.append(
                ("select null as ordpos, null as colnullable, null as ct, null as colsize, null"
                        + " as colDecimalDigits, null as tblname, null as cn, null as tn, null as"
                        + " colDefault, null as colautoincrement, null as colgenerated) limit 0;")
            )
        }

        val stat = conn!!.createStatement()
        return (stat as CoreStatement).executeQuery(sql.toString(), true)!!
    }

    /**
     * @see DatabaseMetaData.getCrossReference
     */
    @Throws(SQLException::class)
    override fun getCrossReference(
        pc: String?, ps: String?, pt: String?, fc: String?, fs: String?, ft: String?
    ): ResultSet {
        if (pt == null) {
            return getExportedKeys(fc, fs, ft)
        }

        if (ft == null) {
            return getImportedKeys(pc, ps, pt)
        }

        val query =
            ("select "
                    + quote(pc)
                    + " as PKTABLE_CAT, "
                    + quote(ps)
                    + " as PKTABLE_SCHEM, "
                    + quote(pt)
                    + " as PKTABLE_NAME, "
                    + "'' as PKCOLUMN_NAME, "
                    + quote(fc)
                    + " as FKTABLE_CAT, "
                    + quote(fs)
                    + " as FKTABLE_SCHEM, "
                    + quote(ft)
                    + " as FKTABLE_NAME, '' as FKCOLUMN_NAME, -1 as KEY_SEQ, 3 as UPDATE_RULE,"
                    + " 3 as DELETE_RULE, '' as FK_NAME, '' as PK_NAME, "
                    + DatabaseMetaData.importedKeyInitiallyDeferred
                    + " as DEFERRABILITY limit 0 ")

        return (conn!!.createStatement() as CoreStatement).executeQuery(query, true)!!
    }

    /**
     * @see DatabaseMetaData.getSchemas
     */
    @Throws(SQLException::class)
    override fun getSchemas(): ResultSet {
        if (getSchemas == null) {
            getSchemas =
                conn!!.prepareStatement(
                    "select null as TABLE_SCHEM, null as TABLE_CATALOG limit 0;"
                )
        }

        return getSchemas!!.executeQuery()
    }

    /**
     * @see DatabaseMetaData.getCatalogs
     */
    @Throws(SQLException::class)
    override fun getCatalogs(): ResultSet {
        if (getCatalogs == null) {
            getCatalogs = conn!!.prepareStatement("select null as TABLE_CAT limit 0;")
        }

        return getCatalogs!!.executeQuery()
    }

    /**
     * @see DatabaseMetaData.getPrimaryKeys
     */
    @Throws(SQLException::class)
    override fun getPrimaryKeys(c: String?, s: String?, table: String?): ResultSet {
        val pkFinder = PrimaryKeyFinder(table)
        val columns = pkFinder.columns

        val stat = conn!!.createStatement()
        val sql = StringBuilder(512)
        sql.append("select null as TABLE_CAT, null as TABLE_SCHEM, '")
            .append(escape(table!!))
            .append("' as TABLE_NAME, cn as COLUMN_NAME, ks as KEY_SEQ, pk as PK_NAME from (")

        if (columns == null) {
            sql.append("select null as cn, null as pk, 0 as ks) limit 0;")

            return (stat as CoreStatement).executeQuery(sql.toString(), true)!!
        }

        var pkName = pkFinder.name
        if (pkName != null) {
            pkName = "'" + pkName + "'"
        }

        for (i in columns.indices) {
            if (i > 0) sql.append(" union ")
            sql.append("select ")
                .append(pkName)
                .append(" as pk, '")
                .append(escape(unquoteIdentifier(columns[i])!!))
                .append("' as cn, ")
                .append(i + 1)
                .append(" as ks")
        }

        return (stat as CoreStatement).executeQuery(sql.append(") order by cn;").toString(), true)!!
    }

    /**
     * @see DatabaseMetaData.getExportedKeys
     */
    @Throws(SQLException::class)
    override fun getExportedKeys(catalog: String?, schema: String?, table: String?): ResultSet {
        var catalog = catalog
        var schema = schema
        val pkFinder = PrimaryKeyFinder(table)
        val pkColumns = pkFinder.columns
        val stat = conn!!.createStatement()

        catalog = if (catalog != null) quote(catalog) else null
        schema = if (schema != null) quote(schema) else null

        val exportedKeysQuery = StringBuilder(512)

        var target: String? = null
        var count = 0
        if (pkColumns != null) {
            // retrieve table list
            val tableList: ArrayList<String>?
            stat.executeQuery("select name from sqlite_schema where type = 'table'").use { rs ->
                tableList = ArrayList<String>()
                while (rs.next()) {
                    val tblname = rs.getString(1)
                    tableList.add(tblname)
                    if (tblname.equals(table, ignoreCase = true)) {
                        // get the correct case as in the database
                        // (not uppercase nor lowercase)
                        target = tblname
                    }
                }
            }
            // find imported keys for each table
            for (tbl in tableList!!) {
                val impFkFinder = ImportedKeyFinder(tbl)
                val fkNames =
                    impFkFinder.fkList

                for (foreignKey in fkNames) {
                    val PKTabName = foreignKey.pkTableName

                    if (PKTabName == null || !PKTabName.equals(target, ignoreCase = true)) {
                        continue
                    }

                    for (j in 0..<foreignKey.columnMappingCount) {
                        val keySeq = j + 1
                        val columnMapping = foreignKey.getColumnMapping(j)
                        var PKColName: String = columnMapping[1]!!
                        PKColName = if (PKColName == null) "" else PKColName
                        var FKColName: String = columnMapping[0]!!
                        FKColName = if (FKColName == null) "" else FKColName

                        var usePkName = false
                        for (pkColumn in pkColumns) {
                            if (pkColumn != null && pkColumn.equals(PKColName, ignoreCase = true)) {
                                usePkName = true
                                break
                            }
                        }
                        val pkName: String =
                            (if (usePkName && pkFinder.name != null) pkFinder.name else "")!!

                        exportedKeysQuery
                            .append(if (count > 0) " union all select " else "select ")
                            .append(keySeq)
                            .append(" as ks, '")
                            .append(escape(tbl))
                            .append("' as fkt, '")
                            .append(escape(FKColName))
                            .append("' as fcn, '")
                            .append(escape(PKColName))
                            .append("' as pcn, '")
                            .append(escape(pkName))
                            .append("' as pkn, ")
                            .append(RULE_MAP.get(foreignKey.onUpdate))
                            .append(" as ur, ")
                            .append(RULE_MAP.get(foreignKey.onDelete))
                            .append(" as dr, ")

                        val fkName = foreignKey.fkName

                        if (fkName != null) {
                            exportedKeysQuery.append("'").append(escape(fkName)).append("' as fkn")
                        } else {
                            exportedKeysQuery.append("'' as fkn")
                        }

                        count++
                    }
                }
            }
        }

        val hasImportedKey = (count > 0)
        val sql = StringBuilder(512)
        sql.append("select ")
            .append(catalog)
            .append(" as PKTABLE_CAT, ")
            .append(schema)
            .append(" as PKTABLE_SCHEM, ")
            .append(quote(target))
            .append(" as PKTABLE_NAME, ")
            .append(if (hasImportedKey) "pcn" else "''")
            .append(" as PKCOLUMN_NAME, ")
            .append(catalog)
            .append(" as FKTABLE_CAT, ")
            .append(schema)
            .append(" as FKTABLE_SCHEM, ")
            .append(if (hasImportedKey) "fkt" else "''")
            .append(" as FKTABLE_NAME, ")
            .append(if (hasImportedKey) "fcn" else "''")
            .append(" as FKCOLUMN_NAME, ")
            .append(if (hasImportedKey) "ks" else "-1")
            .append(" as KEY_SEQ, ")
            .append(if (hasImportedKey) "ur" else "3")
            .append(" as UPDATE_RULE, ")
            .append(if (hasImportedKey) "dr" else "3")
            .append(" as DELETE_RULE, ")
            .append(if (hasImportedKey) "fkn" else "''")
            .append(" as FK_NAME, ")
            .append(if (hasImportedKey) "pkn" else "''")
            .append(" as PK_NAME, ")
            .append(DatabaseMetaData.importedKeyInitiallyDeferred) // FIXME: Check for pragma
            // foreign_keys = true ?
            .append(" as DEFERRABILITY ")

        if (hasImportedKey) {
            sql.append("from (")
                .append(exportedKeysQuery)
                .append(") ORDER BY FKTABLE_CAT, FKTABLE_SCHEM, FKTABLE_NAME, KEY_SEQ")
        } else {
            sql.append("limit 0")
        }

        return (stat as CoreStatement).executeQuery(sql.toString(), true)!!
    }

    private fun appendDummyForeignKeyList(sql: StringBuilder): StringBuilder {
        sql.append("select -1 as ks, '' as ptn, '' as fcn, '' as pcn, ")
            .append(DatabaseMetaData.importedKeyNoAction)
            .append(" as ur, ")
            .append(DatabaseMetaData.importedKeyNoAction)
            .append(" as dr, ")
            .append(" '' as fkn, ")
            .append(" '' as pkn ")
            .append(") limit 0;")
        return sql
    }

    /**
     * @see DatabaseMetaData.getImportedKeys
     */
    @Throws(SQLException::class)
    override fun getImportedKeys(catalog: String?, schema: String?, table: String?): ResultSet {
        val rs: ResultSet
        val stat = conn!!.createStatement()
        var sql = StringBuilder(700)

        sql.append("select ")
            .append(quote(catalog))
            .append(" as PKTABLE_CAT, ")
            .append(quote(schema))
            .append(" as PKTABLE_SCHEM, ")
            .append("ptn as PKTABLE_NAME, pcn as PKCOLUMN_NAME, ")
            .append(quote(catalog))
            .append(" as FKTABLE_CAT, ")
            .append(quote(schema))
            .append(" as FKTABLE_SCHEM, ")
            .append(quote(table))
            .append(" as FKTABLE_NAME, ")
            .append(
                "fcn as FKCOLUMN_NAME, ks as KEY_SEQ, ur as UPDATE_RULE, dr as DELETE_RULE,"
                        + " fkn as FK_NAME, pkn as PK_NAME, "
            )
            .append(DatabaseMetaData.importedKeyInitiallyDeferred)
            .append(" as DEFERRABILITY from (")

        // Use a try catch block to avoid "query does not return ResultSet" error
        try {
            rs = stat.executeQuery("pragma foreign_key_list('" + escape(table!!) + "');")
        } catch (e: SQLException) {
            sql = appendDummyForeignKeyList(sql)
            return (stat as CoreStatement).executeQuery(sql.toString(), true)!!
        }

        val impFkFinder = ImportedKeyFinder(table)
        val fkNames =
            impFkFinder.fkList

        var i = 0
        while (rs.next()) {
            val keySeq = rs.getInt(2) + 1
            val keyId = rs.getInt(1)
            val PKTabName = rs.getString(3)
            val FKColName = rs.getString(4)
            var PKColName = rs.getString(5)

            var pkName: String? = null
            try {
                val pkFinder = PrimaryKeyFinder(PKTabName)
                pkName = pkFinder.name
                if (PKColName == null) {
                    PKColName = pkFinder.columns!![0]
                }
            } catch (ignored: SQLException) {
            }

            val updateRule = rs.getString(6)
            val deleteRule = rs.getString(7)

            if (i > 0) {
                sql.append(" union all ")
            }

            var fkName: String? = null
            if (fkNames.size > keyId) fkName = fkNames.get(keyId).fkName

            sql.append("select ")
                .append(keySeq)
                .append(" as ks,")
                .append("'")
                .append(escape(PKTabName))
                .append("' as ptn, '")
                .append(escape(FKColName))
                .append("' as fcn, '")
                .append(escape(PKColName!!))
                .append("' as pcn,")
                .append("case '")
                .append(escape(updateRule))
                .append("'")
                .append(" when 'NO ACTION' then ")
                .append(DatabaseMetaData.importedKeyNoAction)
                .append(" when 'CASCADE' then ")
                .append(DatabaseMetaData.importedKeyCascade)
                .append(" when 'RESTRICT' then ")
                .append(DatabaseMetaData.importedKeyRestrict)
                .append(" when 'SET NULL' then ")
                .append(DatabaseMetaData.importedKeySetNull)
                .append(" when 'SET DEFAULT' then ")
                .append(DatabaseMetaData.importedKeySetDefault)
                .append(" end as ur, ")
                .append("case '")
                .append(escape(deleteRule))
                .append("'")
                .append(" when 'NO ACTION' then ")
                .append(DatabaseMetaData.importedKeyNoAction)
                .append(" when 'CASCADE' then ")
                .append(DatabaseMetaData.importedKeyCascade)
                .append(" when 'RESTRICT' then ")
                .append(DatabaseMetaData.importedKeyRestrict)
                .append(" when 'SET NULL' then ")
                .append(DatabaseMetaData.importedKeySetNull)
                .append(" when 'SET DEFAULT' then ")
                .append(DatabaseMetaData.importedKeySetDefault)
                .append(" end as dr, ")
                .append(if (fkName == null) "''" else quote(fkName))
                .append(" as fkn, ")
                .append(if (pkName == null) "''" else quote(pkName))
                .append(" as pkn")
            i++
        }
        rs.close()

        if (i == 0) {
            sql = appendDummyForeignKeyList(sql)
        } else {
            sql.append(") ORDER BY PKTABLE_CAT, PKTABLE_SCHEM, PKTABLE_NAME, KEY_SEQ;")
        }

        return (stat as CoreStatement).executeQuery(sql.toString(), true)!!
    }

    /**
     * @see DatabaseMetaData.getIndexInfo
     */
    @Throws(SQLException::class)
    override fun getIndexInfo(c: String?, s: String?, table: String?, u: Boolean, approximate: Boolean): ResultSet {
        var rs: ResultSet
        val stat = conn!!.createStatement()
        val sql = StringBuilder(500)

        // define the column header
        // this is from the JDBC spec, it is part of the driver protocol
        sql.append("select null as TABLE_CAT, null as TABLE_SCHEM, '")
            .append(escape(table!!))
            .append(
                "' as TABLE_NAME, un as NON_UNIQUE, null as INDEX_QUALIFIER, n as"
                        + " INDEX_NAME, "
            )
            .append(DatabaseMetaData.tableIndexOther.toInt().toString())
            .append(" as TYPE, op as ORDINAL_POSITION, ")
            .append(
                "cn as COLUMN_NAME, null as ASC_OR_DESC, 0 as CARDINALITY, 0 as PAGES, null"
                        + " as FILTER_CONDITION from ("
            )

        // this always returns a result set now, previously threw exception
        rs = stat.executeQuery("pragma index_list('" + escape(table) + "');")

        val indexList = ArrayList<ArrayList<Any>>()
        while (rs.next()) {
            indexList.add(ArrayList<Any>())
            indexList.get(indexList.size - 1).add(rs.getString(2))
            indexList.get(indexList.size - 1).add(rs.getInt(3))
        }
        rs.close()
        if (indexList.size == 0) {
            // if pragma index_list() returns no information, use this null block
            sql.append("select null as un, null as n, null as op, null as cn) limit 0;")
            return (stat as CoreStatement).executeQuery(sql.toString(), true)!!
        } else {
            // loop over results from pragma call, getting specific info for each index

            val indexIterator = indexList.iterator()
            var currentIndex: ArrayList<Any>?

            val unionAll = ArrayList<String>()

            while (indexIterator.hasNext()) {
                currentIndex = indexIterator.next()
                val indexName = currentIndex.get(0).toString()
                rs = stat.executeQuery("pragma index_info('" + escape(indexName) + "');")

                while (rs.next()) {
                    val sqlRow = StringBuilder()

                    val colName = rs.getString(3)
                    sqlRow.append("select ")
                        .append(1 - currentIndex[1] as Int)
                        .append(" as un,'")
                        .append(escape(indexName))
                        .append("' as n,")
                        .append(rs.getInt(1) + 1)
                        .append(" as op,")
                    if (colName == null) { // expression index
                        sqlRow.append("null")
                    } else {
                        sqlRow.append("'").append(escape(colName)).append("'")
                    }
                    sqlRow.append(" as cn")

                    unionAll.add(sqlRow.toString())
                }

                rs.close()
            }

            val sqlBlock = join(unionAll, " union all ")

            return (stat as CoreStatement)
                .executeQuery(sql.append(sqlBlock).append(");").toString(), true)!!
        }
    }

    /**
     * @see DatabaseMetaData.getProcedureColumns
     */
    @Throws(SQLException::class)
    override fun getProcedureColumns(c: String?, s: String?, p: String?, colPat: String?): ResultSet {
        if (getProcedureColumns == null) {
            getProcedureColumns =
                conn!!.prepareStatement(
                    ("select null as PROCEDURE_CAT, null as PROCEDURE_SCHEM, null as"
                            + " PROCEDURE_NAME, null as COLUMN_NAME, null as COLUMN_TYPE, null"
                            + " as DATA_TYPE, null as TYPE_NAME, null as PRECISION, null as"
                            + " LENGTH, null as SCALE, null as RADIX, null as NULLABLE, null as"
                            + " REMARKS limit 0;")
                )
        }
        return getProcedureColumns!!.executeQuery()
    }

    /**
     * @see DatabaseMetaData.getProcedures
     */
    @Throws(SQLException::class)
    override fun getProcedures(c: String?, s: String?, p: String?): ResultSet {
        if (getProcedures == null) {
            getProcedures =
                conn!!.prepareStatement(
                    ("select null as PROCEDURE_CAT, null as PROCEDURE_SCHEM, null as"
                            + " PROCEDURE_NAME, null as UNDEF1, null as UNDEF2, null as UNDEF3,"
                            + " null as REMARKS, null as PROCEDURE_TYPE limit 0;")
                )
        }
        return getProcedures!!.executeQuery()
    }

    /**
     * @see DatabaseMetaData.getSuperTables
     */
    @Throws(SQLException::class)
    override fun getSuperTables(c: String?, s: String?, t: String?): ResultSet {
        if (getSuperTables == null) {
            getSuperTables =
                conn!!.prepareStatement(
                    "select null as TABLE_CAT, null as TABLE_SCHEM, "
                            + "null as TABLE_NAME, null as SUPERTABLE_NAME limit 0;"
                )
        }
        return getSuperTables!!.executeQuery()
    }

    /**
     * @see DatabaseMetaData.getSuperTypes
     */
    @Throws(SQLException::class)
    override fun getSuperTypes(c: String?, s: String?, t: String?): ResultSet {
        if (getSuperTypes == null) {
            getSuperTypes =
                conn!!.prepareStatement(
                    ("select null as TYPE_CAT, null as TYPE_SCHEM, null as TYPE_NAME, null"
                            + " as SUPERTYPE_CAT, null as SUPERTYPE_SCHEM, null as"
                            + " SUPERTYPE_NAME limit 0;")
                )
        }
        return getSuperTypes!!.executeQuery()
    }

    /**
     * @see DatabaseMetaData.getTablePrivileges
     */
    @Throws(SQLException::class)
    override fun getTablePrivileges(c: String?, s: String?, t: String?): ResultSet {
        if (getTablePrivileges == null) {
            getTablePrivileges =
                conn!!.prepareStatement(
                    ("select  null as TABLE_CAT, null as TABLE_SCHEM, null as TABLE_NAME,"
                            + " null as GRANTOR, null GRANTEE,  null as PRIVILEGE, null as"
                            + " IS_GRANTABLE limit 0;")
                )
        }
        return getTablePrivileges!!.executeQuery()
    }

    /**
     * @see DatabaseMetaData.getTables
     */
    @Synchronized
    @Throws(SQLException::class)
    override fun getTables(
        c: String?, s: String?, tblNamePattern: String?, types: Array<String>?
    ): ResultSet {
        var tblNamePattern = tblNamePattern
        checkOpen()

        tblNamePattern =
            if (tblNamePattern == null || "" == tblNamePattern)
                "%"
            else
                escape(tblNamePattern)

        val sql = StringBuilder()
        sql.append("SELECT").append("\n")
        sql.append("  NULL AS TABLE_CAT,").append("\n")
        sql.append("  NULL AS TABLE_SCHEM,").append("\n")
        sql.append("  NAME AS TABLE_NAME,").append("\n")
        sql.append("  TYPE AS TABLE_TYPE,").append("\n")
        sql.append("  NULL AS REMARKS,").append("\n")
        sql.append("  NULL AS TYPE_CAT,").append("\n")
        sql.append("  NULL AS TYPE_SCHEM,").append("\n")
        sql.append("  NULL AS TYPE_NAME,").append("\n")
        sql.append("  NULL AS SELF_REFERENCING_COL_NAME,").append("\n")
        sql.append("  NULL AS REF_GENERATION").append("\n")
        sql.append("FROM").append("\n")
        sql.append("  (").append("\n")
        sql.append("    SELECT\n")
        sql.append("      'sqlite_schema' AS NAME,\n")
        sql.append("      'SYSTEM TABLE' AS TYPE")
        sql.append("    UNION ALL").append("\n")
        sql.append("    SELECT").append("\n")
        sql.append("      NAME,").append("\n")
        sql.append("      UPPER(TYPE) AS TYPE").append("\n")
        sql.append("    FROM").append("\n")
        sql.append("      sqlite_schema").append("\n")
        sql.append("    WHERE").append("\n")
        sql.append("      NAME NOT LIKE 'sqlite\\_%' ESCAPE '\\'").append("\n")
        sql.append("      AND UPPER(TYPE) IN ('TABLE', 'VIEW')").append("\n")
        sql.append("    UNION ALL").append("\n")
        sql.append("    SELECT").append("\n")
        sql.append("      NAME,").append("\n")
        sql.append("      'GLOBAL TEMPORARY' AS TYPE").append("\n")
        sql.append("    FROM").append("\n")
        sql.append("      sqlite_temp_master").append("\n")
        sql.append("    UNION ALL").append("\n")
        sql.append("    SELECT").append("\n")
        sql.append("      NAME,").append("\n")
        sql.append("      'SYSTEM TABLE' AS TYPE").append("\n")
        sql.append("    FROM").append("\n")
        sql.append("      sqlite_schema").append("\n")
        sql.append("    WHERE").append("\n")
        sql.append("      NAME LIKE 'sqlite\\_%' ESCAPE '\\'").append("\n")
        sql.append("  )").append("\n")
        sql.append(" WHERE TABLE_NAME LIKE '")
        sql.append(tblNamePattern)
        sql.append("' ESCAPE '")
        sql.append(getSearchStringEscape())
        sql.append("'")

        if (types != null && types.size != 0) {
            sql.append(" AND TABLE_TYPE IN (")
            sql.append(
                Arrays.stream<String>(types)
                    .map<String> { t: String? -> "'" + t!!.uppercase(Locale.getDefault()) + "'" }
                    .collect(Collectors.joining(",")))
            sql.append(")")
        }

        sql.append(" ORDER BY TABLE_TYPE, TABLE_NAME;")

        return (conn!!.createStatement() as CoreStatement).executeQuery(sql.toString(), true)!!
    }

    /**
     * @see DatabaseMetaData.getTableTypes
     */
    @Throws(SQLException::class)
    override fun getTableTypes(): ResultSet {
        checkOpen()

        val sql =
            ("SELECT 'TABLE' AS TABLE_TYPE "
                    + "UNION "
                    + "SELECT 'VIEW' AS TABLE_TYPE "
                    + "UNION "
                    + "SELECT 'SYSTEM TABLE' AS TABLE_TYPE "
                    + "UNION "
                    + "SELECT 'GLOBAL TEMPORARY' AS TABLE_TYPE;")

        if (getTableTypes == null) {
            getTableTypes = conn!!.prepareStatement(sql)
        }
        getTableTypes!!.clearParameters()
        return getTableTypes!!.executeQuery()
    }

    /**
     * @see DatabaseMetaData.getTypeInfo
     */
    @Throws(SQLException::class)
    override fun getTypeInfo(): ResultSet {
        if (getTypeInfo == null) {
            val sql =
                (valuesQuery(
                    mutableListOf<String>(
                        "TYPE_NAME",
                        "DATA_TYPE",
                        "PRECISION",
                        "LITERAL_PREFIX",
                        "LITERAL_SUFFIX",
                        "CREATE_PARAMS",
                        "NULLABLE",
                        "CASE_SENSITIVE",
                        "SEARCHABLE",
                        "UNSIGNED_ATTRIBUTE",
                        "FIXED_PREC_SCALE",
                        "AUTO_INCREMENT",
                        "LOCAL_TYPE_NAME",
                        "MINIMUM_SCALE",
                        "MAXIMUM_SCALE",
                        "SQL_DATA_TYPE",
                        "SQL_DATETIME_SUB",
                        "NUM_PREC_RADIX"
                    ),
                    Arrays.asList<MutableList<Any>>(
                        Arrays.asList<Any>(
                            "BLOB",
                            Types.BLOB,
                            0,
                            null,
                            null,
                            null,
                            DatabaseMetaData.typeNullable,
                            0,
                            DatabaseMetaData.typeSearchable,
                            1,
                            0,
                            0,
                            null,
                            0,
                            0,
                            0,
                            0,
                            10
                        ),
                        Arrays.asList<Any>(
                            "INTEGER",
                            Types.INTEGER,
                            0,
                            null,
                            null,
                            null,
                            DatabaseMetaData.typeNullable,
                            0,
                            DatabaseMetaData.typeSearchable,
                            0,
                            0,
                            1,
                            null,
                            0,
                            0,
                            0,
                            0,
                            10
                        ),
                        Arrays.asList<Any>(
                            "NULL",
                            Types.NULL,
                            0,
                            null,
                            null,
                            null,
                            DatabaseMetaData.typeNullable,
                            0,
                            DatabaseMetaData.typeSearchable,
                            1,
                            0,
                            0,
                            null,
                            0,
                            0,
                            0,
                            0,
                            10
                        ),
                        Arrays.asList<Any>(
                            "REAL",
                            Types.REAL,
                            0,
                            null,
                            null,
                            null,
                            DatabaseMetaData.typeNullable,
                            0,
                            DatabaseMetaData.typeSearchable,
                            0,
                            0,
                            0,
                            null,
                            0,
                            0,
                            0,
                            0,
                            10
                        ),
                        Arrays.asList<Any>(
                            "TEXT",
                            Types.VARCHAR,
                            0,
                            null,
                            null,
                            null,
                            DatabaseMetaData.typeNullable,
                            1,
                            DatabaseMetaData.typeSearchable,
                            1,
                            0,
                            0,
                            null,
                            0,
                            0,
                            0,
                            0,
                            10
                        )
                    )
                )
                        + " order by DATA_TYPE")
            getTypeInfo = conn!!.prepareStatement(sql)
        }

        getTypeInfo!!.clearParameters()
        return getTypeInfo!!.executeQuery()
    }

    /**
     * @see DatabaseMetaData.getUDTs
     */
    @Throws(SQLException::class)
    override fun getUDTs(c: String?, s: String?, t: String?, types: IntArray?): ResultSet {
        if (getUDTs == null) {
            getUDTs =
                conn!!.prepareStatement(
                    ("select  null as TYPE_CAT, null as TYPE_SCHEM, null as TYPE_NAME,  null"
                            + " as CLASS_NAME,  null as DATA_TYPE, null as REMARKS, null as"
                            + " BASE_TYPE limit 0;")
                )
        }

        getUDTs!!.clearParameters()
        return getUDTs!!.executeQuery()
    }

    /**
     * @see DatabaseMetaData.getVersionColumns
     */
    @Throws(SQLException::class)
    override fun getVersionColumns(c: String?, s: String?, t: String?): ResultSet {
        if (getVersionColumns == null) {
            getVersionColumns =
                conn!!.prepareStatement(
                    ("select null as SCOPE, null as COLUMN_NAME, null as DATA_TYPE, null as"
                            + " TYPE_NAME, null as COLUMN_SIZE, null as BUFFER_LENGTH, null as"
                            + " DECIMAL_DIGITS, null as PSEUDO_COLUMN limit 0;")
                )
        }
        return getVersionColumns!!.executeQuery()
    }

    @get:Throws(SQLException::class)
    @get:Deprecated(
        """Not exactly sure what this function does, as it is not implementing any
          interface, and is not used anywhere in the code. Deprecated since 3.43.0.0."""
    )
    override val generatedKeys: ResultSet?
        get() {
            throw SQLFeatureNotSupportedException("not implemented by SQLite JDBC driver")
        }

    /** Not implemented yet.  */
    @Throws(SQLException::class)
    fun createStruct(t: String, attr: Array<Any>): Struct? {
        throw SQLFeatureNotSupportedException("Not yet implemented by SQLite JDBC driver")
    }

    /** Not implemented yet.  */
    @Throws(SQLException::class)
    override fun getFunctionColumns(a: String?, b: String?, c: String?, d: String?): ResultSet? {
        throw SQLFeatureNotSupportedException("Not yet implemented by SQLite JDBC driver")
    }

    /** Parses the sqlite_schema table for a table's primary key  */
    internal inner class PrimaryKeyFinder(
        /** The table name.  */
        var table: String?
    ) {
        /**
         * @return The primary key name if any.
         */
        /** The primary key name.  */
        var name: String? = null

        /**
         * @return Array of primary key column(s) if any.
         */
        /** The column(s) for the primary key.  */
        var columns: Array<String>? = null

        /**
         * Constructor.
         * 
         * @param table The table for which to get find a primary key.
         * @throws SQLException
         */
        init {
            populate()
        }

        @Throws(SQLException::class)
        private fun populate() {
            // specific handling for sqlite_schema and synonyms, so that
            // getExportedKeys/getPrimaryKeys return an empty ResultSet instead of throwing an
            // exception
            if ("sqlite_schema" == table || "sqlite_master" == table) return

            if (table == null || table!!.trim { it <= ' ' }.length == 0) {
                throw SQLException("Invalid table name: '" + this.table + "'")
            }

            conn!!.createStatement().use { stat ->
                stat.executeQuery(
                    ("select sql from sqlite_schema where"
                            + " lower(name) = lower('"
                            + escape(table!!)
                            + "') and type in ('table', 'view')")
                ).use { rs ->
                    if (!rs.next()) throw SQLException("Table not found: '" + table + "'")
                    var matcher: Matcher = PK_NAMED_PATTERN.matcher(rs.getString(1))
                    if (matcher.find()) {
                        this.name = unquoteIdentifier(escape(matcher.group(1)))
                        this.columns =
                            matcher.group(2).split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                    } else {
                        matcher = PK_UNNAMED_PATTERN.matcher(rs.getString(1))
                        if (matcher.find()) {
                            this.columns =
                                matcher.group(1).split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                        }
                    }
                    var localColumns = this.columns
                    if (localColumns == null) {
                        stat.executeQuery("pragma table_info('" + escape(table!!) + "');").use { rs2 ->
                            while (rs2.next()) {
                                if (rs2.getBoolean(6)) this.columns = arrayOf(rs2.getString(2))
                            }
                        }
                    }
                    localColumns = this.columns
                    if (localColumns != null) {
                        for (i in localColumns.indices) {
                            localColumns[i] = unquoteIdentifier(localColumns!![i])!!
                        }
                    }
                }
            }
        }
    }

    internal inner class ImportedKeyFinder(table: String?) {
        /** Pattern used to extract a named primary key.  */
        private val FK_NAMED_PATTERN: Pattern = Pattern.compile(
            "CONSTRAINT\\s*\"?([A-Za-z_][A-Za-z\\d_]*)?\"?\\s*FOREIGN\\s+KEY\\s*\\((.*?)\\)",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )

        val fkTableName: String
        val fkList: MutableList<ForeignKey> = ArrayList<ForeignKey>()

        init {
            if (table == null || table.trim { it <= ' ' }.length == 0) {
                throw SQLException("Invalid table name: '" + table + "'")
            }

            this.fkTableName = table

            val fkNames = getForeignKeyNames(this.fkTableName)

            conn!!.createStatement().use { stat ->
                stat.executeQuery(
                    ("pragma foreign_key_list('"
                            + escape(this.fkTableName.lowercase(Locale.getDefault()))
                            + "')")
                ).use { rs ->
                    var prevFkId = -1
                    var count = 0
                    var fk: ForeignKey? = null
                    while (rs.next()) {
                        val fkId = rs.getInt(1)
                        val pkTableName = rs.getString(3)
                        val fkColName = rs.getString(4)
                        val pkColName = rs.getString(5)
                        val onUpdate = rs.getString(6)
                        val onDelete = rs.getString(7)
                        val match = rs.getString(8)

                        var fkName: String? = null
                        if (fkNames.size > count) fkName = fkNames.get(count)

                        if (fkId != prevFkId) {
                            fk =
                                ForeignKey(
                                    fkName,
                                    pkTableName,
                                    fkTableName,
                                    onUpdate,
                                    onDelete,
                                    match
                                )
                            fkList.add(fk!!)
                            prevFkId = fkId
                            count++
                        }
                        if (fk != null) {
                            fk.addColumnMapping(fkColName, pkColName)
                        }
                    }
                }
            }
        }

        @Throws(SQLException::class)
        private fun getForeignKeyNames(tbl: String?): MutableList<String> {
            val fkNames: MutableList<String> = ArrayList<String>()
            if (tbl == null) {
                return fkNames
            }
            conn!!.createStatement().use { stat2 ->
                stat2.executeQuery(
                    ("select sql from sqlite_schema where"
                            + " lower(name) = lower('"
                            + escape(tbl)
                            + "')")
                ).use { rs ->
                    if (rs.next()) {
                        val matcher = FK_NAMED_PATTERN.matcher(rs.getString(1))

                        while (matcher.find()) {
                            fkNames.add(matcher.group(1))
                        }
                    }
                }
            }
            Collections.reverse(fkNames)
            return fkNames
        }

        internal inner class ForeignKey(
            val fkName: String?,
            val pkTableName: String?,
            val fkTableName: String,
            val onUpdate: String?,
            val onDelete: String?,
            val match: String?
        ) {
            private val fkColNames: MutableList<String?> = ArrayList<String?>()
            private val pkColNames: MutableList<String?> = ArrayList<String?>()

            fun addColumnMapping(fkColName: String?, pkColName: String?) {
                fkColNames.add(fkColName)
                pkColNames.add(pkColName)
            }

            fun getColumnMapping(colSeq: Int): Array<String?> {
                return arrayOf<String?>(fkColNames.get(colSeq), pkColNames.get(colSeq))
            }

            val columnMappingCount: Int
                get() = fkColNames.size

            override fun toString(): String {
                return ("ForeignKey [fkName="
                        + fkName
                        + ", pkTableName="
                        + pkTableName
                        + ", fkTableName="
                        + fkTableName
                        + ", pkColNames="
                        + pkColNames
                        + ", fkColNames="
                        + fkColNames
                        + "]")
            }
        }
    }

    /**
     * @see Object.finalize
     */
    @Throws(Throwable::class)
    override fun finalize() {
        close()
    }

    /**
     * Follow rules in [SQLite Keywords](https://www.sqlite.org/lang_keywords.html)
     * 
     * @param name Identifier name
     * @return Unquoted identifier
     */
    private fun unquoteIdentifier(name: String?): String? {
        var name = name
        if (name == null) return name
        name = name.trim { it <= ' ' }
        if (name.length > 2
            && ((name.startsWith("`") && name.endsWith("`"))
                    || (name.startsWith("\"") && name.endsWith("\""))
                    || (name.startsWith("[") && name.endsWith("]")))
        ) {
            // unquote to be consistent with column names returned by getColumns()
            name = name.substring(1, name.length - 1)
        }
        return name
    }

    /**
     * Class-wrapper around the logger object to avoid build-time initialization of the logging
     * framework in native-image
     */
    private object LogHolder {
        val logger = getLogger(JDBC3DatabaseMetaData::class.java)
    }

    companion object {
        private var driverName: String? = null
        private var driverVersion: String? = null

        init {
            try {
                JDBC3DatabaseMetaData::class.java
                    .classLoader
                    .getResourceAsStream("sqlite-jdbc.properties").use { sqliteJdbcPropStream ->
                        if (sqliteJdbcPropStream == null) {
                            throw IOException("Cannot load sqlite-jdbc.properties from jar")
                        }
                        val sqliteJdbcProp = Properties()
                        sqliteJdbcProp.load(sqliteJdbcPropStream)
                        driverName = sqliteJdbcProp.getProperty("name")
                        driverVersion = sqliteJdbcProp.getProperty("version")
                    }
            } catch (e: Exception) {
                // Default values
                driverName = "SQLite JDBC"
                driverVersion = "3.0.0-UNKNOWN"
            }
        }

        // Column type patterns
        protected val TYPE_INTEGER: Pattern = Pattern.compile(".*(INT|BOOL).*")
        protected val TYPE_VARCHAR: Pattern = Pattern.compile(".*(CHAR|CLOB|TEXT|BLOB).*")
        protected val TYPE_FLOAT: Pattern = Pattern.compile(".*(REAL|FLOA|DOUB|DEC|NUM).*")

        private val RULE_MAP: MutableMap<String, Int> = HashMap<String, Int>()

        init {
            RULE_MAP.put("NO ACTION", DatabaseMetaData.importedKeyNoAction)
            RULE_MAP.put("CASCADE", DatabaseMetaData.importedKeyCascade)
            RULE_MAP.put("RESTRICT", DatabaseMetaData.importedKeyRestrict)
            RULE_MAP.put("SET NULL", DatabaseMetaData.importedKeySetNull)
            RULE_MAP.put("SET DEFAULT", DatabaseMetaData.importedKeySetDefault)
        }

        // inner classes
        /** Pattern used to extract column order for an unnamed primary key.  */
        protected val PK_UNNAMED_PATTERN: Pattern = Pattern.compile(
            ".*PRIMARY\\s+KEY\\s*\\((.*?)\\).*", Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )

        /** Pattern used to extract a named primary key.  */
        protected val PK_NAMED_PATTERN: Pattern = Pattern.compile(
            ".*CONSTRAINT\\s*(.*?)\\s*PRIMARY\\s+KEY\\s*\\((.*?)\\).*",
            Pattern.CASE_INSENSITIVE or Pattern.DOTALL
        )
    }
}
