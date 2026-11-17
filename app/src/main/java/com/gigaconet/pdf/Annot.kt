package com.gigaconet.pdf

import android.graphics.PointF
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject

/**
 * Anotaciones sobre una página. Todas las coordenadas están normalizadas
 * (0..1) respecto a la página tal como se ve en pantalla: x de izquierda a
 * derecha, y de arriba hacia abajo. Los tamaños (grosor, letra) son
 * fracciones del ancho visible de la página.
 */
sealed class Annot

class StrokeAnnot(
    val points: MutableList<PointF>,
    val color: Int,
    val width: Float,
    val highlight: Boolean
) : Annot()

/**
 * Texto. (x, y) es la esquina superior izquierda; la primera línea base
 * queda a "size" por debajo. lineHeight es el interlineado en múltiplos
 * del tamaño. maxWidth > 0 hace que las líneas largas se acomoden solas.
 */
class TextAnnot(
    var x: Float,
    var y: Float,
    var text: String,
    var size: Float,
    var color: Int,
    var bold: Boolean = false,
    var lineHeight: Float = 1.25f,
    var maxWidth: Float = 0f,
    /** "sans", "serif", "mono" u "orig:<nombre>" para usar la letra original del PDF. */
    var font: String = "sans",
    /** Si la letra original era negrita (para saber si el usuario la cambió). */
    val origBold: Boolean = false
) : Annot() {
    /** Familia para dibujar en pantalla y para la letra de respaldo. */
    val family: String get() = if (font.startsWith("orig:")) familyOf(font.removePrefix("orig:")) else font
}

/** Adivina la familia (sans / serif / mono) por el nombre de la fuente del PDF. */
fun familyOf(fontName: String?): String {
    val n = (fontName ?: "").lowercase()
    return when {
        listOf("courier", "mono", "consol", "typewriter").any { n.contains(it) } -> "mono"
        listOf("times", "serif", "roman", "georgia", "garamond", "cambria", "book", "palatino", "minion").any { n.contains(it) } &&
            !n.contains("sans") -> "serif"
        else -> "sans"
    }
}

/** Nombre corto de la letra para mostrar en el botón. */
fun fontLabel(font: String): String = when {
    font.startsWith("orig:") -> "Original"
    font == "serif" -> "Times"
    font == "mono" -> "Courier"
    else -> "Arial"
}

/**
 * Edición de un texto que ya existía en el PDF: tapa el original con el
 * color del fondo y escribe el texto nuevo en el mismo lugar.
 */
class EditAnnot(
    val box: RectF,
    val bg: Int,
    val text: TextAnnot,
    val original: String,
    /** true si el texto viene de una foto/escaneo (hay que tapar los píxeles). */
    val fromImage: Boolean = false
) : Annot()

/** Bloque de texto detectado en una página (del PDF o reconocido por OCR). */
class TextBlock(
    val box: RectF,
    val text: String,
    val size: Float,
    val firstBaseline: Float,
    val lineHeight: Float,
    val bold: Boolean,
    val fromOcr: Boolean,
    val fontName: String? = null
)

/** Una línea reconocida por OCR (para crear la capa de texto invisible). */
class OcrLine(val box: RectF, val text: String)

/** Corta el texto en líneas que quepan en maxWidth (si maxWidth > 0). */
fun wrapText(text: String, maxWidth: Float, measure: (String) -> Float): List<String> {
    val out = ArrayList<String>()
    for (para in text.split("\n")) {
        if (maxWidth <= 0f || measure(para) <= maxWidth) {
            out.add(para)
            continue
        }
        var line = StringBuilder()
        for (word in para.split(" ")) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (measure(candidate) <= maxWidth || line.isEmpty()) {
                line = StringBuilder(candidate)
            } else {
                out.add(line.toString())
                line = StringBuilder(word)
            }
        }
        out.add(line.toString())
    }
    return out
}

/** Una firma guardada: trazos normalizados dentro de su propio recuadro. */
class Signature(val strokes: List<List<PointF>>, val aspect: Float) {

    fun toJson(): String {
        val arr = JSONArray()
        for (s in strokes) {
            val pts = JSONArray()
            for (p in s) {
                pts.put(p.x.toDouble())
                pts.put(p.y.toDouble())
            }
            arr.put(pts)
        }
        return JSONObject().put("aspect", aspect.toDouble()).put("strokes", arr).toString()
    }

    companion object {
        fun fromJson(json: String?): Signature? {
            if (json.isNullOrBlank()) return null
            return try {
                val o = JSONObject(json)
                val arr = o.getJSONArray("strokes")
                val list = ArrayList<List<PointF>>()
                for (i in 0 until arr.length()) {
                    val pts = arr.getJSONArray(i)
                    val s = ArrayList<PointF>()
                    var j = 0
                    while (j + 1 < pts.length()) {
                        s.add(PointF(pts.getDouble(j).toFloat(), pts.getDouble(j + 1).toFloat()))
                        j += 2
                    }
                    list.add(s)
                }
                Signature(list, o.getDouble("aspect").toFloat())
            } catch (e: Exception) {
                null
            }
        }
    }
}
