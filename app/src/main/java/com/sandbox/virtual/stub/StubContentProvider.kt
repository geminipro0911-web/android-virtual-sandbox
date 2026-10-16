package com.sandbox.virtual.stub

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log

/**
 * Pre-registered Stub Content Provider across isolated processes.
 */
open class StubContentProvider : ContentProvider() {

    companion object {
        private const val TAG = "StubContentProvider"
    }

    override fun onCreate(): Boolean {
        Log.d(TAG, "StubContentProvider created in process pid=${android.os.Process.myPid()}")
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        return MatrixCursor(projection ?: arrayOf("_id"))
    }

    override fun getType(uri: Uri): String? {
        return "vnd.android.cursor.dir/virtual"
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        return uri
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        return 0
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        return 0
    }

    // Concrete Stub implementations assigned to :virtual_p0 and :virtual_p1
    class P0 : StubContentProvider()
    class P1 : StubContentProvider()
}
