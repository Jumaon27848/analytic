package com.analytic.atribution.gb

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

internal class SQLiteEventRepository(
    private val context3: Context,
) {
    private val db get() = helper.writableDatabase
    private val helper = object : SQLiteOpenHelper(context3, "events.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE events (uuid TEXT PRIMARY KEY, timestamp INTEGER NOT NULL, name TEXT NOT NULL, parameters TEXT NOT NULL)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS events")
            onCreate(db)
        }
    }

    fun save(event: Event) {
        val values: ContentValues = ContentValues().apply {
            put("uuid", event.uuid)
            put("timestamp", event.timestamp2)
            put("name", event.name2)
            put("parameters", event.parameters2.toString())
        }
        db.insert("events", null, values)
    }

    fun delete(events: List<Event>) {
        val selection: String = "uuid IN (" + events.joinToString(",") { "?" } + ")"
        val selectionArgs: Array<String> = events.map { it.uuid }.toTypedArray()
        db.delete("events", selection, selectionArgs)
    }

    fun get(limit: Int): List<Event> {
        val cursor: Cursor = db.query(
            "events",
            null,
            null,
            null,
            null,
            null,
            "timestamp ASC",
            limit.toString()
        )
        val result: MutableList<Event> = mutableListOf()
        cursor.use {
            while (it.moveToNext()) {
                val uuid: String = it.getString(0)
                val timestamp: Long = it.getLong(1)
                val name: String = it.getString(2)
                val parameters: String = it.getString(3)
                result.add(
                    Event(
                        uuid,
                        timestamp,
                        name,
                        JSONObject(parameters)
                    )
                )
            }
        }
        return result
    }

    fun stats(): Pair<Int, Long?> {
        val sql: String = "SELECT COUNT(*) AS cnt, MIN(timestamp) AS oldest FROM events"
        db.rawQuery(sql, null).use { cursor ->
            return if (cursor.moveToFirst()) {
                val count: Int = cursor.getInt(0)
                val oldest: Long? = if (!cursor.isNull(1)) cursor.getLong(1) else null
                count to oldest
            } else {
                0 to null
            }
        }
    }
}