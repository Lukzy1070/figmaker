package com.figmaker.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

class StickerProvider : ContentProvider() {
    private val matcher = UriMatcher(UriMatcher.NO_MATCH)

    override fun onCreate(): Boolean {
        val a = context!!.packageName + ".stickers"
        matcher.addURI(a, "metadata", 1)
        matcher.addURI(a, "metadata/*", 2)
        matcher.addURI(a, "stickers/*", 3)
        matcher.addURI(a, "stickers_asset/*/*", 4)
        return true
    }

    private fun safe(s: String) = s.isNotEmpty() && !s.contains("..") && !s.contains("/") && !s.contains("\\")

    private fun packIds(): List<String> =
        StickerStore.dir(context!!).listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted()
            ?: emptyList()

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? = when (matcher.match(uri)) {
        1 -> metadata(packIds())
        2 -> metadata(listOf(uri.lastPathSegment ?: ""))
        3 -> stickers(uri.lastPathSegment ?: "")
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }

    private fun metadata(ids: List<String>): Cursor {
        val c = MatrixCursor(
            arrayOf(
                "sticker_pack_identifier", "sticker_pack_name", "sticker_pack_publisher",
                "sticker_pack_icon", "android_play_store_link", "ios_app_download_link",
                "sticker_pack_publisher_email", "sticker_pack_publisher_website",
                "sticker_pack_privacy_policy_website", "sticker_pack_license_agreement_website",
                "image_data_version", "whatsapp_will_not_cache_stickers", "animated_sticker_pack"
            )
        )
        for (id in ids) {
            if (!safe(id)) continue
            val d = File(StickerStore.dir(context!!), id)
            if (!d.isDirectory) continue
            val nameFile = File(d, "name.txt")
            val name = if (nameFile.exists()) nameFile.readText() else id
            val pubFile = File(d, "publisher.txt")
            val publisher = if (pubFile.exists()) pubFile.readText().trim().ifEmpty { "FigMaker" } else "FigMaker"
            val anim = if (File(d, "animated").exists()) 1 else 0
            c.addRow(arrayOf<Any>(id, name, publisher, "tray.png", "", "", "", "", "", "", "1", 0, anim))
        }
        return c
    }

    private fun stickers(id: String): Cursor {
        val c = MatrixCursor(arrayOf("sticker_file_name", "sticker_emoji", "sticker_accessibility_text"))
        if (safe(id)) {
            for (i in 1..30) {
                if (File(File(StickerStore.dir(context!!), id), "sticker_$i.webp").exists())
                    c.addRow(arrayOf<Any>("sticker_$i.webp", "😀", "Figurinha"))
            }
        }
        return c
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        if (matcher.match(uri) != 4) return null
        val seg = uri.pathSegments
        val id = seg[seg.size - 2]
        val file = seg[seg.size - 1]
        if (!safe(id) || !safe(file)) return null
        val f = File(File(StickerStore.dir(context!!), id), file)
        if (!f.exists()) return null
        return AssetFileDescriptor(
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY), 0, AssetFileDescriptor.UNKNOWN_LENGTH
        )
    }

    override fun getType(uri: Uri): String? {
        val a = context!!.packageName + ".stickers"
        return when (matcher.match(uri)) {
            1 -> "vnd.android.cursor.dir/vnd.$a.metadata"
            2 -> "vnd.android.cursor.item/vnd.$a.metadata"
            3 -> "vnd.android.cursor.dir/vnd.$a.stickers"
            4 -> if (uri.lastPathSegment?.endsWith(".png") == true) "image/png" else "image/webp"
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
