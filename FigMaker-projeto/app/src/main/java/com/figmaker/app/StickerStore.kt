package com.figmaker.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object StickerStore {
    fun dir(ctx: Context) = File(ctx.filesDir, "packs")

    /** Cria um pacote: sua figurinha + 2 figurinhas em branco (o WhatsApp exige mínimo de 3). */
    fun create(ctx: Context, media: Media, webp: ByteArray): Pair<String, String> {
        val id = "pack_" + System.currentTimeMillis()
        val d = File(dir(ctx), id)
        d.mkdirs()
        File(d, "sticker_1.webp").writeBytes(webp)
        val blank = Converter.blank(media.animated)
        File(d, "sticker_2.webp").writeBytes(blank)
        File(d, "sticker_3.webp").writeBytes(blank)
        File(d, "tray.png").writeBytes(Converter.tray(media))
        val name = "Figurinha " + SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date())
        File(d, "name.txt").writeText(name)
        if (media.animated) File(d, "animated").createNewFile()
        return id to name
    }
}
