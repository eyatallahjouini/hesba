package com.example.hesba.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.File

/**
 * Sauvegarde locale de la base : copie du fichier SQLite vers un fichier choisi par l'utilisateur
 * (carte SD, Drive via le sélecteur Android…). Rien n'est envoyé par l'appli elle-même.
 * Les photos de recettes et images de factures ne sont pas incluses (seulement les données).
 */
object BackupManager {

    sealed class RestoreResult {
        object Ok : RestoreResult()
        object NotHesba : RestoreResult()
        object TooNew : RestoreResult()
        object Unreadable : RestoreResult()
    }

    private const val CURRENT_VERSION = 2
    private val REQUIRED_TABLES = setOf("utilisateur", "ingredient", "recette", "ligne_recette")

    fun export(context: Context, db: HesbaDatabase, target: Uri): Boolean = runCatching {
        // écrit le journal WAL dans le fichier principal avant la copie
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        val source = context.getDatabasePath(HesbaDatabase.NAME)
        context.contentResolver.openOutputStream(target, "w")!!.use { out -> source.inputStream().use { it.copyTo(out) } }
        true
    }.getOrDefault(false)

    /** Vérifie le fichier, puis remplace la base. L'appli doit ensuite repartir de l'écran de connexion. */
    fun restore(context: Context, source: Uri): RestoreResult {
        val temp = File(context.cacheDir, "restauration.db")
        try {
            context.contentResolver.openInputStream(source)!!.use { input -> temp.outputStream().use { input.copyTo(it) } }
            val header = temp.inputStream().use { s -> ByteArray(16).also { s.read(it) } }
            if (String(header, Charsets.US_ASCII) != "SQLite format 3\u0000") return RestoreResult.NotHesba
            val check = SQLiteDatabase.openDatabase(temp.path, null, SQLiteDatabase.OPEN_READONLY)
            val version: Int
            val tables = mutableSetOf<String>()
            try {
                version = check.version
                check.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c -> while (c.moveToNext()) tables += c.getString(0) }
            } finally {
                check.close()
            }
            if (!tables.containsAll(REQUIRED_TABLES)) return RestoreResult.NotHesba
            if (version > CURRENT_VERSION) return RestoreResult.TooNew

            HesbaDatabase.closeInstance()
            val target = context.getDatabasePath(HesbaDatabase.NAME)
            File(target.path + "-wal").delete()
            File(target.path + "-shm").delete()
            temp.copyTo(target, overwrite = true)
            return RestoreResult.Ok
        } catch (e: Exception) {
            return RestoreResult.Unreadable
        } finally {
            temp.delete()
        }
    }
}
