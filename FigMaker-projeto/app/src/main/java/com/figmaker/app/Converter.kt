package com.figmaker.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.bumptech.glide.gifdecoder.GifDecoder
import com.bumptech.glide.gifdecoder.GifHeaderParser
import com.bumptech.glide.gifdecoder.StandardGifDecoder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class Media(val frames: List<Bitmap>, val delays: List<Int>) {
    val animated: Boolean get() = frames.size > 1
}

class Built(val webp: ByteArray, val animated: Boolean, val tray: ByteArray)

/** Um arquivo escolhido + o enquadramento (zoom/posição) definido pelo usuário. */
class Item(val uri: Uri, val kind: Int, val editorBmp: Bitmap, val srcW: Int, val srcH: Int) {
    companion object {
        const val STATIC = 0
        const val GIF = 1
        const val VIDEO = 2
    }

    private val ef = editorBmp.width.toFloat() / srcW
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    // saída (512x512) = origem * scale + (tx, ty)   [origem em pixels do arquivo original]
    var scale = 1f
    // esticar: multiplicadores independentes de largura/altura (1 = proporção original)
    var sx = 1f
    var sy = 1f
    var tx = 0f
    var ty = 0f

    val fitScale: Float get() = minOf(Converter.SIZE / srcW.toFloat(), Converter.SIZE / srcH.toFloat())
    val fillScale: Float get() = maxOf(Converter.SIZE / srcW.toFloat(), Converter.SIZE / srcH.toFloat())

    init { reset(true) }

    fun reset(fill: Boolean) {
        scale = if (fill) fillScale else fitScale
        sx = 1f
        sy = 1f
        tx = (Converter.SIZE - srcW * scale) / 2f
        ty = (Converter.SIZE - srcH * scale) / 2f
    }

    /** Estica a mídia para cobrir os 512x512 inteiros, ignorando a proporção original. */
    fun stretchToFill() {
        scale = fillScale
        sx = (Converter.SIZE / srcW.toFloat()) / scale
        sy = (Converter.SIZE / srcH.toFloat()) / scale
        tx = 0f
        ty = 0f
    }

    /** Muda o esticamento mantendo o centro da mídia no mesmo lugar. */
    fun setStretch(newSx: Float, newSy: Float) {
        val cx = tx + srcW * scale * sx / 2f
        val cy = ty + srcH * scale * sy / 2f
        sx = newSx
        sy = newSy
        tx = cx - srcW * scale * sx / 2f
        ty = cy - srcH * scale * sy / 2f
    }

    /** Desenha o resultado num quadrado de [size] pixels (usado no editor e na prévia). */
    fun draw(canvas: Canvas, size: Float) {
        val k = size / Converter.SIZE
        val m = Matrix()
        m.setScale(scale * sx / ef, scale * sy / ef)
        m.postTranslate(tx, ty)
        m.postScale(k, k)
        canvas.save()
        canvas.clipRect(0f, 0f, size, size)
        canvas.drawBitmap(editorBmp, m, paint)
        canvas.restore()
    }
}

object Converter {
    const val SIZE = 512
    private const val MAX_FRAMES = 80
    private const val MAX_TOTAL_MS = 10_000
    private val renderPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    // ---------- abrir arquivos (para o editor) ----------

    fun openItem(ctx: Context, uri: Uri): Item {
        val type = ctx.contentResolver.getType(uri) ?: ""
        if (type.startsWith("video/")) return openVideo(ctx, uri)
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Não consegui abrir o arquivo")
        return if (isGif(bytes)) openGif(uri, bytes)
        else try { openStatic(uri, bytes) } catch (e: Exception) { openVideo(ctx, uri) }
    }

    private fun isGif(b: ByteArray) = b.size > 6 &&
        b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte()

    private fun forEditor(src: Bitmap): Bitmap {
        val m = maxOf(src.width, src.height)
        return if (m > 1024) {
            val s = 1024f / m
            Bitmap.createScaledBitmap(
                src, (src.width * s).toInt().coerceAtLeast(1), (src.height * s).toInt().coerceAtLeast(1), true
            )
        } else src.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun openStatic(uri: Uri, bytes: ByteArray): Item {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: throw IllegalArgumentException("Imagem inválida")

        val orientation = try {
            ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        }
        if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        return Item(uri, Item.STATIC, bmp, bmp.width, bmp.height)
    }

    private fun gifDecoder(bytes: ByteArray): StandardGifDecoder {
        val header = GifHeaderParser().setData(bytes).parseHeader()
        if (header.numFrames <= 0) throw IllegalArgumentException("GIF inválido")
        val provider = object : GifDecoder.BitmapProvider {
            override fun obtain(width: Int, height: Int, config: Bitmap.Config): Bitmap =
                Bitmap.createBitmap(width, height, config)
            override fun release(bitmap: Bitmap) {}
            override fun obtainByteArray(size: Int): ByteArray = ByteArray(size)
            override fun release(bytes: ByteArray) {}
            override fun obtainIntArray(size: Int): IntArray = IntArray(size)
            override fun release(array: IntArray) {}
        }
        return StandardGifDecoder(provider, header, ByteBuffer.wrap(bytes), 1)
    }

    private fun openGif(uri: Uri, bytes: ByteArray): Item {
        val dec = gifDecoder(bytes)
        dec.advance()
        val f = dec.nextFrame ?: throw IllegalArgumentException("GIF inválido")
        return Item(uri, Item.GIF, forEditor(f), f.width, f.height)
    }

    private fun openVideo(ctx: Context, uri: Uri): Item {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val f = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: throw IllegalArgumentException("Não consegui ler o vídeo")
            return Item(uri, Item.VIDEO, forEditor(f), f.width, f.height)
        } finally {
            r.release()
        }
    }

    // ---------- gerar os frames finais 512x512 (com o recorte do usuário) ----------

    fun render(item: Item, frame: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val k = item.srcW.toFloat() / frame.width
        val m = Matrix()
        m.setScale(k * item.scale * item.sx, k * item.scale * item.sy)
        m.postTranslate(item.tx, item.ty)
        Canvas(out).drawBitmap(frame, m, renderPaint)
        return out
    }

    fun buildMedia(ctx: Context, item: Item): Media = when (item.kind) {
        Item.STATIC -> Media(listOf(render(item, item.editorBmp)), listOf(1000))
        Item.GIF -> gifMedia(ctx, item)
        else -> videoMedia(ctx, item)
    }

    private fun gifMedia(ctx: Context, item: Item): Media {
        val bytes = ctx.contentResolver.openInputStream(item.uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Não consegui abrir o GIF")
        val decoder = gifDecoder(bytes)
        val n = decoder.frameCount
        val step = (n + MAX_FRAMES - 1) / MAX_FRAMES

        val frames = ArrayList<Bitmap>()
        val delays = ArrayList<Int>()
        var accum = 0
        var total = 0
        for (i in 0 until n) {
            decoder.advance()
            val frame = decoder.nextFrame   // precisa decodificar todos (frames dependem dos anteriores)
            var d = decoder.getDelay(i)
            if (d <= 10) d = 100
            accum += d
            if (i % step == step - 1 || i == n - 1) {
                if (frame != null) {
                    if (total + accum > MAX_TOTAL_MS && frames.isNotEmpty()) break
                    frames.add(render(item, frame))
                    delays.add(accum)
                    total += accum
                }
                accum = 0
            }
        }
        if (frames.isEmpty()) throw IllegalArgumentException("Não consegui ler o GIF")
        return Media(frames, delays)
    }

    private fun videoMedia(ctx: Context, item: Item): Media {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, item.uri)
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 3000L
            val total = minOf(durMs, 8000L)
            val stepMs = 100L // 10 quadros por segundo
            val frames = ArrayList<Bitmap>()
            val delays = ArrayList<Int>()
            var t = 0L
            while (t < total && frames.size < MAX_FRAMES) {
                val f = r.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                if (f != null) {
                    frames.add(render(item, f))
                    delays.add(stepMs.toInt())
                }
                t += stepMs
            }
            if (frames.isEmpty()) throw IllegalArgumentException("Não consegui ler o vídeo")
            return Media(frames, delays)
        } finally {
            r.release()
        }
    }

    // ---------- encoder / limites do WhatsApp ----------

    private fun subsample(media: Media, step: Int): Pair<List<Bitmap>, List<Int>> {
        if (step <= 1) return media.frames to media.delays
        val f = ArrayList<Bitmap>()
        val d = ArrayList<Int>()
        var i = 0
        while (i < media.frames.size) {
            var sum = 0
            for (k in i until minOf(i + step, media.frames.size)) sum += media.delays[k]
            f.add(media.frames[i]); d.add(sum)
            i += step
        }
        return f to d
    }

    /** WebP final respeitando os limites do WhatsApp (100 KB estático, 500 KB animado). */
    fun encode(media: Media): ByteArray {
        val limit = if (media.animated) 490_000 else 98_000
        var step = 1
        while (true) {
            val (bmps, dels) = subsample(media, step)
            for (q in intArrayOf(75, 55, 35, 20, 8)) {
                val out = WebpEncoder.encode(bmps.toTypedArray(), dels.toIntArray(), q, false) ?: continue
                if (out.size <= limit) return out
            }
            if (!media.animated || bmps.size <= 3 || step >= 8) break
            step++
        }
        throw IllegalStateException("Arquivo grande demais para o limite do WhatsApp")
    }

    /** Figurinha em branco (transparente). No pacote animado precisa ter 2 frames. */
    fun blank(animated: Boolean): ByteArray {
        val a = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        if (!animated) return WebpEncoder.encode(arrayOf(a), intArrayOf(1000), 100, true)!!
        val b = a.copy(Bitmap.Config.ARGB_8888, true)
        b.setPixel(0, 0, Color.argb(8, 255, 255, 255)) // diferença mínima para não fundir os frames
        return WebpEncoder.encode(arrayOf(a, b), intArrayOf(500, 500), 100, true)!!
    }

    fun tray(frame: Bitmap): ByteArray {
        val s = Bitmap.createScaledBitmap(frame, 96, 96, true)
        val bos = ByteArrayOutputStream()
        s.compress(Bitmap.CompressFormat.PNG, 100, bos)
        return bos.toByteArray()
    }
}
