package com.gigaconet.pdf

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.util.concurrent.Executors

/** Lista rápida de todos los PDF del teléfono (sin pasar por Google Drive). */
class FilesActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MULTI = "multi"
        const val RESULT_URIS = "uris"
    }

    private class Item(val uri: Uri, val name: String, val folder: String, val size: Long, val date: Long)

    private val io = Executors.newSingleThreadExecutor()
    private var all: List<Item> = emptyList()
    private var shown: List<Item> = emptyList()
    private val selected = LinkedHashSet<Uri>()
    private var multi = false
    private var filter = "Todos"

    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var loading: View
    private lateinit var permCard: View
    private lateinit var search: EditText
    private lateinit var btnMerge: MaterialButton
    private val adapter = FileAdapter()

    private val otherPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        for (u in uris) keepPermission(u)
        if (multi) {
            selected.addAll(uris)
            updateMerge()
            adapter.notifyDataSetChanged()
            if (selected.size >= 2) finishMulti()
        } else {
            open(uris.first())
        }
    }

    private val readPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { load() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_files)
        multi = intent.getBooleanExtra(EXTRA_MULTI, false)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = if (multi) "Elige los PDF para unir" else "Abrir PDF"
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.files)
        toolbar.setOnMenuItemClickListener {
            otherPicker.launch(arrayOf("application/pdf"))
            true
        }

        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        loading = findViewById(R.id.loading)
        permCard = findViewById(R.id.permCard)
        search = findViewById(R.id.search)
        btnMerge = findViewById(R.id.btnMerge)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        val chips = findViewById<ChipGroup>(R.id.filters)
        for (name in listOf("Todos", "Descargas", "WhatsApp", "Documentos", "GigaPDF")) {
            val c = Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                text = name
                isCheckable = true
                isCheckedIconVisible = false
                id = View.generateViewId()
                isChecked = name == "Todos"
                chipStrokeWidth = 0f
                setChipBackgroundColorResource(R.color.chip_bg)
                setTextColor(ContextCompat.getColorStateList(this@FilesActivity, R.color.chip_text))
            }
            c.setOnCheckedChangeListener { _, on ->
                if (on) {
                    filter = name
                    applyFilter()
                }
            }
            chips.addView(c)
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })

        findViewById<View>(R.id.btnPerm).setOnClickListener { askPermission() }
        btnMerge.setOnClickListener { finishMulti() }
    }

    override fun onResume() {
        super.onResume()
        load()  // al volver de Ajustes ya se ven todos
    }

    private fun hasFullAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun askPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            readPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun load() {
        permCard.visibility = if (hasFullAccess()) View.GONE else View.VISIBLE
        loading.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        io.execute {
            val found = ArrayList<Item>()
            try {
                val base = MediaStore.Files.getContentUri("external")
                val proj = arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.MediaColumns.RELATIVE_PATH
                )
                val sel = "${MediaStore.MediaColumns.MIME_TYPE}=? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
                val args = arrayOf("application/pdf", "%.pdf")
                contentResolver.query(base, proj, sel, args, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
                    val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val iDate = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val iPath = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                    while (c.moveToNext()) {
                        val name = c.getString(iName) ?: continue
                        val size = c.getLong(iSize)
                        if (size <= 0L) continue
                        found.add(
                            Item(
                                ContentUris.withAppendedId(base, c.getLong(iId)),
                                name,
                                (c.getString(iPath) ?: "").trimEnd('/'),
                                size,
                                c.getLong(iDate) * 1000L
                            )
                        )
                    }
                }
            } catch (e: Exception) {
            }
            runOnUiThread {
                all = found
                loading.visibility = View.GONE
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val q = search.text.toString().trim().lowercase()
        shown = all.filter { it ->
            val f = it.folder.lowercase()
            val okFolder = when (filter) {
                "Descargas" -> f.startsWith("download")
                "WhatsApp" -> f.contains("whatsapp")
                "Documentos" -> f.startsWith("documents")
                "GigaPDF" -> f.contains("gigapdf")
                else -> true
            }
            okFolder && (q.isEmpty() || it.name.lowercase().contains(q))
        }
        adapter.notifyDataSetChanged()
        empty.visibility = if (shown.isEmpty() && loading.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        empty.text = if (all.isEmpty() && !hasFullAccess())
            "Activa el acceso para ver tus PDF aquí, o toca la carpeta de arriba para buscar en otra ubicación."
        else "No hay PDF que coincidan"
    }

    private fun open(uri: Uri) {
        startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_URI, uri.toString()))
        finish()
    }

    private fun updateMerge() {
        btnMerge.visibility = if (multi && selected.isNotEmpty()) View.VISIBLE else View.GONE
        btnMerge.text = if (selected.size < 2) "Elige al menos 2" else "Unir ${selected.size} PDF"
        btnMerge.isEnabled = selected.size >= 2
    }

    private fun finishMulti() {
        if (selected.size < 2) return
        setResult(RESULT_OK, Intent().putStringArrayListExtra(RESULT_URIS, ArrayList(selected.map { it.toString() })))
        finish()
    }

    private fun keepPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: Exception) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e2: Exception) {
            }
        }
    }

    private inner class FileAdapter : RecyclerView.Adapter<FileAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.name)
            val date: TextView = v.findViewById(R.id.date)
            val more: ImageButton = v.findViewById(R.id.more)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_recent, parent, false))

        override fun getItemCount() = shown.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val it = shown[position]
            h.name.text = it.name
            val folder = it.folder.substringAfterLast('/').ifEmpty { "Teléfono" }
            h.date.text = "$folder · ${DateUtils.getRelativeTimeSpanString(it.date)} · ${Formatter.formatShortFileSize(this@FilesActivity, it.size)}"
            if (multi) {
                val on = it.uri in selected
                h.more.setImageResource(if (on) R.drawable.ic_check else R.drawable.ic_add)
                h.more.imageTintList = ContextCompat.getColorStateList(this@FilesActivity, if (on) R.color.primary else R.color.text_soft)
                val toggle = View.OnClickListener { _ ->
                    if (!selected.remove(it.uri)) selected.add(it.uri)
                    notifyItemChanged(position)
                    updateMerge()
                }
                h.itemView.setOnClickListener(toggle)
                h.more.setOnClickListener(toggle)
            } else {
                h.more.visibility = View.GONE
                h.itemView.setOnClickListener { _ -> open(it.uri) }
            }
        }
    }
}
