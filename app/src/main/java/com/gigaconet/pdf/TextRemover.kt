package com.gigaconet.pdf

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType3Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.ByteArrayInputStream

/**
 * Borra del PDF el texto original que el usuario editó, para que no quede
 * escondido debajo del nuevo (así se puede volver a editar, buscar y copiar
 * bien). Cada texto borrado se cambia por un espacio del mismo ancho, para
 * que el resto del renglón no se mueva.
 */
object TextRemover {

    private class Op(val tsFontSize: Float, val tc: Float, val tw: Float, val th: Float, val font: PDFont?) {
        var total = 0
        var inside = 0
    }

    private class Locator(
        val boxes: List<android.graphics.RectF>,
        val dispW: Float,
        val dispH: Float
    ) : PDFTextStripper() {
        var depth = 0
        var opIndex = -1
        var current: Op? = null
        val ops = HashMap<Int, Op>()
        val glyphsPerBox = IntArray(boxes.size)
        val glyphsRemovablePerBox = HashMap<Int, MutableList<Int>>() // caja -> índices de operadores

        override fun processOperator(operator: Operator, operands: MutableList<COSBase>) {
            if (depth == 0) {
                opIndex++
                val name = operator.name
                current = if (name == "Tj" || name == "TJ") {
                    val ts = graphicsState.textState
                    Op(ts.fontSize, ts.characterSpacing, ts.wordSpacing, ts.horizontalScaling / 100f, ts.font).also { ops[opIndex] = it }
                } else null
            }
            super.processOperator(operator, operands)
        }

        override fun showForm(form: PDFormXObject) {
            depth++
            try {
                super.showForm(form)
            } finally {
                depth--
            }
        }

        override fun processTextPosition(text: TextPosition) {
            val cx = (text.xDirAdj + text.widthDirAdj / 2f) / dispW
            val cy = (text.yDirAdj - text.heightDir / 2f) / dispH
            var inAny = -1
            for ((i, b) in boxes.withIndex()) {
                if (cx >= b.left - 0.004f && cx <= b.right + 0.004f && cy >= b.top - 0.004f && cy <= b.bottom + 0.004f) {
                    inAny = i
                    glyphsPerBox[i]++
                    break
                }
            }
            if (depth != 0) return
            val op = current ?: return
            op.total++
            if (inAny >= 0) {
                op.inside++
                glyphsRemovablePerBox.getOrPut(inAny) { ArrayList() }.let { if (it.lastOrNull() != opIndex) it.add(opIndex) }
            }
        }
    }

    /** Devuelve las ediciones cuyo texto original se borró por completo. */
    fun remove(doc: PDDocument, pageIndex: Int, edits: List<EditAnnot>): Set<EditAnnot> {
        val page = doc.getPage(pageIndex)
        val rot = ((page.rotation % 360) + 360) % 360
        val box = page.cropBox
        val dispW = if (rot == 90 || rot == 270) box.height else box.width
        val dispH = if (rot == 90 || rot == 270) box.width else box.height

        val pdfEdits = edits
        val loc = Locator(pdfEdits.map { it.box }, dispW, dispH)
        loc.sortByPosition = false
        loc.startPage = pageIndex + 1
        loc.endPage = pageIndex + 1
        loc.getText(doc)

        // Operadores que están completamente dentro de alguna caja editada
        val toBlank = loc.ops.filter { (_, op) -> op.total > 0 && op.inside == op.total && op.font !is PDType3Font }.keys
        if (toBlank.isEmpty()) return emptySet()

        val parser = PDFStreamParser(page)
        parser.parse()
        val tokens = parser.tokens
        val out = ArrayList<Any>(tokens.size)
        var opIdx = -1
        var operandStart = 0
        for (tok in tokens) {
            if (tok is Operator) {
                opIdx++
                val op = loc.ops[opIdx]
                if (opIdx in toBlank && op != null) {
                    val operands = out.subList(operandStart, out.size)
                    val adv = advance(op, operands.toList())
                    if (adv != null) {
                        operands.clear()
                        // Mismo avance horizontal, sin dibujar letras
                        val arr = COSArray()
                        arr.add(COSFloat(-adv))
                        out.add(arr)
                        out.add(Operator.getOperator("TJ"))
                        operandStart = out.size
                        continue
                    }
                }
                out.add(tok)
                operandStart = out.size
            } else {
                out.add(tok)
            }
        }

        val stream = PDStream(doc)
        stream.createOutputStream(COSName.FLATE_DECODE).use { os ->
            ContentStreamWriter(os).writeTokens(out)
        }
        page.setContents(stream)

        // Una edición queda "limpia" si todas las letras de su caja se borraron
        val cleared = HashSet<EditAnnot>()
        pdfEdits.forEachIndexed { i, e ->
            val opsInBox = loc.glyphsRemovablePerBox[i] ?: emptyList()
            val removedGlyphs = opsInBox.filter { it in toBlank }.sumOf { loc.ops[it]?.inside ?: 0 }
            if (loc.glyphsPerBox[i] > 0 && removedGlyphs >= loc.glyphsPerBox[i]) cleared.add(e)
        }
        return cleared
    }

    /**
     * Avance del texto en milésimas de la unidad de texto (lo que va dentro de
     * un TJ). null si no se puede calcular con seguridad.
     */
    private fun advance(op: Op, operands: List<Any>): Float? {
        val font = op.font ?: return null
        val fs = op.tsFontSize
        if (fs == 0f || op.th == 0f) return null
        var tx = 0f // en unidades de texto, ya multiplicado por fs y th
        fun addString(s: COSString) {
            val input = ByteArrayInputStream(s.bytes)
            while (input.available() > 0) {
                val before = input.available()
                val code = font.readCode(input)
                val used = before - input.available()
                val w = font.getWidth(code) / 1000f
                val wordSp = if (used == 1 && code == 32) op.tw else 0f
                tx += (w * fs + op.tc + wordSp) * op.th
            }
        }
        val arg = operands.lastOrNull() ?: return null
        when (arg) {
            is COSString -> addString(arg)
            is COSArray -> {
                for (i in 0 until arg.size()) {
                    when (val item = arg.getObject(i)) {
                        is COSString -> addString(item)
                        is COSNumber -> tx += -item.floatValue() / 1000f * fs * op.th
                        else -> {}
                    }
                }
            }
            else -> return null
        }
        return tx / (fs * op.th) * 1000f
    }
}
