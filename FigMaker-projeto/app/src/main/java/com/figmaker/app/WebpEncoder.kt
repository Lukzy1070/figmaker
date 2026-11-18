package com.figmaker.app

import android.graphics.Bitmap

object WebpEncoder {
    init { System.loadLibrary("figmaker") }

    /** Todos os bitmaps devem ser ARGB_8888 e do mesmo tamanho. 1 frame = webp estático. */
    external fun encode(frames: Array<Bitmap>, delaysMs: IntArray, quality: Int, lossless: Boolean): ByteArray?
}
