package com.gigaconet.pdf

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class EditorActivity : AppCompatActivity(), PageHost {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_FILE = "file"
        const val EXTRA_NAME = "name"
        const val EXTRA_OCR = "ocr"
        private val COLORS = intArrayOf(
            Color.parseColor("#111111"), Color.parseColor("#1A2B6D"), Color.parseColor("#2F6FDB"),
            Color.parseColor("#D93A3A"), Color.parseColor("#1F9468"), Color.parseColor("#F2C230"),
            Color.parseColor("#FF7A1A"), Color.parseColor("#8A4FD1")
        )
    }

    // Hilos: uno dibuja páginas, otro lee texto/OCR, otro modifica el archivo
    private val renderExec = Executors.newSingleThreadExecutor()
    private val textExec = Executors.newSingleThreadExecutor()
    private val ioExec = Executors.newSingleThreadExecutor()

    private lateinit var workFile: File
    private var sourceUri: Uri? = null
    private var docName = "documento.pdf"
    private var dirty = false

    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var aspects: List<Float> = emptyList()
    @Volatile private var generation = 0

    private val annots = HashMap<Int, MutableList<Annot>>()
    private val undo = ArrayList<() -> Unit>()
    private val blocks = HashMap<Int, List<TextBlock>>()
    private val loadingBlocks = HashSet<Int>()
    private var textDoc: PDDocument? = null

    private val bitmaps = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 5).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    override var tool = Tool.PAN
    override var penColor = COLORS[1]
    override var penWidth = 0.004f

    private lateinit var toolbar: MaterialToolbar
    private lateinit var pages: RecyclerView
    private lateinit var busy: View
    private lateinit var hint: TextView
    private lateinit var colorChip: Chip
    private val adapter = PagesAdapter()

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runFileOp("Agregando página…") {
            PdfOps.addImagePage(workFile, Store.loadBitmap(this, uri))
        }
    }

    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runFileOp("Agregando PDF…") {
            val other = File(cacheDir, "otro.pdf")
            val out = File(cacheDir, "union.pdf")
            Store.copyToFile(this, uri, other)
            PdfOps.merge(listOf(workFile, other), out)
            out.copyTo(workFile, overwrite = true)
            other.delete()
            out.delete()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        toolbar = findViewById(R.id.toolbar)
        pages = findViewById(R.id.pages)
        busy = findViewById(R.id.busy)
        hint = findViewById(R.id.hint)
        colorChip = findViewById(R.id.btnColor)

        toolbar.inflateMenu(R.menu.editor)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        toolbar.setOnMenuItemClickListener { onMenu(it.itemId); true }

        pages.layoutManager = LinearLayoutManager(this)
        pages.adapter = adapter
        pages.setItemViewCacheSize(4)

        setupTools()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!dirty && hasAnnots().not()) {
                    finish()
                    return
                }
                MaterialAlertDialogBuilder(this@EditorActivity)
                    .setTitle("¿Salir sin guardar?")
                    .setMessage("Tienes cambios que no has guardado.")
                    .setPositiveButton("Guardar") { _, _ -> askSave(thenFinish = true) }
                    .setNegativeButton("Salir") { _, _ -> finish() }
                    .setNeutralButton("Cancelar", null)
                    .show()
            }
        })

        val dir = File(cacheDir, "work").apply { mkdirs() }
        workFile = File(dir, "actual_${System.currentTimeMillis()}.pdf")
        loadInitial()
    }

    override fun onDestroy() {
        super.onDestroy()
        renderExec.execute {
            try { renderer?.close(); pfd?.close() } catch (e: Exception) { }
        }
        textExec.execute { try { textDoc?.close() } catch (e: Exception) { } }
        renderExec.shutdown()
        textExec.shutdown()
        ioExec.execute { workFile.delete() }
        ioExec.shutdown()
    }

    // ------------------------------------------------------------------
    // Abrir
    // ------------------------------------------------------------------

    private fun loadInitial() {
        val path = intent.getStringExtra(EXTRA_FILE)
        var uri: Uri? = intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) }
        if (uri == null && path == null) {
            uri = when (intent.action) {
                Intent.ACTION_VIEW -> intent.data
                Intent.ACTION_SEND -> {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                }
                else -> null
            }
        }
        if (path == null && uri == null) {
            toast("No se recibió ningún PDF")
            finish()
            return
        }
        showBusy("Abriendo…")
        val src: Uri? = uri
        ioExec.execute {
            try {
                if (path != null) {
                    File(path).copyTo(workFile, overwrite = true)
                    File(path).delete()
                    docName = intent.getStringExtra(EXTRA_NAME) ?: Store.newName()
                    dirty = true
                } else {
                    val u = src!!
                    Store.copyToFile(this, u, workFile)
                    sourceUri = u
                    docName = Store.displayName(this, u)
                    Store.addRecent(this, u, docName)
                }
                val needsPassword = try {
                    PDDocument.load(workFile).use { doc ->
                        if (doc.isEncrypted) {
                            // Solo tenía restricciones: se quitan para poder editar
                            doc.isAllSecurityToBeRemoved = true
                            val tmp = File(workFile.path + ".u")
                            doc.save(tmp)
                            tmp.copyTo(workFile, overwrite = true)
                            tmp.delete()
                        }
                    }
                    false
                } catch (e: InvalidPasswordException) {
                    true
                }
                runOnUiThread {
                    toolbar.title = docName
                    if (needsPassword) askPassword() else reload(firstTime = true)
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    hideBusy()
                    toast("No se pudo abrir: ${e.message ?: e.javaClass.simpleName}")
                    if (path == null && src != null) Store.removeRecent(this, src.toString())
                    finish()
                }
            }
        }
    }

    private fun askPassword() {
        hideBusy()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Contraseña"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("PDF protegido")
            .setMessage("Escribe la contraseña para abrirlo.")
            .setView(padded(input))
            .setCancelable(false)
            .setPositiveButton("Abrir") { _, _ ->
                showBusy("Abriendo…")
                ioExec.execute {
                    try {
                        val tmp = File(workFile.path + ".u")
                        PdfOps.unlock(workFile, input.text.toString(), tmp)
                        tmp.copyTo(workFile, overwrite = true)
                        tmp.delete()
                        runOnUiThread { reload(firstTime = true) }
                    } catch (e: Throwable) {
                        runOnUiThread {
                            toast("Contraseña incorrecta")
                            askPassword()
                        }
                    }
                }
            }
            .setNegativeButton("Cancelar") { _, _ -> finish() }
            .show()
    }

    /** Vuelve a abrir el archivo de trabajo (después de cualquier cambio). */
    private fun reload(firstTime: Boolean = false) {
        generation++
        val gen = generation
        bitmaps.evictAll()
        blocks.clear()
        loadingBlocks.clear()
        textExec.execute {
            try { textDoc?.close() } catch (e: Exception) { }
            textDoc = null
        }
        showBusy(null)
        renderExec.execute {
            try {
                renderer?.close()
                pfd?.close()
                val p = ParcelFileDescriptor.open(workFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val r = PdfRenderer(p)
                val list = (0 until r.pageCount).map { i ->
                    r.openPage(i).use { pg -> pg.height.toFloat() / pg.width.toFloat() }
                }
                pfd = p
                renderer = r
                runOnUiThread {
                    if (gen != generation) return@runOnUiThread
                    aspects = list
                    toolbar.subtitle = if (list.size == 1) "1 página" else "${list.size} páginas"
                    adapter.notifyDataSetChanged()
                    hideBusy()
                    if (firstTime && intent.getBooleanExtra(EXTRA_OCR, false)) offerOcr()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    hideBusy()
                    toast("Este PDF no se puede mostrar: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun renderPage(index: Int, width: Int): Bitmap? {
        val r = renderer ?: return null
        if (index >= r.pageCount) return null
        r.openPage(index).use { page ->
            val w = width.coerceIn(200, 2400)
            val h = (w * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        }
    }

    /** Pide una imagen de la página desde otro hilo (espera el resultado). */
    private fun renderBlocking(index: Int, width: Int): Bitmap? =
        renderExec.submit(Callable { renderPage(index, width) }).get()

    private fun requestBitmap(index: Int) {
        val gen = generation
        val width = (pages.width - pages.paddingLeft - pages.paddingRight).takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        renderExec.execute {
            if (gen != generation) return@execute
            val bmp = try { renderPage(index, minOf(width, 1600)) } catch (e: Throwable) { null } ?: return@execute
            runOnUiThread {
                if (gen != generation) return@runOnUiThread
                bitmaps.put(index, bmp)
                pageHolder(index)?.page?.bitmap = bmp
            }
        }
    }

    private fun pageHolder(index: Int) = pages.findViewHolderForAdapterPosition(index) as? PagesAdapter.PH

    private fun refreshPage(index: Int) {
        pageHolder(index)?.page?.invalidate()
    }

    // ------------------------------------------------------------------
    // Herramientas
    // ------------------------------------------------------------------

    private fun setupTools() {
        val group = findViewById<ChipGroup>(R.id.tools)
        group.check(R.id.toolPan)
        group.setOnCheckedStateChangeListener { _, ids ->
            val id = ids.firstOrNull() ?: R.id.toolPan
            tool = when (id) {
                R.id.toolEditText -> Tool.EDIT_TEXT
                R.id.toolPen -> Tool.PEN
                R.id.toolHighlight -> Tool.HIGHLIGHT
                R.id.toolText -> Tool.TEXT
                R.id.toolSign -> Tool.SIGN
                R.id.toolErase -> Tool.ERASE
                else -> Tool.PAN
            }
            when (tool) {
                Tool.PAN -> flash("Desliza para ver las páginas. Mantén presionada una página para organizarla.")
                Tool.EDIT_TEXT -> flash("Toca un recuadro azul para editar ese texto")
                Tool.PEN -> flash("Dibuja con el dedo. Usa «Mover» para desplazarte.")
                Tool.HIGHLIGHT -> {
                    if (penColor == COLORS[0] || penColor == COLORS[1]) setPenColor(COLORS[5])
                    flash("Pasa el dedo sobre el renglón que quieres resaltar")
                }
                Tool.TEXT -> flash("Toca donde quieres escribir")
                Tool.SIGN -> {
                    if (Store.signature(this) == null) showSignatureDialog()
                    else flash("Toca donde va la firma. Mantén presionado «Firma» para cambiarla.")
                }
                Tool.ERASE -> flash("Pasa el dedo sobre lo que quieres borrar")
            }
            for (i in 0 until adapter.itemCount) refreshPage(i)
        }
        findViewById<View>(R.id.toolSign).setOnLongClickListener {
            showSignatureDialog()
            true
        }
        colorChip.setOnClickListener { showStyleDialog() }
        findViewById<View>(R.id.btnUndo).setOnClickListener {
            val last = undo.removeLastOrNull()
            if (last == null) toast("Nada que deshacer") else last()
        }
        setPenColor(penColor)
    }

    private fun setPenColor(c: Int) {
        penColor = c
        colorChip.chipIconTint = ColorStateList.valueOf(c)
    }

    private fun showStyleDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        var dialog: androidx.appcompat.app.AlertDialog? = null
        COLORS.forEachIndexed { i, c ->
            val v = View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(c)
                    if (c == penColor) setStroke(dp(3), Color.parseColor("#9AA0AC"))
                }
                setOnClickListener {
                    setPenColor(c)
                    dialog?.dismiss()
                }
            }
            val lp = LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }
            (if (i < 4) row else row2).addView(v, lp)
        }
        box.addView(row)
        box.addView(row2)
        box.addView(TextView(this).apply {
            text = "Grosor"
            setPadding(0, dp(14), 0, dp(4))
        })
        val sizes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Fino" to 0.0025f, "Medio" to 0.004f, "Grueso" to 0.007f).forEach { (label, w) ->
            val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label
                if (w == penWidth) setBackgroundColor(Color.parseColor("#FBE3E1"))
                setOnClickListener {
                    penWidth = w
                    dialog?.dismiss()
                }
            }
            sizes.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        box.addView(sizes)
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Color y grosor")
            .setView(box)
            .setPositiveButton("Listo", null)
            .show()
    }

    // ------------------------------------------------------------------
    // PageHost: lo que pasa cuando tocas una página
    // ------------------------------------------------------------------

    override fun annotsOf(page: Int): List<Annot> = annots[page] ?: emptyList()

    override fun blocksOf(page: Int): List<TextBlock>? {
        val b = blocks[page]
        if (b == null && tool == Tool.EDIT_TEXT) loadBlocks(page)
        return b
    }

    private fun addAnnot(page: Int, a: Annot) {
        annots.getOrPut(page) { ArrayList() }.add(a)
        undo.add {
            annots[page]?.remove(a)
            refreshPage(page)
        }
        refreshPage(page)
    }

    override fun onStroke(page: Int, stroke: StrokeAnnot) = addAnnot(page, stroke)

    override fun onTap(page: Int, x: Float, y: Float) {
        when (tool) {
            Tool.TEXT -> {
                showTextDialog("Añadir texto", "", 0.03f, false, false) { text, size, bold ->
                    if (text.isBlank()) return@showTextDialog
                    val asp = aspects.getOrElse(page) { 1.414f }
                    addAnnot(page, TextAnnot(x, (y - size / asp / 2f).coerceAtLeast(0f), text, size, penColor, bold, 1.25f, (0.97f - x).coerceAtLeast(0.2f)))
                }
            }
            Tool.SIGN -> {
                val sig = Store.signature(this)
                if (sig == null) showSignatureDialog() else placeSignature(page, x, y, sig)
            }
            Tool.EDIT_TEXT -> tapEditText(page, x, y)
            else -> {}
        }
    }

    override fun onErase(page: Int, x: Float, y: Float) {
        val list = annots[page] ?: return
        val asp = aspects.getOrElse(page) { 1.414f }
        val tol = 0.025f
        val hit = list.lastOrNull { a ->
            when (a) {
                is StrokeAnnot -> a.points.any { Math.abs(it.x - x) < tol && Math.abs(it.y - y) * asp < tol }
                is EditAnnot -> a.box.contains(x, y)
                is TextAnnot -> {
                    val lines = a.text.split("\n")
                    val w = if (a.maxWidth > 0f) a.maxWidth else lines.maxOf { it.length } * a.size * 0.55f
                    val h = lines.size * a.size * a.lineHeight / asp
                    x in a.x..(a.x + w) && y in a.y..(a.y + h)
                }
            }
        } ?: return
        val idx = list.indexOf(hit)
        list.remove(hit)
        undo.add {
            annots.getOrPut(page) { ArrayList() }.add(idx.coerceAtMost(annots[page]!!.size), hit)
            refreshPage(page)
        }
        refreshPage(page)
    }

    override fun onLongPress(page: Int) {
        val n = aspects.size
        val opts = arrayOf("Rotar", "Mover arriba", "Mover abajo", "Duplicar", "Insertar página en blanco después", "Eliminar página")
        MaterialAlertDialogBuilder(this)
            .setTitle("Página ${page + 1} de $n")
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> runFileOp("Rotando…") { PdfOps.rotate(workFile, page) }
                    1 -> if (page > 0) runFileOp("Moviendo…") { PdfOps.move(workFile, page, page - 1) }
                    2 -> if (page < n - 1) runFileOp("Moviendo…") { PdfOps.move(workFile, page, page + 1) }
                    3 -> runFileOp("Duplicando…") { PdfOps.duplicate(workFile, page) }
                    4 -> runFileOp("Agregando…") { PdfOps.addBlank(workFile, page) }
                    5 -> {
                        if (n <= 1) {
                            toast("El PDF debe tener al menos una página")
                        } else {
                            MaterialAlertDialogBuilder(this)
                                .setTitle("¿Eliminar la página ${page + 1}?")
                                .setPositiveButton("Eliminar") { _, _ -> runFileOp("Eliminando…") { PdfOps.delete(workFile, page) } }
                                .setNegativeButton("Cancelar", null)
                                .show()
                        }
                    }
                }
            }
            .show()
    }

    // ------------------------------------------------------------------
    // Editar texto existente
    // ------------------------------------------------------------------

    private fun loadBlocks(page: Int) {
        if (!loadingBlocks.add(page)) return
        val gen = generation
        textExec.execute {
            var result: List<TextBlock> = emptyList()
            try {
                if (gen != generation) return@execute
                val doc = textDoc ?: PDDocument.load(workFile).also { textDoc = it }
                result = TextFinder.fromPdf(doc, page)
                if (result.sumOf { it.text.length } < 5) {
                    // Página escaneada o foto: reconocer el texto con OCR
                    runOnUiThread { flash("Reconociendo el texto de la página ${page + 1}…") }
                    val bmp = renderBlocking(page, 1800)
                    if (bmp != null) {
                        result = TextFinder.ocr(bmp).first
                        bmp.recycle()
                    }
                }
            } catch (e: Throwable) {
                result = emptyList()
            }
            val finalResult = result
            runOnUiThread {
                if (gen != generation) return@runOnUiThread
                blocks[page] = finalResult
                loadingBlocks.remove(page)
                if (finalResult.isEmpty() && tool == Tool.EDIT_TEXT) flash("No se encontró texto en la página ${page + 1}")
                refreshPage(page)
            }
        }
    }

    private fun tapEditText(page: Int, x: Float, y: Float) {
        val list = annots[page]
        // ¿Tocaste un texto que ya editaste? Se vuelve a abrir
        val prev = list?.lastOrNull { it is EditAnnot && it.box.contains(x, y) } as? EditAnnot
        if (prev != null) {
            showTextDialog("Editar texto", prev.text.text, prev.text.size, prev.text.bold, true) { text, size, bold ->
                val t = prev.text
                val newAnnot = EditAnnot(prev.box, prev.bg, TextAnnot(t.x, t.y, text, size, t.color, bold, t.lineHeight, t.maxWidth), prev.original)
                replaceAnnot(page, prev, newAnnot)
            }
            return
        }
        val bl = blocks[page]
        if (bl == null) {
            flash("Leyendo el texto de la página…")
            loadBlocks(page)
            return
        }
        val block = bl.filter { RectF(it.box).apply { inset(-0.01f, -0.006f) }.contains(x, y) }
            .minByOrNull { it.box.width() * it.box.height() }
        if (block == null) {
            flash("Toca dentro de un recuadro azul")
            return
        }
        val preview = block.text.replace("\n", " ").let { if (it.length > 60) it.take(60) + "…" else it }
        MaterialAlertDialogBuilder(this)
            .setTitle(preview)
            .setItems(arrayOf("Editar texto", "Borrar este texto", "Copiar texto")) { _, which ->
                when (which) {
                    0 -> showTextDialog("Editar texto", block.text, block.size, block.bold, true) { text, size, bold ->
                        addAnnot(page, makeEdit(page, block, text, size, bold))
                    }
                    1 -> addAnnot(page, makeEdit(page, block, "", block.size, block.bold))
                    2 -> copyToClipboard(block.text)
                }
            }
            .show()
    }

    private fun makeEdit(page: Int, b: TextBlock, text: String, size: Float, bold: Boolean): EditAnnot {
        val bmp = bitmaps.get(page)
        val (bg, fg) = if (bmp != null && !bmp.isRecycled) TextFinder.sampleColors(bmp, b.box) else Pair(Color.WHITE, Color.BLACK)
        val asp = aspects.getOrElse(page) { 1.414f }
        val x = b.box.left + 0.15f * b.size
        val y = b.firstBaseline - b.size / asp
        val maxW = (b.box.width() - 0.15f * b.size) * 1.08f
        return EditAnnot(RectF(b.box), bg, TextAnnot(x, y, text, size, fg, bold, b.lineHeight, maxW), b.text)
    }

    private fun replaceAnnot(page: Int, old: Annot, new: Annot) {
        val list = annots.getOrPut(page) { ArrayList() }
        val idx = list.indexOf(old)
        if (idx >= 0) list[idx] = new else list.add(new)
        undo.add {
            val l = annots[page]
            if (l != null) {
                val i = l.indexOf(new)
                if (i >= 0) l[i] = old
            }
            refreshPage(page)
        }
        refreshPage(page)
    }

    /** Ventana para escribir o corregir un texto, con tamaño y negrita. */
    private fun showTextDialog(
        title: String, initial: String, size: Float, bold: Boolean, isEdit: Boolean,
        onApply: (String, Float, Boolean) -> Unit
    ) {
        var curSize = size
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), 0)
        }
        val input = EditText(this).apply {
            setText(initial)
            setSelection(text.length)
            minLines = 2
            maxLines = 10
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        box.addView(input)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val label = TextView(this)
        fun updateLabel() {
            label.text = "Tamaño ${"%.1f".format(curSize * 595f)}"
        }
        updateLabel()
        val minus = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "A−"
            setOnClickListener { curSize = (curSize / 1.1f).coerceAtLeast(0.006f); updateLabel() }
        }
        val plus = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "A+"
            setOnClickListener { curSize = (curSize * 1.1f).coerceAtMost(0.2f); updateLabel() }
        }
        val boldBox = CheckBox(this).apply {
            text = "Negrita"
            isChecked = bold
        }
        row.addView(minus)
        row.addView(plus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
        row.addView(boldBox)
        box.addView(row)
        if (isEdit) {
            box.addView(TextView(this).apply {
                text = "El texto original se tapa con el color del fondo y se escribe el nuevo en su lugar."
                setTextColor(Color.parseColor("#646B7A"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(8), 0, 0)
            })
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Aplicar") { _, _ -> onApply(input.text.toString(), curSize, boldBox.isChecked) }
            .setNegativeButton("Cancelar", null)
            .show()
        input.requestFocus()
    }

    // ------------------------------------------------------------------
    // Firma
    // ------------------------------------------------------------------

    private fun showSignatureDialog() {
        val pad = SignaturePad(this)
        val frame = FrameLayout(this).apply { setPadding(dp(16), dp(8), dp(16), 0) }
        frame.addView(pad, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200)))
        val d = MaterialAlertDialogBuilder(this)
            .setTitle("Dibuja tu firma")
            .setView(frame)
            .setPositiveButton("Guardar firma", null)
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Borrar", null)
            .show()
        d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { pad.clear() }
        d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val sig = pad.toSignature()
            if (sig == null) {
                toast("Dibuja tu firma primero")
            } else {
                Store.saveSignature(this, sig)
                d.dismiss()
                flash("Firma guardada. Ahora toca donde quieres ponerla.")
            }
        }
    }

    private fun placeSignature(page: Int, x: Float, y: Float, sig: Signature) {
        val asp = aspects.getOrElse(page) { 1.414f }
        val w = 0.32f
        val h = w * sig.aspect / asp
        val left = (x - w / 2f).coerceIn(0f, 1f - w)
        val top = (y - h / 2f).coerceIn(0f, (1f - h).coerceAtLeast(0f))
        val color = if (penColor == COLORS[5]) COLORS[1] else penColor
        val added = sig.strokes.map { s ->
            StrokeAnnot(s.map { PointF(left + it.x * w, top + it.y * h) }.toMutableList(), color, 0.0035f, false)
        }
        val list = annots.getOrPut(page) { ArrayList() }
        list.addAll(added)
        undo.add {
            annots[page]?.removeAll(added.toSet())
            refreshPage(page)
        }
        refreshPage(page)
    }

    // ------------------------------------------------------------------
    // Menú
    // ------------------------------------------------------------------

    private fun onMenu(id: Int) {
        when (id) {
            R.id.m_save -> askSave(thenFinish = false)
            R.id.m_share -> withApplied("Preparando…") { runOnUiThread { Store.shareFile(this, workFile, docName) } }
            R.id.m_ocr -> runOcrAll()
            R.id.m_copy_text -> showAllText()
            R.id.m_add_blank -> runFileOp("Agregando página…") { PdfOps.addBlank(workFile, aspects.size - 1) }
            R.id.m_add_image -> pickImage.launch("image/*")
            R.id.m_add_pdf -> pickPdf.launch(arrayOf("application/pdf"))
            R.id.m_extract -> askExtract()
            R.id.m_protect -> askProtect()
            R.id.m_pages_help -> {
                findViewById<ChipGroup>(R.id.tools).check(R.id.toolPan)
                flash("Mantén presionada una página para rotarla, moverla, duplicarla o eliminarla")
            }
        }
    }

    private fun hasAnnots() = annots.values.any { it.isNotEmpty() }

    /** Copia las anotaciones de pantalla y las borra (ya quedarán dentro del PDF). */
    private fun takeAnnots(): Map<Int, List<Annot>> {
        val snap = annots.mapValues { it.value.toList() }.filterValues { it.isNotEmpty() }
        if (snap.isNotEmpty()) dirty = true
        annots.clear()
        undo.clear()
        return snap
    }

    /** Ejecuta un cambio sobre el archivo y luego recarga las páginas. */
    private fun runFileOp(msg: String, block: () -> Unit) {
        val snap = takeAnnots()
        showBusy(msg)
        ioExec.execute {
            try {
                PdfOps.applyAnnotations(workFile, snap)
                block()
                runOnUiThread {
                    dirty = true
                    reload()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    hideBusy()
                    toast("Error: ${e.message ?: e.javaClass.simpleName}")
                    reload()
                }
            }
        }
    }

    /** Mete las anotaciones al PDF (sin cambiar páginas) y luego hace algo. */
    private fun withApplied(msg: String, block: () -> Unit) {
        val snap = takeAnnots()
        showBusy(msg)
        ioExec.execute {
            try {
                PdfOps.applyAnnotations(workFile, snap)
                block()
                runOnUiThread {
                    hideBusy()
                    if (snap.isNotEmpty()) reload()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    hideBusy()
                    toast("Error: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun askSave(thenFinish: Boolean) {
        val base = docName.removeSuffix(".pdf").removeSuffix(".PDF")
        val input = EditText(this).apply {
            setText(base)
            setSelectAllOnFocus(true)
            isSingleLine = true
        }
        val b = MaterialAlertDialogBuilder(this)
            .setTitle("Guardar PDF")
            .setMessage("Se guarda en Descargas › GigaPDF")
            .setView(padded(input))
            .setPositiveButton("Guardar copia") { _, _ -> save(input.text.toString(), replace = false, thenFinish = thenFinish) }
            .setNegativeButton("Cancelar", null)
        if (sourceUri != null) {
            b.setNeutralButton("Reemplazar original") { _, _ -> save(docName, replace = true, thenFinish = thenFinish) }
        }
        b.show()
    }

    private fun save(name: String, replace: Boolean, thenFinish: Boolean) {
        withApplied("Guardando…") {
            val clean = Store.cleanName(name)
            var savedUri: Uri? = null
            var note = ""
            if (replace && sourceUri != null) {
                try {
                    Store.overwrite(this, workFile, sourceUri!!)
                    savedUri = sourceUri
                    note = "Original actualizado"
                } catch (e: Exception) {
                    note = "No se pudo reemplazar el original; se guardó una copia en Descargas › GigaPDF"
                }
            }
            if (savedUri == null) {
                savedUri = Store.saveToDownloads(this, workFile, clean)
                if (note.isEmpty()) note = "Guardado en Descargas › GigaPDF › $clean"
            }
            val finalName = if (replace && note == "Original actualizado") docName else clean
            Store.addRecent(this, savedUri!!, finalName)
            runOnUiThread {
                sourceUri = savedUri
                docName = finalName
                toolbar.title = finalName
                dirty = false
                toast(note)
                if (thenFinish) finish()
            }
        }
    }

    private fun askExtract() {
        val input = EditText(this).apply {
            hint = "Ejemplo: 1-3, 5"
            isSingleLine = true
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Extraer páginas")
            .setMessage("Escribe las páginas que quieres sacar a un PDF nuevo (de ${aspects.size}).")
            .setView(padded(input))
            .setPositiveButton("Extraer") { _, _ ->
                val list = parsePages(input.text.toString(), aspects.size)
                if (list.isEmpty()) {
                    toast("No entendí las páginas")
                    return@setPositiveButton
                }
                withApplied("Extrayendo…") {
                    val out = File(cacheDir, "extraido.pdf")
                    PdfOps.extract(workFile, list, out)
                    val name = Store.cleanName(docName.removeSuffix(".pdf") + "_paginas")
                    val uri = Store.saveToDownloads(this, out, name)
                    Store.addRecent(this, uri, name)
                    out.delete()
                    runOnUiThread { toast("Guardado en Descargas › GigaPDF › $name") }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun parsePages(s: String, n: Int): List<Int> {
        val out = LinkedHashSet<Int>()
        for (part in s.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }) {
            val r = part.split('-')
            try {
                if (r.size == 2) {
                    val a = r[0].trim().toInt()
                    val b = r[1].trim().toInt()
                    for (i in minOf(a, b)..maxOf(a, b)) if (i in 1..n) out.add(i - 1)
                } else {
                    val a = part.toInt()
                    if (a in 1..n) out.add(a - 1)
                }
            } catch (e: NumberFormatException) {
            }
        }
        return out.toList()
    }

    private fun askProtect() {
        val p1 = EditText(this).apply {
            hint = "Contraseña"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val p2 = EditText(this).apply {
            hint = "Repite la contraseña"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), 0)
            addView(p1)
            addView(p2)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Proteger con contraseña")
            .setMessage("Se guarda una copia protegida en Descargas › GigaPDF. Nadie podrá abrirla sin la contraseña.")
            .setView(box)
            .setPositiveButton("Proteger") { _, _ ->
                val pw = p1.text.toString()
                if (pw.length < 4) {
                    toast("Usa al menos 4 caracteres")
                    return@setPositiveButton
                }
                if (pw != p2.text.toString()) {
                    toast("Las contraseñas no coinciden")
                    return@setPositiveButton
                }
                withApplied("Protegiendo…") {
                    val out = File(cacheDir, "protegido.pdf")
                    PdfOps.protect(workFile, pw, out)
                    val name = Store.cleanName(docName.removeSuffix(".pdf") + "_protegido")
                    val uri = Store.saveToDownloads(this, out, name)
                    Store.addRecent(this, uri, name)
                    out.delete()
                    runOnUiThread { toast("Guardado: $name") }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ------------------------------------------------------------------
    // OCR de todo el documento
    // ------------------------------------------------------------------

    private fun offerOcr() {
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Reconocer el texto?")
            .setMessage("GigaPDF puede leer el texto del escaneo para que puedas buscarlo, copiarlo y editarlo.")
            .setPositiveButton("Sí, reconocer") { _, _ -> runOcrAll() }
            .setNegativeButton("Ahora no", null)
            .show()
    }

    private fun runOcrAll() {
        val snap = takeAnnots()
        val total = aspects.size
        showBusy("Reconociendo texto…")
        ioExec.execute {
            try {
                PdfOps.applyAnnotations(workFile, snap)
                // Páginas que ya tienen texto real no se tocan
                val hasText = PDDocument.load(workFile).use { doc ->
                    (0 until doc.numberOfPages).map { i -> TextFinder.fromPdf(doc, i).sumOf { it.text.length } >= 5 }
                }
                val found = HashMap<Int, List<OcrLine>>()
                var count = 0
                for (i in 0 until total) {
                    if (hasText.getOrElse(i) { true }) continue
                    runOnUiThread { showBusy("Reconociendo página ${i + 1} de $total…") }
                    val bmp = renderBlocking(i, 1800) ?: continue
                    val lines = TextFinder.ocr(bmp).second
                    bmp.recycle()
                    found[i] = lines
                    count += lines.size
                }
                if (found.isNotEmpty()) PdfOps.addTextLayer(workFile, found)
                runOnUiThread {
                    when {
                        hasText.all { it } -> toast("Este PDF ya tiene texto: puedes editarlo con «Editar texto»")
                        count == 0 -> toast("No se encontró texto en las páginas escaneadas")
                        else -> {
                            dirty = true
                            toast("Listo: ahora el texto se puede buscar, copiar y editar")
                        }
                    }
                    reload()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    hideBusy()
                    toast("Error al reconocer: ${e.message ?: e.javaClass.simpleName}")
                    reload()
                }
            }
        }
    }

    private fun showAllText() {
        withApplied("Leyendo texto…") {
            val text = PdfOps.allText(workFile).trim()
            runOnUiThread {
                if (text.isEmpty()) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Sin texto")
                        .setMessage("Este PDF parece una imagen escaneada. Usa «Reconocer texto (OCR)» primero.")
                        .setPositiveButton("Reconocer ahora") { _, _ -> runOcrAll() }
                        .setNegativeButton("Cerrar", null)
                        .show()
                    return@runOnUiThread
                }
                val tv = TextView(this).apply {
                    this.text = text
                    setTextIsSelectable(true)
                    setPadding(dp(20), dp(8), dp(20), dp(8))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                }
                val scroll = ScrollView(this).apply { addView(tv) }
                MaterialAlertDialogBuilder(this)
                    .setTitle("Texto del PDF")
                    .setView(scroll)
                    .setPositiveButton("Copiar todo") { _, _ -> copyToClipboard(text) }
                    .setNeutralButton("Compartir") { _, _ ->
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }, "Compartir texto"))
                    }
                    .setNegativeButton("Cerrar", null)
                    .show()
            }
        }
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("texto", text))
        toast("Texto copiado")
    }

    private fun showBusy(msg: String?) {
        busy.visibility = View.VISIBLE
        if (msg != null) {
            hint.text = msg
            hint.visibility = View.VISIBLE
            hint.removeCallbacks(hideHint)
        }
    }

    private fun hideBusy() {
        busy.visibility = View.GONE
        hint.removeCallbacks(hideHint)
        hint.postDelayed(hideHint, 600)
    }

    private val hideHint = Runnable { hint.visibility = View.GONE }

    private fun flash(msg: String) {
        hint.text = msg
        hint.visibility = View.VISIBLE
        hint.removeCallbacks(hideHint)
        hint.postDelayed(hideHint, 3200)
    }

    private fun padded(v: View): View = FrameLayout(this).apply {
        setPadding(dp(20), dp(6), dp(20), 0)
        addView(v)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------------
    // Lista de páginas
    // ------------------------------------------------------------------

    inner class PagesAdapter : RecyclerView.Adapter<PagesAdapter.PH>() {
        inner class PH(v: View) : RecyclerView.ViewHolder(v) {
            val page: PageView = v.findViewById(R.id.page)
            val label: TextView = v.findViewById(R.id.label)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_page, parent, false)
            return PH(v).also { it.page.host = this@EditorActivity }
        }

        override fun getItemCount() = aspects.size

        override fun onBindViewHolder(h: PH, position: Int) {
            h.page.pageIndex = position
            h.page.aspect = aspects[position]
            h.label.text = "${position + 1} / ${aspects.size}"
            val bmp = bitmaps.get(position)
            h.page.bitmap = bmp
            if (bmp == null) requestBitmap(position)
            h.page.requestLayout()
            if (tool == Tool.EDIT_TEXT && blocks[position] == null) loadBlocks(position)
        }
    }
}
