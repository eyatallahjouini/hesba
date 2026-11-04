package com.example.hesba

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hesba.data.HesbaDatabase
import com.example.hesba.data.Migrations
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration 1 → 2 avec de vraies données, pour les deux variantes de v1 :
 *  - v1 « Eya » (schemas/1.json, version publiée) ;
 *  - v1 « Aya » (sauvegarde non versionnée : colonnes en plus, politique de confidentialité en moins).
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        HesbaDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @After
    fun cleanup() {
        context.deleteDatabase("migration-eya")
        context.deleteDatabase("migration-aya")
    }

    @Test
    fun v1EyaVersV2GardeToutesLesDonnees() {
        helper.createDatabase("migration-eya", 1).apply {
            // aucun compte (la connexion n'existait pas dans la version d'Eya) : recette au nom de l'utilisateur 1
            execSQL("INSERT INTO ingredient (id, nom, unite, quantiteEmballage, prixPaye, dateMiseAJour) VALUES (1, 'Harissa', 'g', 1000.0, 6.0, 111)")
            execSQL("INSERT INTO ingredient (id, nom, unite, quantiteEmballage, prixPaye, dateMiseAJour) VALUES (2, 'Pain', 'piece', 1.0, 0.25, 222)")
            execSQL("INSERT INTO ingredient (id, nom, unite, quantiteEmballage, prixPaye, dateMiseAJour) VALUES (3, 'Beurre', 'g', 250.0, 2.675, 333)")
            execSQL("INSERT INTO recette (id, utilisateurId, nom, nombreParts, margeVoulue, coutParPart, prixVente, dateCreation) VALUES (10, 1, 'Sandwich', 10, 40.0, 0.28, 0.4666666666666667, 444)")
            execSQL("INSERT INTO recette (id, utilisateurId, nom, nombreParts, margeVoulue, coutParPart, prixVente, dateCreation) VALUES (11, 1, 'Sans prix', 4, 30.0, 0.0, NULL, 555)")
            execSQL("INSERT INTO ligne_recette (id, recetteId, ingredientId, quantite, unite) VALUES (1, 10, 1, 50.0, 'g')")
            execSQL("INSERT INTO ligne_recette (id, recetteId, ingredientId, quantite, unite) VALUES (2, 10, 2, 10.0, 'piece')")
            execSQL("INSERT INTO vente (id, recetteId, nomRecette, quantite, prixVenteUnitaire, coutUnitaire, date) VALUES (1, 10, 'Sandwich', 3, 0.5, 0.28, 666)")
            close()
        }

        val db = helper.runMigrationsAndValidate("migration-eya", 2, true, Migrations.MIGRATION_1_2)

        db.query("SELECT id, utilisateurId, prixMillimes, rendementPourcent, quantiteEmballage, dateMiseAJour FROM ingredient ORDER BY id").use { c ->
            c.moveToFirst(); assertEquals(6000L, c.getLong(2)); assertEquals(100.0, c.getDouble(3), 0.0); assertEquals(-1L, c.getLong(1)); assertEquals(111L, c.getLong(5))
            c.moveToNext(); assertEquals(250L, c.getLong(2))
            c.moveToNext(); assertEquals(2675L, c.getLong(2)); assertEquals(250.0, c.getDouble(4), 0.0)   // 2,675 → 2675 millimes exactement
        }
        db.query("SELECT utilisateurId, coutParPartMillimes, prixVenteMillimes, margeVoulue, dateCreation, emballageParPartMillimes FROM recette ORDER BY id").use { c ->
            c.moveToFirst()
            assertEquals(-1L, c.getLong(0))      // utilisateur 1 inexistant → mode essai, données visibles
            assertEquals(280L, c.getLong(1))
            assertEquals(467L, c.getLong(2))     // 0,46666… → 0,467 (seul l'arrondi au millime change)
            assertEquals(40.0, c.getDouble(3), 0.0)
            assertEquals(444L, c.getLong(4))
            assertEquals(0L, c.getLong(5))
            c.moveToNext()
            assertEquals(true, c.isNull(2))      // prix de vente absent reste absent
        }
        db.query("SELECT COUNT(*) FROM ligne_recette").use { c -> c.moveToFirst(); assertEquals(2, c.getInt(0)) }
        db.query("SELECT quantite, prixVenteUnitaire FROM vente").use { c -> c.moveToFirst(); assertEquals(3, c.getInt(0)); assertEquals(0.5, c.getDouble(1), 0.0) }
        db.query("SELECT COUNT(*) FROM prix_historique").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        db.close()

        // l'appli ouvre la base migrée et retrouve le même coût qu'avant
        val room = Room.databaseBuilder(context, HesbaDatabase::class.java, "migration-eya").addMigrations(*Migrations.ALL).build()
        runBlocking {
            val ingredients = room.ingredientDao().listePour(-1).associateBy { it.id }
            val recette = room.recetteDao().parId(10)!!
            val cost = RecipeCosting.compute(recette, room.ligneRecetteDao().listePour(10), ingredients)
            assertEquals(280L, cost.costPerPortionMillimes)
        }
        room.close()
    }

    @Test
    fun v1AyaVersV2() {
        // Base créée par la sauvegarde d'Aya (même numéro de version 1, schéma différent)
        val file = context.getDatabasePath("migration-aya")
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).apply {
            execSQL("CREATE TABLE utilisateur (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, prenom TEXT NOT NULL, email TEXT NOT NULL, motDePasseHash TEXT NOT NULL, activite TEXT NOT NULL, nombreEmployes INTEGER NOT NULL, salaireMoyenEmploye REAL NOT NULL, loyerMensuel REAL NOT NULL, volumeMensuel INTEGER NOT NULL, dateCreation INTEGER NOT NULL)")
            execSQL("CREATE UNIQUE INDEX index_utilisateur_email ON utilisateur (email)")
            execSQL("CREATE TABLE ingredient (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, nom TEXT NOT NULL, unite TEXT NOT NULL, quantiteEmballage REAL NOT NULL, prixPaye REAL NOT NULL, dateMiseAJour INTEGER NOT NULL, utilisateurId INTEGER NOT NULL)")
            execSQL("CREATE TABLE recette (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, utilisateurId INTEGER NOT NULL, nom TEXT NOT NULL, nombreParts INTEGER NOT NULL, margeVoulue REAL NOT NULL, coutParPart REAL NOT NULL, prixVente REAL, tempsPreparationMinutes INTEGER NOT NULL, dateCreation INTEGER NOT NULL)")
            execSQL("CREATE TABLE ligne_recette (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, recetteId INTEGER NOT NULL, ingredientId INTEGER NOT NULL, quantite REAL NOT NULL, unite TEXT NOT NULL, FOREIGN KEY(recetteId) REFERENCES recette(id) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(ingredientId) REFERENCES ingredient(id) ON UPDATE NO ACTION ON DELETE RESTRICT )")
            execSQL("CREATE INDEX index_ligne_recette_ingredientId ON ligne_recette (ingredientId)")
            execSQL("CREATE UNIQUE INDEX index_ligne_recette_recetteId_ingredientId ON ligne_recette (recetteId, ingredientId)")
            execSQL("CREATE TABLE vente (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, recetteId INTEGER, nomRecette TEXT NOT NULL, quantite INTEGER NOT NULL, prixVenteUnitaire REAL NOT NULL, coutUnitaire REAL NOT NULL, date INTEGER NOT NULL, FOREIGN KEY(recetteId) REFERENCES recette(id) ON UPDATE NO ACTION ON DELETE SET NULL )")
            execSQL("CREATE INDEX index_vente_recetteId ON vente (recetteId)")
            execSQL("CREATE INDEX index_vente_date ON vente (date)")
            execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '772bfe5727f4d25ecba670a6cb3faca2')")
            execSQL("INSERT INTO utilisateur VALUES (5, 'Aya', 'aya@hesba.tn', 'sel:hash', 'avec_employe', 2, 1200.5, 900.0, 500, 1)")
            execSQL("INSERT INTO ingredient VALUES (1, 'Farine', 'g', 1000.0, 1.2, 2, 5)")
            execSQL("INSERT INTO ingredient VALUES (2, 'Sucre', 'g', 1000.0, 1.85, 3, -1)")
            execSQL("INSERT INTO recette VALUES (7, 5, 'Gâteau', 8, 35.0, 1.234, 1.9, 45, 4)")
            execSQL("INSERT INTO ligne_recette VALUES (1, 7, 1, 500.0, 'g')")
            version = 1
            close()
        }

        val db = helper.runMigrationsAndValidate("migration-aya", 2, true, Migrations.MIGRATION_1_2)
        db.query("SELECT politiqueConfidentialiteAcceptee, nombreEmployes, salaireMoyenMillimes, loyerMensuelMillimes, volumeMensuel, heuresTravailMois, nomCommerce FROM utilisateur").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0)); assertEquals(2, c.getInt(1)); assertEquals(1_200_500L, c.getLong(2))
            assertEquals(900_000L, c.getLong(3)); assertEquals(500, c.getInt(4)); assertEquals(0, c.getInt(5)); assertEquals("", c.getString(6))
        }
        db.query("SELECT utilisateurId, prixMillimes FROM ingredient ORDER BY id").use { c ->
            c.moveToFirst(); assertEquals(5L, c.getLong(0)); assertEquals(1200L, c.getLong(1))
            c.moveToNext(); assertEquals(-1L, c.getLong(0)); assertEquals(1850L, c.getLong(1))   // donnée invitée conservée
        }
        db.query("SELECT utilisateurId, tempsPreparationMinutes, coutParPartMillimes, prixVenteMillimes FROM recette").use { c ->
            c.moveToFirst(); assertEquals(5L, c.getLong(0)); assertEquals(45, c.getInt(1)); assertEquals(1234L, c.getLong(2)); assertEquals(1900L, c.getLong(3))
        }
        db.close()
    }

    @Test
    fun baseNeuveEnV2() {
        val room = Room.inMemoryDatabaseBuilder(context, HesbaDatabase::class.java).build()
        runBlocking { assertNull(room.recetteDao().parId(1)) }
        room.close()
    }
}
