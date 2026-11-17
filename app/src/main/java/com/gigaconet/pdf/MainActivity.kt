package com.gigaconet.pdf

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var busy: View
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private var items: List<Recent> = emptyList()

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode != RESULT_OK) return@registerForActivityResult
        val pdf = GmsDocumentScanningResult.fromActivityResultIntent(res.data)?.pdf ?: return@registerForActivityResult
        work("Preparando escaneo…") {
            val f = newTemp()
            Store.copyToFile(this, pdf.uri, f)
            runOnUiThread { openFile(f, Store.newName("Escaneo"), askOcr = true) }
        }
    }

    private val openLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        keepPermission(uri)
        openUri(uri)
    }

    private val imagesLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        work("Creando PDF con ${uris.size} foto(s)…") {
            val f = newTemp()
            PdfOps.imagesToPdf(uris.asSequence().map { Store.loadBitmap(this, it) }, f)
            runOnUiThread { openFile(f, Store.newName("Fotos")) }
        }
    }

    private val mergeLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        if (uris.size < 2) {
            toast("Elige 2 o más PDF (mantén presionado para seleccionar varios)")
            return@registerForActivityResult
        }
        work("Uniendo ${uris.size} PDF…") {
            val parts = uris.mapIndexed { i, u ->
                File(cacheDir, "merge_$i.pdf").also { Store.copyToFile(this, u, it) }
            }
            val f = newTemp()
            PdfOps.merge(parts, f)
            parts.forEach { it.delete() }
            runOnUiThread { openFile(f, Store.newName("Unido")) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        busy = findViewById(R.id.busy)
        list = findViewById(R.id.recents)
        empty = findViewById(R.id.emptyRecents)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = RecentAdapter()

        findViewById<View>(R.id.cardScan).setOnClickListener { startScan() }
        findViewById<View>(R.id.cardOpen).setOnClickListener { openLauncher.launch(arrayOf("application/pdf")) }
        findViewById<View>(R.id.cardImages).setOnClickListener { imagesLauncher.launch("image/*") }
        findViewById<View>(R.id.cardMerge).setOnClickListener { mergeLauncher.launch(arrayOf("application/pdf")) }
        findViewById<View>(R.id.btnTerms).setOnClickListener { Terms.show(this) }
        Terms.ensureAccepted(this) { }
        findViewById<View>(R.id.btnBlank).setOnClickListener {
            work("Creando…") {
                val f = newTemp()
                PdfOps.createBlank(f)
                runOnUiThread { openFile(f, Store.newName("Nuevo")) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        items = Store.recents(this)
        list.adapter?.notifyDataSetChanged()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun startScan() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(60)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { sender ->
                scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener {
                toast("No se pudo abrir el escáner. Revisa que Google Play Services esté actualizado.")
            }
    }

    private fun keepPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e2: Exception) {
            }
        }
    }

    private fun newTemp(): File {
        val dir = File(cacheDir, "nuevos").apply { mkdirs() }
        return File(dir, "doc_${System.currentTimeMillis()}.pdf")
    }

    private fun openUri(uri: Uri) {
        startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_URI, uri.toString()))
    }

    private fun openFile(f: File, name: String, askOcr: Boolean = false) {
        startActivity(
            Intent(this, EditorActivity::class.java)
                .putExtra(EditorActivity.EXTRA_FILE, f.absolutePath)
                .putExtra(EditorActivity.EXTRA_NAME, name)
                .putExtra(EditorActivity.EXTRA_OCR, askOcr)
        )
    }

    private fun work(msg: String, block: () -> Unit) {
        busy.visibility = View.VISIBLE
        toast(msg)
        io.execute {
            try {
                block()
            } catch (e: Throwable) {
                runOnUiThread { toast("Error: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                runOnUiThread { busy.visibility = View.GONE }
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private inner class RecentAdapter : RecyclerView.Adapter<RecentAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.name)
            val date: TextView = v.findViewById(R.id.date)
            val more: ImageButton = v.findViewById(R.id.more)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_recent, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val r = items[position]
            h.name.text = r.name
            h.date.text = DateUtils.getRelativeTimeSpanString(r.time)
            h.itemView.setOnClickListener { openUri(Uri.parse(r.uri)) }
            h.more.setOnClickListener { v ->
                val m = PopupMenu(this@MainActivity, v)
                m.menu.add(0, 1, 0, "Compartir")
                m.menu.add(0, 2, 1, "Quitar de recientes")
                m.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "application/pdf"
                                putExtra(Intent.EXTRA_STREAM, Uri.parse(r.uri))
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(Intent.createChooser(send, "Compartir PDF"))
                        }
                        2 -> {
                            Store.removeRecent(this@MainActivity, r.uri)
                            refresh()
                        }
                    }
                    true
                }
                m.show()
            }
        }
    }
}
