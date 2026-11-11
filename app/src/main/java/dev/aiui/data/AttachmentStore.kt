package dev.aiui.data

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import android.provider.OpenableColumns
import android.util.Base64
import dev.aiui.data.db.AttachmentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** A file copied into app storage and waiting to be sent with the next message. */
data class PendingAttachment(
    val kind: String,
    val mimeType: String,
    val fileName: String,
    val localPath: String,
    val sizeBytes: Long,
)

class UnsupportedAttachmentException(message: String) : Exception(message)

class AttachmentStore(private val context: Context) {

    private val dir = File(context.filesDir, "attachments").apply { mkdirs() }

    /** Copies [uri] into app storage. Images are downscaled once here so every later turn resends identical bytes. */
    suspend fun import(uri: Uri): PendingAttachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = queryName(uri) ?: uri.lastPathSegment ?: "file"
        val mime = resolver.getType(uri) ?: guessMime(name)
        when {
            mime.startsWith("image/") -> importImage(uri, name)
            mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true) -> {
                val file = copy(uri, "pdf")
                if (file.length() > MAX_PDF_BYTES) {
                    file.delete()
                    throw UnsupportedAttachmentException("$name is larger than 32 MB, the most a PDF can be.")
                }
                PendingAttachment(AttachmentKind.PDF, "application/pdf", name, file.absolutePath, file.length())
            }
            isTextLike(mime, name) -> {
                val file = copy(uri, "txt")
                if (file.length() > MAX_TEXT_BYTES) {
                    file.delete()
                    throw UnsupportedAttachmentException("$name is larger than 2 MB, the most a text file can be.")
                }
                PendingAttachment(AttachmentKind.TEXT, mime, name, file.absolutePath, file.length())
            }
            else -> throw UnsupportedAttachmentException(
                "$name can't be attached ($mime). Attach images, PDFs, or text and code files.",
            )
        }
    }

    fun delete(path: String) {
        File(path).takeIf { it.parentFile == dir }?.delete()
    }

    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    fun readBase64(path: String): String = Base64.encodeToString(File(path).readBytes(), Base64.NO_WRAP)

    fun readText(path: String): String = File(path).readText(Charsets.UTF_8)

    /** Text of every page, or null when the PDF has no text layer or the API is unavailable. */
    @SuppressLint("NewApi")
    fun pdfText(path: String): String? = runCatching {
        if (SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) < 13) return null
        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                val text = buildString {
                    for (index in 0 until renderer.pageCount) {
                        renderer.openPage(index).use { page ->
                            append("--- Page ${index + 1} ---\n")
                            page.textContents.forEach { append(it.text).append('\n') }
                        }
                    }
                }
                text.takeIf { it.replace(Regex("--- Page \\d+ ---"), "").isNotBlank() }
            }
        }
    }.getOrNull()

    /** Renders the first [maxPages] pages as JPEGs, for servers that take images but not PDFs. */
    fun pdfPagesAsJpeg(path: String, maxPages: Int = 20): List<String> =
        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                (0 until minOf(renderer.pageCount, maxPages)).map { index ->
                    renderer.openPage(index).use { page ->
                        val scale = PDF_PAGE_LONG_EDGE.toFloat() / max(page.width, page.height)
                        val bitmap = Bitmap.createBitmap(
                            (page.width * scale).roundToInt(),
                            (page.height * scale).roundToInt(),
                            Bitmap.Config.ARGB_8888,
                        )
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val out = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                        bitmap.recycle()
                        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                    }
                }
            }
        }

    private fun importImage(uri: Uri, name: String): PendingAttachment {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longEdge = max(info.size.width, info.size.height)
            if (longEdge > MAX_IMAGE_EDGE) {
                val scale = MAX_IMAGE_EDGE.toFloat() / longEdge
                decoder.setTargetSize(
                    (info.size.width * scale).roundToInt(),
                    (info.size.height * scale).roundToInt(),
                )
            }
        }
        val file = newFile("jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        bitmap.recycle()
        val jpegName = name.substringBeforeLast('.', name) + ".jpg"
        return PendingAttachment(AttachmentKind.IMAGE, "image/jpeg", jpegName, file.absolutePath, file.length())
    }

    private fun copy(uri: Uri, extension: String): File {
        val file = newFile(extension)
        val input = context.contentResolver.openInputStream(uri)
            ?: throw UnsupportedAttachmentException("The file couldn't be opened.")
        input.use { stream -> file.outputStream().use { stream.copyTo(it) } }
        return file
    }

    private fun newFile(extension: String) = File(dir, "${UUID.randomUUID()}.$extension")

    private fun queryName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic", "heif" -> "image/heic"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    private fun isTextLike(mime: String, name: String): Boolean {
        if (mime.startsWith("text/")) return true
        if (mime in TEXT_MIME_TYPES) return true
        return name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS
    }

    companion object {
        const val MAX_IMAGE_EDGE = 2048
        private const val PDF_PAGE_LONG_EDGE = 1600
        private const val JPEG_QUALITY = 85
        private const val MAX_PDF_BYTES = 32L * 1024 * 1024
        private const val MAX_TEXT_BYTES = 2L * 1024 * 1024
        private val TEXT_MIME_TYPES = setOf(
            "application/json", "application/xml", "application/javascript", "application/x-yaml",
            "application/yaml", "application/toml", "application/x-sh", "application/sql",
        )
        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "xml", "yaml", "yml", "toml", "ini", "conf",
            "log", "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "c", "h", "cpp", "hpp", "cc", "cs", "go",
            "rs", "swift", "rb", "php", "sh", "zsh", "bash", "sql", "html", "htm", "css", "scss", "gradle",
            "properties", "dart", "lua", "r", "m", "scala", "vue", "svelte", "tex", "env",
        )
    }
}
