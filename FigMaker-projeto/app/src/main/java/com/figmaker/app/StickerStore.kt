package com.figmaker.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PackInfo(val id: String, val name: String, val count: Int)

object StickerStore {
    fun dir(ctx: Context) = File(ctx.filesDir, "packs")

    /**
     * Cria os pacotes. O WhatsApp não mistura estáticas e animadas no mesmo pacote,
     * então se houver as duas, saem 2 pacotes. Máx. 30 por pacote, mínimo 3 (completa com em branco).
     */
    fun createPacks(ctx: Context, built: List<Built>): List<PackInfo> {
        val out = ArrayList<PackInfo>()
        val stamp = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date())
        val now = System.currentTimeMillis()

        for (anim in listOf(false, true)) {
            val group = built.filter { it.animated == anim }
            if (group.isEmpty()) continue
            val chunks = group.chunked(30)
            chunks.forEachIndexed { ci, chunk ->
                val id = "pack_${now}_${if (anim) "a" else "s"}$ci"
                val d = File(dir(ctx), id)
                d.mkdirs()
                chunk.forEachIndexed { i, b -> File(d, "sticker_${i + 1}.webp").writeBytes(b.webp) }
                var n = chunk.size
                if (n < 3) {
                    val blank = Converter.blank(anim)
                    while (n < 3) { n++; File(d, "sticker_$n.webp").writeBytes(blank) }
                }
                File(d, "tray.png").writeBytes(chunk[0].tray)
                var name = (if (anim) "Animadas " else "Figurinhas ") + stamp
                if (chunks.size > 1) name += " (${ci + 1})"
                File(d, "name.txt").writeText(name)
                if (anim) File(d, "animated").createNewFile()
                out.add(PackInfo(id, name, n))
            }
        }
        return out
    }
}
