package com.figmaker.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val items = ArrayList<Item>()
    private var cur = 0
    private var bgMode = 0
    private var working = false

    private lateinit var crop: CropView
    private lateinit var chat: ChatPreview
    private lateinit var counter: TextView
    private lateinit var status: TextView
    private lateinit var packsBox: LinearLayout
    private lateinit var createBtn: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun btn(label: String, weight: Float = 0f, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        layoutParams = if (weight > 0f)
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        else
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    private fun row(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        val side = minOf(resources.displayMetrics.widthPixels - 2 * pad, dp(420))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(btn("Escolher imagens, GIFs ou vídeos") { pickFiles() })

        val nav = row()
        nav.addView(btn("◀", 0.6f) { move(-1) })
        counter = TextView(this).apply {
            gravity = Gravity.CENTER
            text = "Nenhum arquivo"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f)
        }
        nav.addView(counter)
        nav.addView(btn("▶", 0.6f) { move(1) })
        nav.addView(btn("Remover", 1.2f) { removeCurrent() })
        root.addView(nav)

        crop = CropView(this).apply {
            layoutParams = LinearLayout.LayoutParams(side, side).apply { topMargin = dp(8) }
            onChange = { chat.invalidate() }
        }
        root.addView(crop)

        root.addView(TextView(this).apply {
            text = "Arraste para mover · pince para dar zoom"
            gravity = Gravity.CENTER
            textSize = 12f
        })

        val tools = row()
        tools.addView(btn("Encaixar", 1f) { current()?.let { it.reset(false); refresh() } })
        tools.addView(btn("Preencher", 1f) { current()?.let { it.reset(true); refresh() } })
        tools.addView(btn("Fundo", 1f) {
            bgMode = (bgMode + 1) % 3
            crop.bg = bgMode; chat.bg = bgMode
            refresh()
        })
        root.addView(tools)

        root.addView(TextView(this).apply {
            text = "Prévia na conversa (tamanho aproximado)"
            gravity = Gravity.CENTER
            textSize = 12f
            setPadding(0, dp(8), 0, dp(4))
        })
        chat = ChatPreview(this).apply { layoutParams = LinearLayout.LayoutParams(side, ViewGroup.LayoutParams.WRAP_CONTENT) }
        root.addView(chat)

        createBtn = btn("Criar pacote de figurinhas") { createPacks() }
        root.addView(createBtn)

        status = TextView(this).apply {
            gravity = Gravity.CENTER
            text = "Escolha um ou mais arquivos"
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(status)

        packsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(packsBox)

        setContentView(ScrollView(this).apply { addView(root) })

        if (savedInstanceState == null) {
            val uris = ArrayList<Uri>()
            when (intent?.action) {
                Intent.ACTION_SEND ->
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
                Intent.ACTION_SEND_MULTIPLE ->
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
            }
            if (uris.isNotEmpty()) loadUris(uris)
        }
    }

    private fun current(): Item? = if (working) null else items.getOrNull(cur)

    private fun refresh() {
        crop.invalidate()
        chat.invalidate()
    }

    private fun showCurrent() {
        val it = items.getOrNull(cur)
        crop.item = it
        chat.item = it
        counter.text = if (it == null) "Nenhum arquivo" else {
            val tipo = when (it.kind) { Item.GIF -> "GIF"; Item.VIDEO -> "vídeo"; else -> "imagem" }
            "${cur + 1}/${items.size} · $tipo"
        }
        refresh()
    }

    private fun move(delta: Int) {
        if (working || items.isEmpty()) return
        cur = (cur + delta + items.size) % items.size
        showCurrent()
    }

    private fun removeCurrent() {
        if (working || items.isEmpty()) return
        items.removeAt(cur)
        cur = cur.coerceIn(0, maxOf(items.size - 1, 0))
        showCurrent()
    }

    private fun pickFiles() {
        if (working) return
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(i, "Escolher"), 100)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            val uris = ArrayList<Uri>()
            val cd = data.clipData
            if (cd != null) {
                for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri)
            } else {
                data.data?.let { uris.add(it) }
            }
            loadUris(uris)
        } else if (requestCode == 200 && resultCode == RESULT_CANCELED) {
            val err = data?.getStringExtra("validation_error")
            if (err != null) status.text = "WhatsApp recusou: $err"
        }
    }

    private fun loadUris(uris: List<Uri>) {
        if (working) return
        working = true
        status.text = "Carregando..."
        Thread {
            var fail = 0
            val added = ArrayList<Item>()
            uris.forEachIndexed { i, u ->
                if (items.size + added.size < 60) {
                    try { added.add(Converter.openItem(this, u)) } catch (e: Throwable) { fail++ }
                }
                runOnUiThread { status.text = "Carregando ${i + 1}/${uris.size}..." }
            }
            runOnUiThread {
                working = false
                val first = items.size
                items.addAll(added)
                if (added.isNotEmpty()) cur = first
                packsBox.removeAllViews()
                showCurrent()
                status.text = if (fail > 0) "$fail arquivo(s) não puderam ser abertos"
                else "${items.size} arquivo(s). Ajuste cada um e toque em Criar pacote."
            }
        }.start()
    }

    private fun createPacks() {
        if (working) return
        if (items.isEmpty()) {
            Toast.makeText(this, "Escolha um arquivo primeiro", Toast.LENGTH_SHORT).show()
            return
        }
        working = true
        createBtn.isEnabled = false
        packsBox.removeAllViews()
        val snapshot = items.toList()
        Thread {
            try {
                val built = ArrayList<Built>()
                snapshot.forEachIndexed { idx, item ->
                    runOnUiThread {
                        status.text = "Convertendo ${idx + 1}/${snapshot.size}..." +
                            (if (item.kind == Item.VIDEO) " (vídeo demora mais)" else "")
                    }
                    val media = Converter.buildMedia(this, item)
                    val webp = Converter.encode(media)
                    built.add(Built(webp, media.animated, Converter.tray(media.frames[0])))
                    media.frames.forEach { it.recycle() }
                }
                val packs = StickerStore.createPacks(this, built)
                runOnUiThread {
                    working = false
                    createBtn.isEnabled = true
                    status.text = "Pronto! Toque para adicionar ao WhatsApp:"
                    for (p in packs) {
                        packsBox.addView(btn("Adicionar: ${p.name} (${p.count})") { addToWhatsApp(p.id, p.name) })
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    working = false
                    createBtn.isEnabled = true
                    status.text = "Erro: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }.start()
    }

    private fun addToWhatsApp(id: String, name: String) {
        val i = Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK").apply {
            putExtra("sticker_pack_id", id)
            putExtra("sticker_pack_authority", packageName + ".stickers")
            putExtra("sticker_pack_name", name)
        }
        try {
            startActivityForResult(i, 200)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "WhatsApp não encontrado", Toast.LENGTH_LONG).show()
        }
    }
}
