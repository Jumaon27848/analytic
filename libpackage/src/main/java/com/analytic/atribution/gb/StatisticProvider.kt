package com.analytic.atribution.gb

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log

internal class StatisticProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val context: Context = this.context ?: run {
            Log.d(Constants.LOG_TAG, "Provider not initialized: no context")
            return false
        }

        val service = StatisticService(context)
        service.init()

        return true
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
