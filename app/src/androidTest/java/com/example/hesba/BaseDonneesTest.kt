package com.example.hesba
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hesba.data.HesbaDatabase
import com.example.hesba.data.Ingredient
import com.example.hesba.data.LigneRecette
import com.example.hesba.data.Recette
import com.example.hesba.data.Utilisateur
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaseDonneesTest {

    private lateinit var db: HesbaDatabase
    private val tag = "HESBA_TEST"
    private val tables = listOf("utilisateur", "ingredient", "recette", "ligne_recette", "vente")

    @Before
    fun creerBase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Base temporaire en mémoire : la vraie hesba.db n'est jamais touchée
        db = Room.inMemoryDatabaseBuilder(context, HesbaDatabase::class.java).build()
    }

    @After
    fun fermerBase() {
        db.close()
    }

    private fun compter(table: String): Int {
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { c ->
            c.moveToFirst()
            return c.getInt(0)
        }
    }

    @Test
    fun les5TablesExistent() {
        val noms = mutableListOf<String>()
        db.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type='table' " +
                    "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' " +
                    "AND name != 'room_master_table'"
        ).use { c -> while (c.moveToNext()) noms.add(c.getString(0)) }

        Log.d(tag, "Tables trouvées : $noms")
        assertEquals(tables.toSet(), noms.toSet())
    }

    @Test
    fun ajouterAfficherSupprimer() {
        runBlocking {
            // 1. ÉTAT INITIAL : tout est vide
            tables.forEach { assertEquals("$it doit être vide", 0, compter(it)) }

            // 2. AJOUT
            db.utilisateurDao().inserer(
                Utilisateur(
                    prenom = "Test", email = "test@hesba.tn", motDePasseHash = "hash",
                    activite = "Pâtisserie", politiqueConfidentialiteAcceptee = true
                )
            )
            val farineId = db.ingredientDao().inserer(
                Ingredient(nom = "Farine", unite = "g", quantiteEmballage = 1000.0, prixPaye = 1.5)
            )
            val recetteId = db.recetteDao().inserer(
                Recette(nom = "Gâteau", nombreParts = 8, margeVoulue = 30.0)
            )
            db.ligneRecetteDao().inserer(
                LigneRecette(recetteId = recetteId, ingredientId = farineId, quantite = 250.0, unite = "g")
            )
            // Pas encore de VenteDao : insertion directe en SQL
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO vente (recetteId, nomRecette, quantite, prixVenteUnitaire, coutUnitaire, date) " +
                        "VALUES ($recetteId, 'Gâteau', 2, 5.0, 3.0, 0)"
            )

            // 3. AFFICHAGE (visible dans Logcat, filtre : HESBA_TEST)
            val ingredients = db.ingredientDao().tous().first()
            val recettes = db.recetteDao().toutes().first()
            val lignes = db.ligneRecetteDao().parRecette(recetteId).first()
            Log.d(tag, "Ingrédients : $ingredients")
            Log.d(tag, "Recettes : $recettes")
            Log.d(tag, "Lignes : $lignes")
            tables.forEach { Log.d(tag, "$it : ${compter(it)} ligne(s)") }

            assertEquals(1, ingredients.size)
            assertEquals(1, recettes.size)
            assertEquals(1, lignes.size)
            assertEquals(0.0015, ingredients[0].prixParUnite, 1e-9)
            tables.forEach { assertEquals("$it doit avoir 1 ligne", 1, compter(it)) }

            // 4. SUPPRESSION (dans l'ordre imposé par les clés étrangères)
            db.ligneRecetteDao().supprimerParRecette(recetteId)
            db.openHelper.writableDatabase.execSQL("DELETE FROM vente")
            db.recetteDao().supprimer(recettes[0])
            db.ingredientDao().supprimer(ingredients[0])
            db.openHelper.writableDatabase.execSQL("DELETE FROM utilisateur")

            // 5. RETOUR À L'ÉTAT INITIAL : tout est vide
            tables.forEach { assertEquals("$it doit être vide", 0, compter(it)) }
            Log.d(tag, "Retour à l'état initial : les 5 tables sont vides")
        }
    }
}
