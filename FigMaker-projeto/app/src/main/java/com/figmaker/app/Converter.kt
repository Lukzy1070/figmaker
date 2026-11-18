package com.figmaker.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
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

object Converter {
    const val SIZE = 512
    private const val MAX_FRAMES = 80
    private const val MAX_TOTAL_MS = 10_000

    fun load(ctx: Context, uri: Uri): Media {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Não consegui abrir o arquivo")
        val isGif = bytes.size > 6 &&
            bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()
        return if (isGif) loadGif(bytes) else loadStatic(bytes)
    }

    /** Encaixa a imagem em 512x512 com fundo TRANSPARENTE (sem borda branca). */
    private fun fit(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val scale = minOf(SIZE.toFloat() / src.width, SIZE.toFloat() / src.height)
        val w = src.width * scale
        val h = src.height * scale
        val l = (SIZE - w) / 2f
        val t = (SIZE - h) / 2f
        Canvas(out).drawBitmap(
            src, null, RectF(l, t, l + w, t + h),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        )
        return out
    }

    private fun loadStatic(bytes: ByteArray): Media {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
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
        return Media(listOf(fit(bmp)), listOf(1000))
    }

    private fun loadGif(bytes: ByteArray): Media {
        val header = GifHeaderParser().setData(bytes).parseHeader()
        if (header.frameCount <= 0) throw IllegalArgumentException("GIF inválido")
        val provider = object : GifDecoder.BitmapProvider {
            override fun obtain(width: Int, height: Int, config: Bitmap.Config): Bitmap =
                Bitmap.createBitmap(width, height, config)
            override fun release(bitmap: Bitmap) {}
            override fun obtainByteArray(size: Int): ByteArray = ByteArray(size)
            override fun release(bytes: ByteArray) {}
            override fun obtainIntArray(size: Int): IntArray = IntArray(size)
            override fun release(array: IntArray) {}
        }
        val decoder = StandardGifDecoder(provider, header, ByteBuffer.wrap(bytes), 1)
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
                    frames.add(fit(frame))
                    delays.add(accum)
                    total += accum
                }
                accum = 0
            }
        }
        if (frames.isEmpty()) throw IllegalArgumentException("Não consegui ler o GIF")
        return Media(frames, delays)
    }

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

    /** Gera o WebP final respeitando os limites do WhatsApp (100 KB estático, 500 KB animado). */
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
        throw IllegalStateException("Não consegui reduzir o arquivo para o limite do WhatsApp")
    }

    /** Figurinha em branco (transparente). No pacote animado precisa ter 2 frames. */
    fun blank(animated: Boolean): ByteArray {
        val a = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        if (!animated) return WebpEncoder.encode(arrayOf(a), intArrayOf(1000), 100, true)!!
        val b = a.copy(Bitmap.Config.ARGB_8888, true)
        b.setPixel(0, 0, Color.argb(8, 255, 255, 255)) // diferença mínima para não fundir os frames
        return WebpEncoder.encode(arrayOf(a, b), intArrayOf(500, 500), 100, true)!!
    }

    fun tray(media: Media): ByteArray {
        val s = Bitmap.createScaledBitmap(media.frames[0], 96, 96, true)
        val bos = ByteArrayOutputStream()
        s.compress(Bitmap.CompressFormat.PNG, 100, bos)
        return bos.toByteArray()
    }
}
