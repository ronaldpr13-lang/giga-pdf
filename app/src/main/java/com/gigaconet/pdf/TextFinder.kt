package com.gigaconet.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlin.math.abs

/**
 * Encuentra los bloques de texto de una página: primero lee el texto real
 * del PDF; si la página es una foto/escaneo, se usa OCR (ML Kit, sin internet).
 */
object TextFinder {

    private class Word(val x0: Float, val x1: Float, val base: Float, val size: Float, val text: String, val bold: Boolean, val font: String)

    private class Line(var x0: Float, var x1: Float, val base: Float, val size: Float, val sb: StringBuilder, var bold: Boolean, val font: String)

    private class Collector(val out: MutableList<Word>) : PDFTextStripper() {
        init {
            sortByPosition = true
        }

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            if (textPositions.isEmpty() || text.isBlank()) return
            var x0 = Float.MAX_VALUE
            var x1 = -Float.MAX_VALUE
            val sizes = ArrayList<Float>()
            var bold = false
            var font = ""
            for (tp in textPositions) {
                x0 = minOf(x0, tp.xDirAdj)
                x1 = maxOf(x1, tp.xDirAdj + tp.widthDirAdj)
                var s = tp.fontSizeInPt
                if (s <= 0.5f) s = tp.heightDir / 0.7f
                if (s > 0f) sizes.add(s)
                val fname = try { tp.font?.name ?: "" } catch (e: Exception) { "" }
                if (font.isEmpty()) font = fname
                if (fname.contains("Bold", true) || fname.contains("Black", true) || fname.contains("Heavy", true)) bold = true
            }
            if (sizes.isEmpty()) return
            sizes.sort()
            out.add(Word(x0, x1, textPositions[0].yDirAdj, sizes[sizes.size / 2], text.trim(), bold, font))
        }
    }

    /** Bloques de texto reales del PDF en la página [index]. */
    fun fromPdf(doc: PDDocument, index: Int): List<TextBlock> {
        val page = doc.getPage(index)
        val rot = ((page.rotation % 360) + 360) % 360
        val box = page.cropBox
        val dispW = if (rot == 90 || rot == 270) box.height else box.width
        val dispH = if (rot == 90 || rot == 270) box.width else box.height

        val words = ArrayList<Word>()
        val stripper = Collector(words)
        stripper.startPage = index + 1
        stripper.endPage = index + 1
        stripper.getText(doc)
        if (words.isEmpty()) return emptyList()

        // 1) Palabras -> renglones (misma línea base y cerca una de otra)
        words.sortWith(compareBy<Word>({ it.base }, { it.x0 }))
        val lines = ArrayList<Line>()
        for (w in words) {
            val l = lines.lastOrNull { abs(it.base - w.base) < 0.35f * w.size && w.x0 - it.x1 < 1.3f * w.size && w.x0 >= it.x0 - 0.5f * w.size }
            if (l != null) {
                l.sb.append(' ').append(w.text)
                l.x1 = maxOf(l.x1, w.x1)
                l.bold = l.bold && w.bold
            } else {
                lines.add(Line(w.x0, w.x1, w.base, w.size, StringBuilder(w.text), w.bold, w.font))
            }
        }

        // 2) Renglones -> bloques (párrafos alineados y seguidos)
        lines.sortWith(compareBy<Line>({ it.base }, { it.x0 }))
        val blocks = ArrayList<MutableList<Line>>()
        for (l in lines) {
            val target = blocks.lastOrNull { b ->
                val last = b.last()
                val gap = l.base - last.base
                val sameSize = l.size / last.size in 0.8f..1.25f
                val minX = b.minOf { it.x0 }
                val maxX = b.maxOf { it.x1 }
                val overlap = l.x0 < maxX && l.x1 > minX
                gap > 0.3f * l.size && gap < 2.6f * l.size && sameSize && overlap && abs(l.x0 - b.first().x0) < 2.5f * l.size
            }
            if (target != null) target.add(l) else blocks.add(mutableListOf(l))
        }

        return blocks.map { b ->
            val size = b.map { it.size }.sorted()[b.size / 2]
            val minX = b.minOf { it.x0 } - 0.15f * size
            val maxX = b.maxOf { it.x1 } + 0.15f * size
            val top = b.first().base - 0.95f * size
            val bottom = b.last().base + 0.3f * size
            val diffs = (1 until b.size).map { b[it].base - b[it - 1].base }.filter { it > 0f }
            val lh = if (diffs.isEmpty()) 1.2f else diffs.minOrNull()!! / size
            TextBlock(
                box = RectF(minX / dispW, top / dispH, maxX / dispW, bottom / dispH),
                text = b.joinToString("\n") { it.sb.toString() },
                size = size / dispW,
                firstBaseline = b.first().base / dispH,
                lineHeight = lh.coerceIn(1.0f, 2.2f),
                bold = b.all { it.bold },
                fromOcr = false,
                fontName = b.groupingBy { it.font }.eachCount().maxByOrNull { it.value }?.key
            )
        }
    }

    /** OCR de una imagen de la página. Devuelve bloques editables y líneas. */
    fun ocr(bmp: Bitmap): Pair<List<TextBlock>, List<OcrLine>> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)))
            val w = bmp.width.toFloat()
            val h = bmp.height.toFloat()
            val blocks = ArrayList<TextBlock>()
            val ocrLines = ArrayList<OcrLine>()
            val regular = android.graphics.Paint().apply { typeface = android.graphics.Typeface.SANS_SERIF; textSize = 100f }
            val boldP = android.graphics.Paint().apply { typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD); textSize = 100f }
            for (block in result.textBlocks) {
                val ls = block.lines.filter { it.boundingBox != null && it.text.isNotBlank() }
                if (ls.isEmpty()) continue
                for (l in ls) {
                    val r = l.boundingBox!!
                    ocrLines.add(OcrLine(RectF(r.left / w, r.top / h, r.right / w, r.bottom / h), l.text))
                }
                // Separar renglones de distinto tamaño (ej. un título en negrita y el texto normal)
                val groups = ArrayList<MutableList<com.google.mlkit.vision.text.Text.Line>>()
                for (l in ls) {
                    val g = groups.lastOrNull()
                    val r = l.boundingBox!!
                    if (g != null) {
                        val pr = g.last().boundingBox!!
                        val ratio = r.height().toFloat() / pr.height().coerceAtLeast(1)
                        val gap = r.top - pr.bottom
                        if (ratio in 0.85f..1.18f && gap < pr.height() * 1.2f && Math.abs(r.left - pr.left) < pr.height() * 2.5f) {
                            g.add(l)
                            continue
                        }
                    }
                    groups.add(mutableListOf(l))
                }
                // En fotos se edita renglón por renglón: así solo cambia la línea que
                // tocaste y el resto de la foto queda intacto
                for (g in groups) for (l in g) blocks.add(ocrBlock(listOf(l), w, h, regular, boldP))
            }
            return Pair(blocks, ocrLines)
        } finally {
            recognizer.close()
        }
    }

    /**
     * Calcula tamaño, negrita y posición de un grupo de renglones de una foto.
     * El tamaño se ajusta para que el renglón más largo mida lo mismo que en la
     * foto (así el texto nuevo queda del mismo tamaño que el original).
     */
    private fun ocrBlock(
        g: List<com.google.mlkit.vision.text.Text.Line>, w: Float, h: Float,
        regular: android.graphics.Paint, boldP: android.graphics.Paint
    ): TextBlock {
        val heights = g.map { it.boundingBox!!.height().toFloat() }.sorted()
        val lineH = heights[heights.size / 2]
        val byHeight = lineH * 0.74f
        // Renglón más ancho: el que mejor sirve para medir
        val widest = g.maxByOrNull { it.boundingBox!!.width() }!!
        val boxW = widest.boundingBox!!.width().toFloat()
        val wReg = regular.measureText(widest.text).coerceAtLeast(1f)
        val wBold = boldP.measureText(widest.text).coerceAtLeast(1f)
        val sizeReg = boxW / wReg * 100f
        val sizeBold = boxW / wBold * 100f
        // Si con negrita el tamaño cuadra mejor con la altura de las letras, era negrita
        // (con margen: ante la duda, letra normal)
        val bold = widest.text.length >= 4 && Math.abs(sizeBold - byHeight) < Math.abs(sizeReg - byHeight) * 0.6f
        var sizePx = if (bold) sizeBold else sizeReg
        if (widest.text.length < 4) sizePx = byHeight
        sizePx = sizePx.coerceIn(byHeight * 0.7f, byHeight * 1.5f)

        val first = g.first().boundingBox!!
        val tops = g.map { it.boundingBox!!.top.toFloat() }
        val diffs = (1 until tops.size).map { tops[it] - tops[it - 1] }.filter { it > 0f }
        val lh = if (diffs.isEmpty()) 1.2f else diffs.minOrNull()!! / sizePx
        val textLeft = g.minOf { it.boundingBox!!.left }.toFloat()
        val pad = lineH * 0.15f
        val left = textLeft - pad
        val right = g.maxOf { it.boundingBox!!.right } + pad
        val top = g.minOf { it.boundingBox!!.top } - lineH * 0.12f
        val bottom = g.maxOf { it.boundingBox!!.bottom } + lineH * 0.12f
        return TextBlock(
            box = RectF(left / w, top / h, right / w, bottom / h),
            text = g.joinToString("\n") { it.text },
            size = sizePx / w,
            firstBaseline = (first.bottom - lineH * 0.2f) / h,
            lineHeight = lh.coerceIn(1.0f, 2.2f),
            bold = bold,
            fromOcr = true,
            textLeft = textLeft / w
        )
    }

    /**
     * Mira los píxeles de la página para saber el color del fondo (para tapar)
     * y el color de las letras (para escribir igual). Devuelve (fondo, letra).
     */
    fun sampleColors(bmp: Bitmap, box: RectF): Pair<Int, Int> {
        val w = bmp.width
        val h = bmp.height
        val l = (box.left * w).toInt().coerceIn(0, w - 1)
        val r = (box.right * w).toInt().coerceIn(0, w - 1)
        val t = (box.top * h).toInt().coerceIn(0, h - 1)
        val b = (box.bottom * h).toInt().coerceIn(0, h - 1)
        val border = ArrayList<Int>()
        val stepX = maxOf(1, (r - l) / 40)
        val stepY = maxOf(1, (b - t) / 20)
        for (x in l..r step stepX) {
            border.add(bmp.getPixel(x, (t - 2).coerceAtLeast(0)))
            border.add(bmp.getPixel(x, (b + 2).coerceAtMost(h - 1)))
        }
        for (y in t..b step stepY) {
            border.add(bmp.getPixel((l - 2).coerceAtLeast(0), y))
            border.add(bmp.getPixel((r + 2).coerceAtMost(w - 1), y))
        }
        border.sortBy { lum(it) }
        val bg = if (border.isEmpty()) Color.WHITE else border[border.size * 2 / 3]
        var fg = Color.BLACK
        var best = -1f
        val bgL = lum(bg)
        for (y in t..b step maxOf(1, (b - t) / 30)) {
            for (x in l..r step maxOf(1, (r - l) / 80)) {
                val p = bmp.getPixel(x, y)
                val d = abs(lum(p) - bgL)
                if (d > best) {
                    best = d
                    fg = p
                }
            }
        }
        return Pair(bg or 0xFF000000.toInt(), fg or 0xFF000000.toInt())
    }

    private fun lum(c: Int): Float = 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
}
