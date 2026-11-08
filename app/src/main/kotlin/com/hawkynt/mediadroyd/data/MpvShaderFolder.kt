package com.hawkynt.mediadroyd.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.hawkynt.mediadroyd.player.MpvShaders

/**
 * The folder of mpv user shaders the household picked in Settings (a document tree, its permission kept across
 * restarts): its `.glsl` files read into [MpvShaders], which the picture's shader choices and the stage take them from.
 */
object MpvShaderFolder {
    /** Reads the `.glsl` files of [tree] (a tree URI as Settings keeps it; empty: none) into [MpvShaders]. */
    fun load(context: Context, tree: String) {
        if (tree.isEmpty()) {
            MpvShaders.load(emptyMap())
            return
        }
        val files = runCatching {
            val root = Uri.parse(tree)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                root,
                DocumentsContract.getTreeDocumentId(root),
            )
            val found = mutableMapOf<String, String>()
            context.contentResolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1) ?: continue
                    if (!MpvShaders.isShader(name)) continue
                    val document = DocumentsContract.buildDocumentUriUsingTree(root, cursor.getString(0))
                    context.contentResolver.openInputStream(document)?.use { found[name] = String(it.readBytes()) }
                }
            }
            found
        }.getOrDefault(emptyMap())
        MpvShaders.load(files)
    }
}
