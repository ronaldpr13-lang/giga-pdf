package com.gigaconet.pdf

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.text.HtmlCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Aviso de propiedad y Términos y Condiciones de GigaPDF. */
object Terms {

    // Si cambias los términos, sube este número para que se vuelvan a aceptar
    private const val VERSION = 1
    private const val KEY = "terms_accepted_v$VERSION"

    const val AVISO = """
<b>Esta aplicación pertenece a RONALD PINEDA RODRÍGUEZ.</b><br><br>
Fue creada <b>única y exclusivamente para uso educativo</b>. Cualquier otro uso indebido recae en la responsabilidad de quien le dio ese uso.<br><br>
Al continuar, aceptas que:<br>
• No usarás la app para falsificar, alterar o adulterar documentos oficiales, facturas, recibos, contratos, cédulas, títulos ni ningún documento con fines de engaño o fraude.<br>
• Solo editarás documentos que sean tuyos o que tengas permiso para modificar.<br>
• No usarás la firma de otra persona sin su autorización.<br>
• La app se entrega "tal como está", sin garantías. El autor no responde por pérdida de archivos, daños ni por el uso que hagas de ella.<br>
• Está prohibido vender, modificar o redistribuir la app sin permiso escrito del autor.<br><br>
<b>Contacto:</b><br>
Tel. / WhatsApp: 809-729-0613<br>
Correo: ronaldpr13@gmail.com
"""

    const val TERMINOS = """
<b>TÉRMINOS Y CONDICIONES DE USO — GigaPDF</b><br>
<i>Versión 1 · Vigentes desde el 17 de noviembre de 2026</i><br><br>

<b>1. Propietario</b><br>
GigaPDF (en adelante, "la aplicación") es propiedad de <b>Ronald Pineda Rodríguez</b>, República Dominicana (en adelante, "el autor"). Todos los derechos están reservados.<br><br>

<b>2. Finalidad</b><br>
La aplicación fue creada única y exclusivamente con fines <b>educativos</b> y de aprendizaje, para practicar la creación, escaneo y edición de documentos PDF.<br><br>

<b>3. Aceptación</b><br>
Al instalar, abrir o usar la aplicación, declaras haber leído y aceptado estos Términos. Si no estás de acuerdo, no debes usarla y debes desinstalarla.<br><br>

<b>4. Uso permitido</b><br>
Puedes usar la aplicación para escanear, crear, ver, anotar, firmar y editar documentos que sean de tu propiedad o que tengas autorización expresa para modificar.<br><br>

<b>5. Usos prohibidos</b><br>
Queda prohibido usar la aplicación para:<br>
a) Falsificar, alterar o adulterar documentos públicos o privados: cédulas, pasaportes, actas, títulos, certificados, facturas, recibos, cotizaciones, contratos, estados de cuenta u otros.<br>
b) Imitar o usar la firma, sello o identidad de otra persona o empresa sin su autorización.<br>
c) Cometer estafas, fraudes, engaños o cualquier acto contrario a la ley.<br>
d) Modificar documentos ajenos para perjudicar a terceros.<br>
e) Copiar, vender, alquilar, modificar, descompilar o redistribuir la aplicación sin permiso escrito del autor.<br><br>

<b>6. Responsabilidad del usuario</b><br>
Cada usuario es el <b>único responsable</b> del contenido que crea o modifica y del uso que da a la aplicación y a los documentos resultantes. Cualquier uso indebido, ilegal o contrario a estos Términos recae exclusivamente en la persona que lo realice, quien libera al autor de toda reclamación, daño o perjuicio derivado de ese uso.<br><br>

<b>7. Sin garantías</b><br>
La aplicación se ofrece "tal como está" y "según disponibilidad", sin garantías de ningún tipo. El autor no garantiza que funcione sin errores ni que el texto editado o reconocido (OCR) sea exacto. Revisa siempre tus documentos antes de usarlos.<br><br>

<b>8. Limitación de responsabilidad</b><br>
En la medida que lo permita la ley, el autor no será responsable por pérdida de archivos o información, daños al dispositivo, lucro cesante ni por ningún daño directo o indirecto derivado del uso o de la imposibilidad de usar la aplicación. Se recomienda guardar copias de tus documentos importantes.<br><br>

<b>9. Privacidad</b><br>
Tus documentos se procesan y guardan <b>en tu propio teléfono</b>. La aplicación no tiene cuentas de usuario ni envía tus archivos a servidores del autor. El escáner y el reconocimiento de texto usan componentes de Google instalados en el dispositivo, sujetos a las políticas de privacidad de Google. Si en el futuro la aplicación muestra anuncios, estos podrán usar un identificador del dispositivo según las políticas de Google, y se actualizarán estos Términos.<br><br>

<b>10. Distribución del APK</b><br>
Si recibiste la aplicación de otra persona, estos Términos se aplican igual. Quien comparte la aplicación no adquiere ningún derecho sobre ella, y cada persona que la usa responde por su propio uso.<br><br>

<b>11. Cambios</b><br>
El autor puede modificar estos Términos en cualquier momento. Cuando haya cambios importantes, la aplicación te pedirá aceptarlos de nuevo.<br><br>

<b>12. Ley aplicable</b><br>
Estos Términos se rigen por las leyes de la República Dominicana.<br><br>

<b>13. Contacto</b><br>
Ronald Pineda Rodríguez — República Dominicana.<br>
Tel. / WhatsApp: 809-729-0613<br>
Correo: ronaldpr13@gmail.com
"""

    fun accepted(ctx: Context): Boolean =
        ctx.getSharedPreferences("gigapdf", Context.MODE_PRIVATE).getBoolean(KEY, false)

    private fun setAccepted(ctx: Context) {
        ctx.getSharedPreferences("gigapdf", Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
    }

    private fun body(ctx: Context, html: String): ScrollView {
        val d = ctx.resources.displayMetrics.density
        val tv = TextView(ctx).apply {
            text = HtmlCompat.fromHtml(html.trim().replace("\n", ""), HtmlCompat.FROM_HTML_MODE_LEGACY)
            setTextColor(Color.parseColor("#1B1E26"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            setLineSpacing(0f, 1.15f)
            val p = (22 * d).toInt()
            setPadding(p, (8 * d).toInt(), p, (8 * d).toInt())
        }
        return ScrollView(ctx).apply { addView(tv) }
    }

    /**
     * Si la persona todavía no aceptó, muestra el aviso. Solo continúa con
     * [onAccepted] cuando toca "Acepto"; si no acepta, la app se cierra.
     */
    fun ensureAccepted(act: Activity, onAccepted: () -> Unit) {
        if (accepted(act)) {
            onAccepted()
            return
        }
        val dialog = MaterialAlertDialogBuilder(act)
            .setTitle("Aviso importante")
            .setView(body(act, AVISO))
            .setCancelable(false)
            .setPositiveButton("Acepto") { _, _ ->
                setAccepted(act)
                onAccepted()
            }
            .setNegativeButton("No acepto") { _, _ -> act.finishAffinity() }
            .setNeutralButton("Leer términos", null)
            .show()
        // "Leer términos" abre el texto completo sin cerrar el aviso
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { show(act) }
    }

    /** Muestra los Términos y Condiciones completos. */
    fun show(ctx: Context) {
        MaterialAlertDialogBuilder(ctx)
            .setView(body(ctx, TERMINOS))
            .setPositiveButton("Cerrar", null)
            .show()
    }
}
