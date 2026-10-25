package com.topognss.app.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

enum class ImportFormat { DXF, KML }

/** Determina el formato a partir de la extensión del nombre de archivo (no del mime type: el
 * selector de documentos de Android rara vez conoce "application/dxf" o similar). */
fun detectFormat(fileName: String?): ImportFormat? {
    val lower = (fileName ?: "").lowercase()
    return when {
        lower.endsWith(".dxf") -> ImportFormat.DXF
        lower.endsWith(".kml") || lower.endsWith(".kmz") -> ImportFormat.KML
        else -> null
    }
}

/** Nombre visible del documento elegido con el selector del sistema (SAF). */
fun queryDisplayName(context: Context, uri: Uri): String? {
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx)
            }
        }
    }
    return uri.lastPathSegment
}

/**
 * Lee el texto de un DXF o KML elegido con el selector de documentos. Si es un KMZ (KML
 * comprimido), busca el primer `.kml` dentro del zip y devuelve su contenido.
 */
fun readImportableText(context: Context, uri: Uri, fileName: String?): String? {
    val lower = (fileName ?: uri.lastPathSegment ?: "").lowercase()
    val resolver = context.contentResolver
    if (lower.endsWith(".kmz")) {
        resolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name.lowercase().endsWith(".kml")) {
                        return BufferedReader(InputStreamReader(zip)).readText()
                    }
                    entry = zip.nextEntry
                }
            }
        }
        return null
    }
    return resolver.openInputStream(uri)?.use { input -> BufferedReader(InputStreamReader(input)).readText() }
}
