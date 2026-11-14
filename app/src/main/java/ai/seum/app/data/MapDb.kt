package ai.seum.app.data

import ai.seum.engine.Sql
import ai.seum.engine.SqlMapData
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Opens the bundled map database. The APK ships assets/seum.db.gz (~37 MB); on first launch it is
 * expanded to files/seum-<version>.db and opened read-only afterwards.
 */
class MapDb(private val context: Context) {
    private var db: SQLiteDatabase? = null

    val data: SqlMapData by lazy { SqlMapData(AndroidSql { open() }) }

    @Synchronized
    fun open(): SQLiteDatabase {
        db?.let { return it }
        val target = File(context.filesDir, "seum-$DB_VERSION.db")
        if (!target.exists() || target.length() == 0L) {
            val t0 = System.currentTimeMillis()
            context.filesDir.listFiles { f -> f.name.startsWith("seum-") && f.name.endsWith(".db") }?.forEach { it.delete() }
            val tmp = File(context.filesDir, "seum.tmp")
            // The build tool unpacks *.gz assets, so the APK normally holds plain "seum.db".
            val plain = runCatching { context.assets.open(ASSET_PLAIN) }.getOrNull()
            if (plain != null) {
                plain.use { raw -> tmp.outputStream().use { raw.copyTo(it, 1 shl 16) } }
            } else {
                context.assets.open(ASSET).use { raw ->
                    GZIPInputStream(raw.buffered(1 shl 16)).use { gz -> tmp.outputStream().use { gz.copyTo(it, 1 shl 16) } }
                }
            }
            check(tmp.renameTo(target)) { "could not move map database into place" }
            Log.i(TAG, "expanded map db in ${System.currentTimeMillis() - t0} ms (${target.length() / 1_000_000} MB)")
        }
        return SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY).also { db = it }
    }

    companion object {
        private const val TAG = "Seum/MapDb"
        private const val ASSET = "seum.db.gz"
        private const val ASSET_PLAIN = "seum.db"
        /** Bump when the bundled database changes so old copies are replaced. */
        const val DB_VERSION = 7
    }
}

/**
 * Sql over android.database.sqlite. Our queries only bind grid-cell numbers, which we generate
 * ourselves, so numeric arguments are inlined (keeps INTEGER comparisons and the index usable).
 */
class AndroidSql(private val db: () -> SQLiteDatabase) : Sql {
    override fun query(sql: String, args: List<Any>): List<Array<Any?>> {
        val textArgs = ArrayList<String>()
        val sb = StringBuilder()
        var argIdx = 0
        for (ch in sql) {
            if (ch == '?' && argIdx < args.size) {
                when (val a = args[argIdx++]) {
                    is Long, is Int -> sb.append(a.toString())
                    is Double -> sb.append(a.toString())
                    else -> { sb.append('?'); textArgs += a.toString() }
                }
            } else sb.append(ch)
        }
        db().rawQuery(sb.toString(), textArgs.toTypedArray()).use { c ->
            val out = ArrayList<Array<Any?>>(c.count)
            val n = c.columnCount
            while (c.moveToNext()) {
                out += Array(n) { i ->
                    when (c.getType(i)) {
                        android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        android.database.Cursor.FIELD_TYPE_STRING -> c.getString(i)
                        android.database.Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                        else -> null
                    }
                }
            }
            return out
        }
    }
}
