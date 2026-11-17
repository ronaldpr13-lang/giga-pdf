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
        const val EXTRA_EDIT = "edit"
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
    private class Action(val undo: () -> Unit, val redo: () -> Unit)
    private val undoStack = ArrayList<Action>()
    private val redoStack = ArrayList<Action>()
    private val blocks = HashMap<Int, List<TextBlock>>()
    private val loadingBlocks = HashSet<Int>()
    private var textDoc: PDDocument? = null

    private val bitmaps = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 5).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    override var tool = Tool.PAN
    override var penColor = COLORS[1]
    override var penWidth = 0.004f
    override var preview = false
    override var activeAnnot: Annot? = null

    private lateinit var toolbar: MaterialToolbar
    private lateinit var pages: RecyclerView
    private lateinit var busy: View
    private lateinit var hint: TextView
    private lateinit var toolBar: View
    private lateinit var exitPreview: View
    private lateinit var pageIndicator: TextView
    private val toolViews = HashMap<Tool, View>()
    private var colorDot: android.widget.ImageView? = null
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
        toolBar = findViewById(R.id.toolBar)
        exitPreview = findViewById(R.id.btnExitPreview)
        pageIndicator = findViewById(R.id.pageIndicator)
        exitPreview.setOnClickListener { togglePreview(false) }

        toolbar.inflateMenu(R.menu.editor)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        toolbar.setOnMenuItemClickListener { onMenu(it.itemId); true }

        pages.layoutManager = LinearLayoutManager(this)
        pages.adapter = adapter
        pages.setItemViewCacheSize(4)
        pages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (aspects.size < 2) return
                val lm = rv.layoutManager as LinearLayoutManager
                val first = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
                pageIndicator.text = "${first + 1} / ${aspects.size}"
                pageIndicator.visibility = View.VISIBLE
                pageIndicator.removeCallbacks(hideIndicator)
                pageIndicator.postDelayed(hideIndicator, 1500)
            }
        })

        setupTools()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (preview) {
                    togglePreview(false)
                    return
                }
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
        // Si abrieron un PDF con GigaPDF sin pasar por el inicio, también ven el aviso
        Terms.ensureAccepted(this) { loadInitial() }
    }

    override fun onDestroy() {
        super.onDestroy()
        renderExec.execute {
            try { renderer?.close(); pfd?.close() } catch (e: Exception) { }
            closePreviewRenderer()
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
                    updateUndoIcons()
                    adapter.notifyDataSetChanged()
                    hideBusy()
                    if (firstTime && intent.getBooleanExtra(EXTRA_OCR, false)) offerOcr()
                    if (firstTime && intent.getBooleanExtra(EXTRA_EDIT, false)) {
                        selectTool(Tool.EDIT_TEXT, quiet = true)
                        flash("Leyendo el texto de la foto… luego toca un recuadro para editarlo")
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    hideBusy()
                    toast("Este PDF no se puede mostrar: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun renderPage(index: Int, width: Int, from: PdfRenderer? = renderer): Bitmap? {
        val r = from ?: return null
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

    private val pendingRender = HashSet<Int>()
    private val pendingPreview = HashSet<Int>()

    private fun pageWidthPx(): Int =
        minOf((pages.width - pages.paddingLeft - pages.paddingRight).takeIf { it > 0 } ?: resources.displayMetrics.widthPixels, 1600)

    private fun requestBitmap(index: Int) {
        if (!pendingRender.add(index)) return
        val gen = generation
        val width = pageWidthPx()
        renderExec.execute {
            val bmp = if (gen != generation) null else try { renderPage(index, width) } catch (e: Throwable) { null }
            runOnUiThread {
                pendingRender.remove(index)
                if (gen != generation) {
                    refreshPage(index)  // se volverá a pedir con el archivo nuevo
                    return@runOnUiThread
                }
                if (bmp == null) return@runOnUiThread
                bitmaps.put(index, bmp)
                refreshPage(index)
            }
        }
    }

    // ---------- Vista previa real: el PDF con los cambios ya aplicados ----------

    private var previewRenderer: PdfRenderer? = null
    private var previewPfd: ParcelFileDescriptor? = null
    private var previewGen = 0
    private val previewBitmaps = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    override fun bitmapOf(page: Int): Bitmap? {
        if (preview) {
            val pb = previewBitmaps.get(page)
            if (pb != null) return pb
            if (previewRenderer != null) requestPreviewBitmap(page)
            return bitmaps.get(page)
        }
        val b = bitmaps.get(page)
        if (b == null) requestBitmap(page)
        return b
    }

    private fun requestPreviewBitmap(index: Int) {
        if (!pendingPreview.add(index)) return
        val gen = previewGen
        val width = pageWidthPx()
        renderExec.execute {
            val bmp = if (gen != previewGen) null else try { renderPage(index, width, previewRenderer) } catch (e: Throwable) { null }
            runOnUiThread {
                pendingPreview.remove(index)
                if (gen != previewGen || bmp == null || !preview) return@runOnUiThread
                previewBitmaps.put(index, bmp)
                refreshPage(index)
            }
        }
    }

    private fun buildPreview() {
        previewGen++
        val gen = previewGen
        previewBitmaps.evictAll()
        pendingPreview.clear()
        val snap = annots.mapValues { it.value.toList() }.filterValues { it.isNotEmpty() }
        if (snap.isEmpty()) return  // sin cambios: se ve el PDF tal cual
        showBusy("Preparando vista previa…")
        val file = File(cacheDir, "vista_previa.pdf")
        ioExec.execute {
            try {
                workFile.copyTo(file, overwrite = true)
                PdfOps.applyAnnotations(file, snap)
                val ok = renderExec.submit(Callable {
                    closePreviewRenderer()
                    if (gen != previewGen) return@Callable false
                    val p = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    previewPfd = p
                    previewRenderer = PdfRenderer(p)
                    true
                }).get()
                runOnUiThread {
                    hideBusy()
                    if (ok && gen == previewGen) refreshAll()
                }
            } catch (e: Throwable) {
                runOnUiThread { hideBusy() }
            }
        }
    }

    /** Se llama en el hilo de dibujo. */
    private fun closePreviewRenderer() {
        try { previewRenderer?.close() } catch (e: Exception) { }
        try { previewPfd?.close() } catch (e: Exception) { }
        previewRenderer = null
        previewPfd = null
    }

    private fun pageHolder(index: Int) = pages.findViewHolderForAdapterPosition(index) as? PagesAdapter.PH

    private fun refreshPage(index: Int) {
        pageHolder(index)?.page?.invalidate()
    }

    // ------------------------------------------------------------------
    // Herramientas
    // ------------------------------------------------------------------

    private val hideIndicator = Runnable { pageIndicator.visibility = View.GONE }

    /** Barra de herramientas de abajo: solo iconos con su nombre pequeño. */
    private fun setupTools() {
        val row = findViewById<LinearLayout>(R.id.toolRow)
        val items = listOf(
            Triple(Tool.PAN, R.drawable.ic_pan, "Mover"),
            Triple(Tool.EDIT_TEXT, R.drawable.ic_edit_text, "Editar"),
            Triple(Tool.PEN, R.drawable.ic_pen, "Lápiz"),
            Triple(Tool.HIGHLIGHT, R.drawable.ic_highlight, "Resaltar"),
            Triple(Tool.TEXT, R.drawable.ic_text, "Texto"),
            Triple(Tool.SIGN, R.drawable.ic_sign, "Firma"),
            Triple(Tool.ERASE, R.drawable.ic_eraser, "Borrar")
        )
        for ((t, icon, label) in items) {
            val v = toolItem(icon, label)
            v.setOnClickListener { selectTool(t) }
            if (t == Tool.SIGN) v.setOnLongClickListener { showSignatureDialog(); true }
            toolViews[t] = v
            row.addView(v)
        }
        row.addView(View(this).apply { setBackgroundColor(Color.parseColor("#E2E5EC")) },
            LinearLayout.LayoutParams(dp(1), dp(30)).apply { setMargins(dp(4), 0, dp(4), 0) })
        val color = toolItem(R.drawable.ic_circle, "Color")
        colorDot = color.findViewWithTag("icon")
        color.setOnClickListener { showStyleDialog() }
        row.addView(color)
        selectTool(Tool.PAN, quiet = true)
        applyPenColor(penColor)
    }

    private fun toolItem(icon: Int, label: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
            background = with(TypedValue()) {
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, this, true)
                androidx.core.content.ContextCompat.getDrawable(this@EditorActivity, resourceId)
            }
        }
        val img = android.widget.ImageView(this).apply {
            tag = "icon"
            setImageResource(icon)
            setPadding(dp(14), dp(5), dp(14), dp(5))
        }
        box.addView(img, LinearLayout.LayoutParams(dp(54), dp(32)))
        box.addView(TextView(this).apply {
            tag = "label"
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, 0)
        })
        box.layoutParams = LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT)
        return box
    }

    private fun selectTool(t: Tool, quiet: Boolean = false) {
        tool = t
        val primary = Color.parseColor("#D93A3A")
        val normal = Color.parseColor("#3A3F4B")
        for ((key, v) in toolViews) {
            val on = key == t
            val img = v.findViewWithTag<android.widget.ImageView>("icon")
            val lbl = v.findViewWithTag<TextView>("label")
            img.background = if (on) android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#FBE3E1"))
            } else null
            img.imageTintList = ColorStateList.valueOf(if (on) primary else normal)
            lbl.setTextColor(if (on) primary else Color.parseColor("#646B7A"))
            lbl.typeface = if (on) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        }
        if (!quiet) when (t) {
            Tool.PAN -> flash("Desliza para ver las páginas. Mantén presionada una página para organizarla.")
            Tool.EDIT_TEXT -> flash("Toca un texto para editarlo. Toca un espacio vacío para ver la vista previa.")
            Tool.PEN -> flash("Dibuja con el dedo. Usa «Mover» para desplazarte.")
            Tool.HIGHLIGHT -> {
                if (penColor == COLORS[0] || penColor == COLORS[1]) applyPenColor(COLORS[5])
                flash("Pasa el dedo sobre el renglón que quieres resaltar")
            }
            Tool.TEXT -> flash("Toca donde quieres escribir")
            Tool.SIGN -> {
                if (Store.signature(this) == null) showSignatureDialog()
                else flash("Toca donde va la firma. Mantén presionado «Firma» para cambiarla.")
            }
            Tool.ERASE -> flash("Pasa el dedo sobre lo que quieres borrar")
        }
        refreshAll()
    }

    private fun refreshAll() {
        for (i in 0 until adapter.itemCount) refreshPage(i)
    }

    /** Vista previa: esconde recuadros y herramientas para ver cómo queda. */
    private fun togglePreview(on: Boolean) {
        if (preview == on) return
        preview = on
        toolBar.visibility = if (on) View.GONE else View.VISIBLE
        exitPreview.visibility = if (on) View.VISIBLE else View.GONE
        toolbar.menu.findItem(R.id.m_preview)?.setIcon(if (on) R.drawable.ic_close else R.drawable.ic_eye)
        if (on) {
            flash("Vista previa: así quedará tu PDF")
            buildPreview()
        } else {
            hint.visibility = View.GONE
            previewGen++
            previewBitmaps.evictAll()
            renderExec.execute { closePreviewRenderer() }
        }
        refreshAll()
    }

    private fun applyPenColor(c: Int) {
        penColor = c
        colorDot?.imageTintList = ColorStateList.valueOf(c)
    }

    private fun push(a: Action) {
        undoStack.add(a)
        redoStack.clear()
        updateUndoIcons()
    }

    private fun doUndo() {
        val a = undoStack.removeLastOrNull()
        if (a == null) {
            toast("Nada que deshacer")
            return
        }
        a.undo()
        redoStack.add(a)
        updateUndoIcons()
    }

    private fun doRedo() {
        val a = redoStack.removeLastOrNull()
        if (a == null) {
            toast("Nada que rehacer")
            return
        }
        a.redo()
        undoStack.add(a)
        updateUndoIcons()
    }

    private fun updateUndoIcons() {
        toolbar.menu.findItem(R.id.m_undo)?.icon?.alpha = if (undoStack.isEmpty()) 90 else 255
        toolbar.menu.findItem(R.id.m_redo)?.icon?.alpha = if (redoStack.isEmpty()) 90 else 255
        val n = aspects.size
        val pagesTxt = if (n == 1) "1 página" else "$n páginas"
        toolbar.subtitle = if (dirty || hasAnnots()) "$pagesTxt · sin guardar" else pagesTxt
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
                    applyPenColor(c)
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
        push(Action(
            undo = { annots[page]?.remove(a); refreshPage(page) },
            redo = { annots.getOrPut(page) { ArrayList() }.add(a); refreshPage(page) }
        ))
        refreshPage(page)
    }

    override fun onStroke(page: Int, stroke: StrokeAnnot) = addAnnot(page, stroke)

    override fun onTap(page: Int, x: Float, y: Float) {
        if (preview) {
            togglePreview(false)
            return
        }
        when (tool) {
            Tool.TEXT -> {
                val asp = aspects.getOrElse(page) { 1.414f }
                val existing = annots[page]?.lastOrNull { a ->
                    a is TextAnnot && x in a.x..(a.x + (if (a.maxWidth > 0f) a.maxWidth else 0.3f)) &&
                        y in a.y..(a.y + a.text.split("\n").size * a.size * a.lineHeight / asp)
                } as? TextAnnot
                if (existing != null) {
                    showEditSheet(page, existing, isNew = false, title = "Editar texto", original = null)
                    return
                }
                val size = 0.03f
                val t = TextAnnot(x, (y - size / asp / 2f).coerceAtLeast(0f), "", size, penColor, false, 1.25f, (0.97f - x).coerceAtLeast(0.2f))
                showEditSheet(page, t, isNew = true, title = "Añadir texto", original = null)
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
        push(Action(
            undo = {
                val l = annots.getOrPut(page) { ArrayList() }
                l.add(idx.coerceAtMost(l.size), hit)
                refreshPage(page)
            },
            redo = { annots[page]?.remove(hit); refreshPage(page) }
        ))
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
        // ¿Tocaste un texto que ya editaste o añadiste? Se vuelve a abrir
        val prev = list?.lastOrNull { it is EditAnnot && RectF(it.box).apply { inset(-0.005f, -0.004f) }.contains(x, y) } as? EditAnnot
        if (prev != null) {
            showEditSheet(page, prev.text, isNew = false, title = "Editar texto", original = prev)
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
            // Tocaste un espacio vacío: vista previa
            togglePreview(true)
            return
        }
        val edit = makeEdit(page, block)
        showEditSheet(page, edit.text, isNew = true, title = "Editar texto", original = edit)
    }

    private fun makeEdit(page: Int, b: TextBlock): EditAnnot {
        val bmp = bitmaps.get(page)
        val (bg, fg) = if (bmp != null && !bmp.isRecycled) TextFinder.sampleColors(bmp, b.box) else Pair(Color.WHITE, Color.BLACK)
        val asp = aspects.getOrElse(page) { 1.414f }
        val x = if (b.textLeft.isNaN()) b.box.left + 0.15f * b.size else b.textLeft
        val y = b.firstBaseline - b.size / asp
        // Un renglón suelto crece hacia la derecha; un párrafo se acomoda al ancho original
        val maxW = if (b.text.contains('\n')) (b.box.width() - 0.15f * b.size) * 1.08f else 0f
        // Misma letra: la fuente original del PDF si existe; en fotos, la más parecida
        val font = if (!b.fromOcr && b.fontName != null) "orig:" + b.fontName else familyOf(b.fontName)
        return EditAnnot(
            RectF(b.box), bg,
            TextAnnot(x, y, b.text, b.size, fg, b.bold, b.lineHeight, maxW, font, b.bold),
            b.text, fromImage = b.fromOcr
        )
    }

    /**
     * Panel de edición abajo. Mientras escribes, la página se actualiza en vivo
     * (vista previa inmediata). Con ✓ se aplica; si lo cierras, se descarta.
     *
     * [t] es el texto que se edita; [original] es la edición que lo contiene
     * (null cuando es un texto añadido con la herramienta Texto).
     */
    private fun showEditSheet(page: Int, t: TextAnnot, isNew: Boolean, title: String, original: EditAnnot?) {
        val target: Annot = original ?: t
        val list = annots.getOrPut(page) { ArrayList() }
        if (isNew) list.add(target)
        // Valores antes de editar (para cancelar o deshacer)
        val before = arrayOf<Any>(t.text, t.size, t.bold, t.color, t.font)
        var applied = false
        activeAnnot = target

        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_edit, null)
        dialog.setContentView(v)
        dialog.window?.setDimAmount(0f)
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        val input = v.findViewById<EditText>(R.id.sInput)
        val sizeLbl = v.findViewById<TextView>(R.id.sSize)
        val boldBtn = v.findViewById<android.widget.ImageButton>(R.id.sBold)
        val colorBtn = v.findViewById<android.widget.ImageButton>(R.id.sColor)
        val fontBtn = v.findViewById<TextView>(R.id.sFont)
        // Letras disponibles: la original (si la hay) y tres comunes
        val fontChoices = ArrayList<String>()
        if (t.font.startsWith("orig:")) fontChoices.add(t.font)
        fontChoices.addAll(listOf("sans", "serif", "mono"))
        v.findViewById<TextView>(R.id.sheetTitle).text = title
        v.findViewById<View>(R.id.sDelete).visibility = if (original != null) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.sCopy).visibility = if (original != null) View.VISIBLE else View.GONE

        fun paintState() {
            sizeLbl.text = "%.0f".format(t.size * 595f)
            boldBtn.background = androidx.core.content.ContextCompat.getDrawable(this, if (t.bold) R.drawable.bg_round_primary else R.drawable.bg_round_soft)
            boldBtn.imageTintList = ColorStateList.valueOf(if (t.bold) Color.WHITE else Color.parseColor("#1B1E26"))
            colorBtn.imageTintList = ColorStateList.valueOf(t.color)
            fontBtn.text = fontLabel(t.font)
            fontBtn.typeface = when (t.family) {
                "serif" -> android.graphics.Typeface.SERIF
                "mono" -> android.graphics.Typeface.MONOSPACE
                else -> android.graphics.Typeface.SANS_SERIF
            }
            refreshPage(page)
        }

        input.setText(t.text)
        input.setSelection(input.text.length)
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                t.text = s?.toString() ?: ""
                refreshPage(page)
            }
        })
        v.findViewById<View>(R.id.sSmaller).setOnClickListener { t.size = (t.size / 1.08f).coerceAtLeast(0.006f); paintState() }
        v.findViewById<View>(R.id.sBigger).setOnClickListener { t.size = (t.size * 1.08f).coerceAtMost(0.2f); paintState() }
        boldBtn.setOnClickListener { t.bold = !t.bold; paintState() }
        colorBtn.setOnClickListener {
            val i = COLORS.indexOfFirst { it == t.color }
            t.color = if (i < 0) COLORS[0] else COLORS[(i + 1) % COLORS.size]
            paintState()
        }
        fontBtn.setOnClickListener {
            val i = fontChoices.indexOf(t.font)
            t.font = fontChoices[(i + 1) % fontChoices.size]
            paintState()
        }
        v.findViewById<View>(R.id.sCopy).setOnClickListener { copyToClipboard(original?.original ?: t.text) }
        v.findViewById<View>(R.id.sDelete).setOnClickListener {
            t.text = ""
            applied = true
            dialog.dismiss()
        }
        v.findViewById<View>(R.id.sApply).setOnClickListener {
            applied = true
            dialog.dismiss()
        }
        dialog.setOnDismissListener {
            activeAnnot = null
            val after = arrayOf<Any>(t.text, t.size, t.bold, t.color, t.font)
            fun set(vals: Array<Any>) {
                t.text = vals[0] as String
                t.size = vals[1] as Float
                t.bold = vals[2] as Boolean
                t.color = vals[3] as Int
                t.font = vals[4] as String
            }
            val changed = !after.contentEquals(before)
            when {
                !applied || (isNew && original == null && t.text.isBlank()) || (!isNew && !changed) || (isNew && original != null && !changed) -> {
                    // Se descarta: todo vuelve como estaba
                    set(before)
                    if (isNew) annots[page]?.remove(target)
                }
                isNew -> push(Action(
                    undo = { annots[page]?.remove(target); refreshPage(page) },
                    redo = { annots.getOrPut(page) { ArrayList() }.add(target); refreshPage(page) }
                ))
                else -> push(Action(
                    undo = { set(before); refreshPage(page) },
                    redo = { set(after); refreshPage(page) }
                ))
            }
            refreshPage(page)
            updateUndoIcons()
        }
        paintState()
        dialog.show()
        input.requestFocus()
        scrollToShow(page, t.y)
    }

    /** Sube la página para que el texto que editas quede visible sobre el panel. */
    private fun scrollToShow(page: Int, yNorm: Float) {
        val holder = pageHolder(page) ?: return
        val pageView = holder.page
        val targetTop = holder.itemView.top + pageView.top + (yNorm * pageView.height).toInt()
        val desired = dp(70)
        pages.post { pages.smoothScrollBy(0, targetTop - desired) }
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
        push(Action(
            undo = { annots[page]?.removeAll(added.toSet()); refreshPage(page) },
            redo = { annots.getOrPut(page) { ArrayList() }.addAll(added); refreshPage(page) }
        ))
        refreshPage(page)
    }

    // ------------------------------------------------------------------
    // Menú
    // ------------------------------------------------------------------

    private fun onMenu(id: Int) {
        when (id) {
            R.id.m_undo -> doUndo()
            R.id.m_redo -> doRedo()
            R.id.m_preview -> togglePreview(!preview)
            R.id.m_save -> askSave(thenFinish = false)
            R.id.m_share -> withApplied("Preparando…") { runOnUiThread { Store.shareFile(this, workFile, docName) } }
            R.id.m_ocr -> runOcrAll()
            R.id.m_copy_text -> showAllText()
            R.id.m_add_blank -> runFileOp("Agregando página…") { PdfOps.addBlank(workFile, aspects.size - 1) }
            R.id.m_add_image -> pickImage.launch("image/*")
            R.id.m_add_pdf -> pickPdf.launch(arrayOf("application/pdf"))
            R.id.m_extract -> askExtract()
            R.id.m_export_img -> exportImage()
            R.id.m_protect -> askProtect()
            R.id.m_pages_help -> {
                selectTool(Tool.PAN, quiet = true)
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
        undoStack.clear()
        redoStack.clear()
        updateUndoIcons()
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
                updateUndoIcons()
                toast(note)
                if (thenFinish) finish()
            }
        }
    }

    /** Guarda la página que estás viendo como foto JPG (con los cambios). */
    private fun exportImage() {
        val lm = pages.layoutManager as LinearLayoutManager
        val page = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        withApplied("Creando imagen…") {
            val pfdTmp = ParcelFileDescriptor.open(workFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val r = PdfRenderer(pfdTmp)
            try {
                val bmp = renderPage(page, 2200, r) ?: throw IllegalStateException("No se pudo dibujar la página")
                val name = docName.removeSuffix(".pdf").removeSuffix(".PDF") + "_pag${page + 1}.jpg"
                Store.saveImage(this, bmp, name)
                bmp.recycle()
                runOnUiThread { toast("Imagen guardada en Imágenes › GigaPDF") }
            } finally {
                r.close()
                pfdTmp.close()
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
            h.page.bitmap = null
            h.page.requestLayout()
            h.page.invalidate()
            if (tool == Tool.EDIT_TEXT && blocks[position] == null) loadBlocks(position)
        }
    }
}
