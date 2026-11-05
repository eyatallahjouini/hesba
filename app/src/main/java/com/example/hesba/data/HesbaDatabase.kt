package com.example.hesba.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Base locale HESBA (SQLite via Room).
 * v1 : base d'origine (prix en Double).
 * v2 : montants en millimes entiers, rendement, coûts cachés, historique des prix, factures, synonymes.
 * Jamais de fallbackToDestructiveMigration : chaque version a une migration testée (voir Migrations.kt).
 */
@Database(
    entities = [
        Utilisateur::class,
        Ingredient::class,
        Recette::class,
        LigneRecette::class,
        Vente::class,
        PrixHistorique::class,
        Facture::class,
        Synonyme::class
    ],
    version = 2,
    exportSchema = true
)
abstract class HesbaDatabase : RoomDatabase() {

    abstract fun utilisateurDao(): UtilisateurDao
    abstract fun ingredientDao(): IngredientDao
    abstract fun recetteDao(): RecetteDao
    abstract fun ligneRecetteDao(): LigneRecetteDao
    abstract fun prixHistoriqueDao(): PrixHistoriqueDao
    abstract fun factureDao(): FactureDao
    abstract fun synonymeDao(): SynonymeDao
    abstract fun transfertDao(): TransfertDao

    companion object {
        const val NAME = "hesba.db"

        @Volatile private var INSTANCE: HesbaDatabase? = null

        fun getInstance(context: Context): HesbaDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(context.applicationContext, HesbaDatabase::class.java, NAME)
                    .addMigrations(*Migrations.ALL)
                    .build()
                    .also { INSTANCE = it }
            }

        /**
         * Tests sur émulateur uniquement (visite d'écrans) : remplace la base par une base en mémoire,
         * pour ne jamais toucher aux données réelles du téléphone. null = revenir à la vraie base.
         */
        @androidx.annotation.VisibleForTesting
        fun replaceForTests(db: HesbaDatabase?) = synchronized(this) { INSTANCE = db }

        /** Ferme la base (restauration d'une sauvegarde). Le prochain getInstance la rouvre. */
        fun closeInstance() = synchronized(this) {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}
