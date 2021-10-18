// MODULE: m1
// FILE: m1.kt

package m1

interface BasicDatabase

abstract class BaseIntrospector<D : BasicDatabase> {
    protected abstract fun createDatabaseRetriever(database: D): AbstractDatabaseRetriever<out D>

    protected abstract inner class AbstractDatabaseRetriever<D : BasicDatabase>
    protected constructor(protected val database: D)
        : AbstractRetriever()

    protected abstract inner class AbstractRetriever
}

// MODULE: m2(m1)
// FILE: m2.kt

package m2

import m1.*

interface SqliteRoot : BasicDatabase

class SqliteIntrospector : BaseIntrospector<SqliteRoot>() {
    override fun createDatabaseRetriever(database: SqliteRoot) =
        object : AbstractDatabaseRetriever<SqliteRoot>(database) {
        }
}
