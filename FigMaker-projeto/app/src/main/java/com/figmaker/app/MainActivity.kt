package com.figmaker.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var preview: ImageView
    private lateinit var addBtn: Button
    private var packId: String? = null
    private var packName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (24 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }
        val pick = Button(this).apply {
            text = "Escolher imagem ou GIF"
            setOnClickListener { pickFile() }
        }
        preview = ImageView(this).apply {
            setBackgroundColor(0xFFDDDDDD.toInt())
            layoutParams = LinearLayout.LayoutParams((260 * d).toInt(), (260 * d).toInt()).apply {
                topMargin = pad; bottomMargin = pad
            }
        }
        status = TextView(this).apply { text = "Escolha uma imagem ou GIF"; gravity = Gravity.CENTER }
        addBtn = Button(this).apply {
            text = "Adicionar ao WhatsApp"
            isEnabled = false
            setOnClickListener { addToWhatsApp() }
        }
        root.addView(pick); root.addView(preview); root.addView(status); root.addView(addBtn)
        setContentView(root)

        if (savedInstanceState == null && intent?.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (uri != null) convert(uri)
        }
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(i, "Escolher"), 100)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK) {
            data?.data?.let { convert(it) }
        } else if (requestCode == 200 && resultCode == RESULT_CANCELED) {
            val err = data?.getStringExtra("validation_error")
            if (err != null) status.text = "WhatsApp recusou: $err"
        }
    }

    private fun convert(uri: Uri) {
        status.text = "Convertendo..."
        addBtn.isEnabled = false
        Thread {
            try {
                val media = Converter.load(this, uri)
                val webp = Converter.encode(media)
                val (id, name) = StickerStore.create(this, media, webp)
                runOnUiThread {
                    preview.setImageBitmap(media.frames[0])
                    packId = id; packName = name
                    addBtn.isEnabled = true
                    val kind = if (media.animated) "animada" else "estática"
                    status.text = "Pronto! Figurinha $kind (${webp.size / 1024} KB)"
                }
            } catch (e: Throwable) {
                runOnUiThread { status.text = "Erro: ${e.message ?: e.javaClass.simpleName}" }
            }
        }.start()
    }

    private fun addToWhatsApp() {
        val id = packId ?: return
        val i = Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK").apply {
            putExtra("sticker_pack_id", id)
            putExtra("sticker_pack_authority", packageName + ".stickers")
            putExtra("sticker_pack_name", packName)
        }
        try {
            startActivityForResult(i, 200)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "WhatsApp não encontrado", Toast.LENGTH_LONG).show()
        }
    }
}
