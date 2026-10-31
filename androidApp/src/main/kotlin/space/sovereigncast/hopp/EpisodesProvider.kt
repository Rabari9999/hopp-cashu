package space.sovereigncast.hopp

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import org.koin.mp.KoinPlatform
import space.sovereigncast.hopp.data.DownloadFiles
import space.sovereigncast.hopp.db.HoppDatabase
import java.io.File
import java.io.FileNotFoundException
import app.cash.sqldelight.async.coroutines.awaitAsList
import kotlinx.coroutines.runBlocking

/**
 * Hopp's downloaded episodes as a place in the system's Files app (device
 * feedback 2026-10-14: where are they?). They live in Hopp's private storage,
 * which no file manager can reach since Android 11 — this provider shows them
 * there, read only: deleting and downloading stay Hopp's, so its library and
 * the files never disagree. Only the system (MANAGE_DOCUMENTS) binds to it;
 * the listener picks a file there, nobody else browses.
 *
 * A file is named after its show and episode, not Hopp's "123.mp3".
 */
class EpisodesProvider : DocumentsProvider() {
    override fun onCreate() = true

    private val dir get() = File(KoinPlatform.getKoin().get<DownloadFiles>().dir)

    /** Downloaded episodes by file name: show and title. */
    private fun files(): List<Pair<File, String>> {
        // The provider answers the system synchronously, on its binder thread; the queries are asynchronous (CLAUDE.md).
        val rows = runBlocking { KoinPlatform.getKoin().get<HoppDatabase>().episodeQueries.downloadedFile().awaitAsList() }
        return rows.mapNotNull { r ->
            val file = r.download_path?.let(::File)?.takeIf { it.isFile && it.parentFile == dir } ?: return@mapNotNull null
            file to displayName(r.podcast_title.orEmpty(), r.title.orEmpty(), file.extension)
        }
    }

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: ROOT_COLUMNS).apply {
        newRow().add(Root.COLUMN_ROOT_ID, ROOT).add(Root.COLUMN_DOCUMENT_ID, ROOT)
            .add(Root.COLUMN_TITLE, context!!.getString(R.string.app_name))
            .add(Root.COLUMN_SUMMARY, context!!.getString(R.string.files_summary))
            .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY)
            .add(Root.COLUMN_AVAILABLE_BYTES, dir.usableSpace)
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
        if (documentId == ROOT) folder(this)
        else files().firstOrNull { it.first.name == documentId }?.let { (f, name) -> file(this, f, name) } ?: throw FileNotFoundException(documentId)
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            if (parentDocumentId == ROOT) files().forEach { (f, name) -> file(this, f, name) }
        }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode != "r") throw UnsupportedOperationException("Hopp's episodes are read only")
        val file = files().firstOrNull { it.first.name == documentId }?.first ?: throw FileNotFoundException(documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String) = parentDocumentId == ROOT && documentId != ROOT

    private fun folder(c: MatrixCursor) {
        c.newRow().add(Document.COLUMN_DOCUMENT_ID, ROOT).add(Document.COLUMN_DISPLAY_NAME, context!!.getString(R.string.app_name))
            .add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR).add(Document.COLUMN_FLAGS, 0)
    }

    private fun file(c: MatrixCursor, f: File, name: String) {
        c.newRow().add(Document.COLUMN_DOCUMENT_ID, f.name).add(Document.COLUMN_DISPLAY_NAME, name)
            .add(Document.COLUMN_MIME_TYPE, MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension) ?: "audio/*")
            .add(Document.COLUMN_SIZE, f.length()).add(Document.COLUMN_LAST_MODIFIED, f.lastModified()).add(Document.COLUMN_FLAGS, 0)
    }

    companion object {
        const val ROOT = "episodes"
        fun authority(context: android.content.Context) = context.packageName + ".episodes"
        private val ROOT_COLUMNS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_ICON, Root.COLUMN_FLAGS, Root.COLUMN_AVAILABLE_BYTES)
        private val DOCUMENT_COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)

        /** "Show – Episode.mp3", without what file systems refuse. */
        internal fun displayName(show: String, title: String, extension: String) = space.sovereigncast.hopp.data.episodeFileName(show, title, extension)
    }
}
