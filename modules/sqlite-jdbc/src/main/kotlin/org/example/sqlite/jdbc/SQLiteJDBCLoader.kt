/*--------------------------------------------------------------------------
 *  Copyright 2007 Taro L. Saito
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *--------------------------------------------------------------------------*/
// --------------------------------------
// SQLite JDBC Project
//
// SQLite.java
// Since: 2007/05/10
//
// $URL$
// $Author$
// --------------------------------------
package org.example.sqlite.jdbc

import org.example.sqlite.jdbc.core.WorkerDB

object SQLiteJDBCLoader {
    @JvmStatic
    val majorVersion: Int
        /**
         * @return The major version of the SQLite JDBC driver.
         */
        get() {
            val c =
                version.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            return if (c.size > 0) c[0].toInt() else 1
        }

    @JvmStatic
    val minorVersion: Int
        /**
         * @return The minor version of the SQLite JDBC driver.
         */
        get() {
            val c =
                version.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            return if (c.size > 1) c[1].toInt() else 0
        }

    @JvmStatic
    val version: String
        /**
         * @return The version of the SQLite JDBC driver.
         */
        get() = WorkerDB.version()
}
