package com.gigaconet.pdf

import android.graphics.Bitmap
import android.graphics.Color
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import kotlin.math.abs

/** Todas las operaciones que modifican el archivo PDF (se llaman en segundo plano). */
object PdfOps {

    /** Abre un PDF; si está protegido usa la contraseña y le quita la protección. */
    fun unlock(src: File, password: String, dst: File) {
        PDDocument.load(src, password).use { doc ->
            doc.isAllSecurityToBeRemoved = true
            doc.save(dst)
        }
    }

    fun isEncrypted(file: File): Boolean {
        return try {
            PDDocument.load(file).use { it.isEncrypted }
        } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
            true
        }
    }

    private inline fun edit(file: File, block: (PDDocument) -> Unit) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        PDDocument.load(file).use { doc ->
            block(doc)
            doc.save(tmp)
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    // ---------- Páginas ----------

    fun rotate(file: File, index: Int) = edit(file) { doc ->
        val p = doc.getPage(index)
        p.rotation = (p.rotation + 90) % 360
    }

    fun delete(file: File, index: Int) = edit(file) { doc ->
        doc.removePage(index)
    }

    fun move(file: File, from: Int, to: Int) = edit(file) { doc ->
        val n = doc.numberOfPages
        if (to < 0 || to >= n || from == to) return@edit
        val page = doc.getPage(from)
        doc.removePage(from)
        if (to >= doc.numberOfPages) {
            doc.addPage(page)
        } else {
            doc.pages.insertBefore(page, doc.getPage(to))
        }
    }

    fun duplicate(file: File, index: Int) = edit(file) { doc ->
        val page = doc.getPage(index)
        val copy = PDPage(COSDictionary(page.cosObject))
        doc.pages.insertAfter(copy, page)
    }

    fun addBlank(file: File, after: Int) = edit(file) { doc ->
        val ref = if (doc.numberOfPages > 0) doc.getPage(after.coerceIn(0, doc.numberOfPages - 1)) else null
        val size = ref?.mediaBox ?: PDRectangle.A4
        val page = PDPage(PDRectangle(size.width, size.height))
        if (ref == null) doc.addPage(page) else doc.pages.insertAfter(page, ref)
    }

    fun addImagePage(file: File, bitmap: Bitmap) = edit(file) { doc ->
        addImageToDoc(doc, bitmap)
    }

    /** Crea un PDF nuevo en blanco (una página A4). */
    fun createBlank(dst: File) {
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle.A4))
            doc.save(dst)
        }
    }

    /** Crea un PDF con una página por imagen (tamaño A4, imagen centrada). */
    fun imagesToPdf(bitmaps: Sequence<Bitmap>, dst: File) {
        PDDocument().use { doc ->
            for (b in bitmaps) {
                addImageToDoc(doc, b)
                b.recycle()
            }
            doc.save(dst)
        }
    }

    private fun addImageToDoc(doc: PDDocument, bitmap: Bitmap) {
        val landscape = bitmap.width > bitmap.height
        val a4 = PDRectangle.A4
        val box = if (landscape) PDRectangle(a4.height, a4.width) else PDRectangle(a4.width, a4.height)
        val page = PDPage(box)
        doc.addPage(page)
        val img = JPEGFactory.createFromImage(doc, bitmap, 0.85f)
        val margin = 18f
        val maxW = box.width - margin * 2
        val maxH = box.height - margin * 2
        val scale = minOf(maxW / bitmap.width, maxH / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        PDPageContentStream(doc, page).use { cs ->
            cs.drawImage(img, (box.width - w) / 2f, (box.height - h) / 2f, w, h)
        }
    }

    // ---------- Unir / extraer / proteger ----------

    fun merge(sources: List<File>, dst: File) {
        val m = PDFMergerUtility()
        for (f in sources) m.addSource(f)
        m.destinationFileName = dst.absolutePath
        m.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly())
    }

    /** Copia solo las páginas indicadas (base 0) a un PDF nuevo. */
    fun extract(src: File, pages: List<Int>, dst: File) {
        PDDocument.load(src).use { doc ->
            val n = doc.numberOfPages
            val keep = pages.filter { it in 0 until n }.toSet()
            for (i in n - 1 downTo 0) {
                if (i !in keep) doc.removePage(i)
            }
            doc.save(dst)
        }
    }

    fun protect(src: File, password: String, dst: File) {
        PDDocument.load(src).use { doc ->
            val policy = StandardProtectionPolicy(password, password, AccessPermission())
            policy.encryptionKeyLength = 128
            doc.protect(policy)
            doc.save(dst)
        }
    }

    // ---------- Anotaciones ----------

    /**
     * Dibuja las anotaciones dentro del PDF como contenido real de la página
     * (vectorial: el texto y los trazos quedan nítidos al hacer zoom).
     */
    fun applyAnnotations(file: File, annots: Map<Int, List<Annot>>) {
        if (annots.values.all { it.isEmpty() }) return
        edit(file) { doc ->
            for ((index, list) in annots) {
                if (list.isEmpty() || index >= doc.numberOfPages) continue
                val page = doc.getPage(index)
                val map = PageMap(page.cropBox, page.rotation)
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    for (a in list) {
                        when (a) {
                            is StrokeAnnot -> drawStroke(cs, a, map)
                            is TextAnnot -> drawText(cs, a, map)
                            is EditAnnot -> {
                                fillBox(cs, a.box, a.bg, map)
                                drawText(cs, a.text, map)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Capa de texto invisible encima de páginas escaneadas: el PDF se ve igual
     * pero ahora se puede buscar, seleccionar y copiar el texto.
     */
    fun addTextLayer(file: File, lines: Map<Int, List<OcrLine>>) {
        if (lines.values.all { it.isEmpty() }) return
        edit(file) { doc ->
            val font = PDType1Font.HELVETICA
            for ((index, list) in lines) {
                if (list.isEmpty() || index >= doc.numberOfPages) continue
                val page = doc.getPage(index)
                val m = PageMap(page.cropBox, page.rotation)
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    for (l in list) {
                        val text = safeText(font, l.text)
                        if (text.isBlank()) continue
                        val hPt = (l.box.bottom - l.box.top) * m.dispH
                        val wPt = (l.box.right - l.box.left) * m.dispW
                        val size = (hPt * 0.8f).coerceAtLeast(1f)
                        val natural = font.getStringWidth(text) / 1000f * size
                        val p = m.map(l.box.left, l.box.bottom - (l.box.bottom - l.box.top) * 0.2f)
                        cs.beginText()
                        cs.setRenderingMode(RenderingMode.NEITHER)
                        cs.setFont(font, size)
                        if (natural > 0f) cs.setHorizontalScaling((wPt / natural * 100f).coerceIn(10f, 400f))
                        cs.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(m.rotation.toDouble()), p[0], p[1]))
                        cs.showText(text)
                        cs.endText()
                    }
                }
            }
        }
    }

    /** Todo el texto del documento (para copiar o compartir). */
    fun allText(file: File): String {
        PDDocument.load(file).use { doc ->
            val st = com.tom_roush.pdfbox.text.PDFTextStripper()
            st.sortByPosition = true
            return st.getText(doc)
        }
    }

    private fun fillBox(cs: PDPageContentStream, r: android.graphics.RectF, color: Int, m: PageMap) {
        val a = m.map(r.left, r.top)
        val b = m.map(r.right, r.bottom)
        cs.saveGraphicsState()
        cs.setNonStrokingColor(Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f)
        cs.addRect(minOf(a[0], b[0]), minOf(a[1], b[1]), abs(a[0] - b[0]), abs(a[1] - b[1]))
        cs.fill()
        cs.restoreGraphicsState()
    }

    private fun drawStroke(cs: PDPageContentStream, s: StrokeAnnot, m: PageMap) {
        if (s.points.isEmpty()) return
        cs.saveGraphicsState()
        if (s.highlight) {
            val gs = PDExtendedGraphicsState()
            gs.strokingAlphaConstant = 0.38f
            cs.setGraphicsStateParameters(gs)
        }
        cs.setStrokingColor(Color.red(s.color) / 255f, Color.green(s.color) / 255f, Color.blue(s.color) / 255f)
        cs.setLineWidth(s.width * m.dispW)
        cs.setLineCapStyle(if (s.highlight) 2 else 1)
        cs.setLineJoinStyle(1)
        val first = m.map(s.points[0].x, s.points[0].y)
        cs.moveTo(first[0], first[1])
        if (s.points.size == 1) {
            cs.lineTo(first[0] + 0.1f, first[1])
        } else {
            for (i in 1 until s.points.size) {
                val p = m.map(s.points[i].x, s.points[i].y)
                cs.lineTo(p[0], p[1])
            }
        }
        cs.stroke()
        cs.restoreGraphicsState()
    }

    private fun drawText(cs: PDPageContentStream, t: TextAnnot, m: PageMap) {
        if (t.text.isBlank()) return
        val font: PDFont = if (t.bold) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA
        val sizePt = t.size * m.dispW
        val lineH = sizePt * t.lineHeight / m.dispH
        val firstBaseline = sizePt / m.dispH
        cs.saveGraphicsState()
        cs.setNonStrokingColor(Color.red(t.color) / 255f, Color.green(t.color) / 255f, Color.blue(t.color) / 255f)
        val maxW = t.maxWidth * m.dispW
        val lines = wrapText(safeText(font, t.text), maxW) { s -> font.getStringWidth(s) / 1000f * sizePt }
        for ((i, line) in lines.withIndex()) {
            if (line.isEmpty()) continue
            val p = m.map(t.x, t.y + firstBaseline + i * lineH)
            cs.beginText()
            cs.setFont(font, sizePt)
            cs.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(m.rotation.toDouble()), p[0], p[1]))
            cs.showText(line)
            cs.endText()
        }
        cs.restoreGraphicsState()
    }

    /** Cambia los caracteres que la fuente no puede dibujar (ej. emojis). */
    private fun safeText(font: PDFont, text: String): String {
        val clean = text.replace('\t', ' ').replace("\r", "")
        return try {
            font.encode(clean.replace("\n", ""))
            clean
        } catch (e: Exception) {
            val sb = StringBuilder()
            var i = 0
            while (i < clean.length) {
                val cp = clean.codePointAt(i)
                val ch = String(Character.toChars(cp))
                if (ch == "\n") {
                    sb.append(ch)
                } else {
                    try {
                        font.encode(ch)
                        sb.append(ch)
                    } catch (e2: Exception) {
                        sb.append('?')
                    }
                }
                i += Character.charCount(cp)
            }
            sb.toString()
        }
    }

    /**
     * Convierte coordenadas normalizadas de pantalla (0..1, y hacia abajo)
     * a coordenadas del PDF, teniendo en cuenta la rotación de la página.
     */
    private class PageMap(box: PDRectangle, rot: Int) {
        val rotation = ((rot % 360) + 360) % 360
        private val x0 = box.lowerLeftX
        private val y0 = box.lowerLeftY
        private val w = box.width
        private val h = box.height
        val dispW = if (rotation == 90 || rotation == 270) h else w
        val dispH = if (rotation == 90 || rotation == 270) w else h

        fun map(u: Float, v: Float): FloatArray = when (rotation) {
            90 -> floatArrayOf(x0 + v * w, y0 + u * h)
            180 -> floatArrayOf(x0 + (1 - u) * w, y0 + v * h)
            270 -> floatArrayOf(x0 + (1 - v) * w, y0 + (1 - u) * h)
            else -> floatArrayOf(x0 + u * w, y0 + (1 - v) * h)
        }
    }
}
