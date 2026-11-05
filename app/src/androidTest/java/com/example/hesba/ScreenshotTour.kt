package com.example.hesba

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hesba.auth.AuthRepository
import com.example.hesba.auth.AuthResult
import com.example.hesba.auth.SessionManager
import com.example.hesba.data.HesbaDatabase
import com.example.hesba.data.HesbaRepository
import com.example.hesba.data.Ingredient
import com.example.hesba.data.LigneRecette
import com.example.hesba.data.Recette
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Visite des écrans pour les captures de docs/screenshots (pas un test de logique).
 * - Base Room EN MÉMOIRE injectée : les vraies données du téléphone ne sont jamais lues ni modifiées.
 * - Les préférences (session, réglages, premier lancement) sont sauvegardées puis remises à l'identique.
 * Arguments : -e lang fr|en|ar  -e night 0|1   (lancé par scripts/screenshots.sh)
 * Les images sont écrites dans Android/data/com.example.hesba/files/tour/<lang>-<light|dark>/.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTour {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instr.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val lang = args.getString("lang") ?: "fr"
    private val night = args.getString("night") == "1"
    private lateinit var db: HesbaDatabase
    private lateinit var out: File
    private val savedPrefs = mutableMapOf<String, Map<String, *>>()
    private val prefNames = listOf("session", "reglages")
    private val ids = mutableMapOf<String, Long>()
    private var savedLocales = LocaleListCompat.getEmptyLocaleList()

    @Before
    fun setUp() {
        prefNames.forEach { savedPrefs[it] = HashMap(context.getSharedPreferences(it, Context.MODE_PRIVATE).all) }
        db = Room.inMemoryDatabaseBuilder(context, HesbaDatabase::class.java).build()
        HesbaDatabase.replaceForTests(db)
        out = File(context.getExternalFilesDir(null), "tour/$lang-${if (night) "dark" else "light"}").apply { deleteRecursively(); mkdirs() }
        instr.runOnMainSync {
            savedLocales = AppCompatDelegate.getApplicationLocales()
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang))
            AppCompatDelegate.setDefaultNightMode(if (night) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        }
    }

    @After
    fun tearDown() {
        HesbaDatabase.replaceForTests(null)
        db.close()
        prefNames.forEach { name ->
            val p = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            savedPrefs[name]?.forEach { (k, v) ->
                when (v) {
                    is String -> p.putString(k, v); is Long -> p.putLong(k, v); is Int -> p.putInt(k, v)
                    is Boolean -> p.putBoolean(k, v); is Float -> p.putFloat(k, v)
                }
            }
            p.commit()
        }
        instr.runOnMainSync {
            AppCompatDelegate.setApplicationLocales(savedLocales)
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    @Test
    fun tour() {
        // écrans sans compte
        SessionManager(context).logout()
        shoot("01-onboarding-story", OnboardingActivity::class.java, wait = 6500)
        shoot("02-onboarding-language", Intent(context, OnboardingActivity::class.java).putExtra(OnboardingActivity.EXTRA_PAGE, 1))
        shoot("03-onboarding-account", Intent(context, OnboardingActivity::class.java).putExtra(OnboardingActivity.EXTRA_PAGE, 2))
        shoot("04-login", LoginActivity::class.java)

        // compte vide : premier usage
        val userId = runBlocking { createUser() }
        SessionManager(context).saveUser(userId)
        shoot("05-home-first-use", HomeActivity::class.java)
        shoot("06-recipes-empty", RecettesActivity::class.java)

        // données réalistes
        runBlocking { seed(HesbaRepository(db, userId)) }
        shoot("10-home", HomeActivity::class.java)
        shoot("11-recipes", RecettesActivity::class.java)
        shoot("12-recipe-detail", Intent(context, RecipeDetailActivity::class.java).putExtra(RecipeDetailActivity.EXTRA_ID, ids["cake"]!!), wait = 2500)
        shoot("13-recipe-detail-advisor", Intent(context, RecipeDetailActivity::class.java).putExtra(RecipeDetailActivity.EXTRA_ID, ids["cake"]!!), wait = 2500) { a ->
            val scroll = a.findViewById<androidx.core.widget.NestedScrollView>(R.id.scroll)
            val slider = a.findViewById<android.view.View>(R.id.sliderMargin)
            val r = android.graphics.Rect(); slider.getDrawingRect(r); scroll.offsetDescendantRectToMyCoords(slider, r)
            scroll.scrollTo(0, r.top - 600)
        }
        shoot("14-compose", Intent(context, ComposeRecipeActivity::class.java).putExtra(ComposeRecipeActivity.EXTRA_RECETTE_ID, ids["croissant"]!!))
        shoot("20-ingredients", IngredientsActivity::class.java)
        shoot("21-ingredient-form", Intent(context, NouvelIngredientActivity::class.java).putExtra(NouvelIngredientActivity.EXTRA_INGREDIENT_ID, ids["butter"]!!))
        shoot("30-profile", ProfilActivity::class.java)
        shoot("40-what-if", Intent(context, WhatIfActivity::class.java).putExtra(WhatIfActivity.EXTRA_INGREDIENT, ids["butter"]!!))
        shoot("41-shopping", Intent(context, ShoppingListActivity::class.java).putExtra(ShoppingListActivity.EXTRA_RECIPE, ids["croissant"]!!))
        shoot("42-insights", InsightsActivity::class.java)
        shoot("43-invoices", InvoicesActivity::class.java)
        shoot("50-scan", ScanActivity::class.java)

        // Scanner : une facture française dessinée ici, lue par le VRAI moteur OCR de l'appli (rien n'est simulé).
        shoot("51-scan-reading", scanIntent(), wait = 250)
        shoot("52-scan-review", scanIntent(), wait = 9000)
    }

    /** Image d'une facture simple (texte noir sur blanc), copiée à chaque fois : l'écran de vérification la prend en charge. */
    private fun scanIntent(): Intent {
        val lines = listOf(
            "Moulin du Sahel SARL", "Tél : 73 123 456   MF 1234567/A", "Facture N° 2026-118        Date : 03/11/2026",
            "Désignation      Qté    P.U     Montant",
            "Farine fleur sac 25 kg   2   32,000   64,000", "Sucre 10 kg   1   18,500   18,500",
            "Lait 1L   12   1,350   16,200", "Oeufs plateau 30 pcs  3  12,000  36,000",
            "Total HT   134,700", "TVA 19 %   25,593", "Net à payer   160,293 DT"
        )
        val bmp = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; textSize = 38f; typeface = android.graphics.Typeface.MONOSPACE }
        lines.forEachIndexed { i, l -> c.drawText(l, 80f, 160f + i * 72f, paint) }
        val f = File(context.cacheDir, "tour-invoice-${System.nanoTime()}.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return Intent(context, ScanReviewActivity::class.java)
            .putExtra(ScanReviewActivity.EXTRA_FILES, arrayOf(f.absolutePath))
            .putExtra(ScanReviewActivity.EXTRA_LANGUAGE, "AUTO")
    }

    private fun shoot(name: String, cls: Class<out Activity>, wait: Long = 1800) = shoot(name, Intent(context, cls), wait)

    private fun shoot(name: String, intent: Intent, wait: Long = 1800, prepare: ((Activity) -> Unit)? = null) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ActivityScenario.launch<Activity>(intent).use { scenario ->
            SystemClock.sleep(wait)
            if (prepare != null) { scenario.onActivity(prepare); SystemClock.sleep(600) }
            instr.waitForIdleSync()
            val bmp = instr.uiAutomation.takeScreenshot() ?: return
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    // ---------- données ----------

    private fun t(fr: String, en: String, ar: String) = when (lang) { "ar" -> ar; "en" -> en; else -> fr }

    private suspend fun createUser(): Long {
        val r = AuthRepository(db.utilisateurDao()).inscrire(
            t("Yasmine", "Yasmine", "ياسمين"), t("Pâtisserie El Yasmine", "El Yasmine Bakery", "مرطبات الياسمين"),
            "yasmine@exemple.tn", "motdepasse-1", "avec_employe", true
        )
        return (r as AuthResult.Success).userId
    }

    private suspend fun ing(repo: HesbaRepository, key: String, name: String, unit: String, qty: Double, price: Long?, yield: Double = 100.0) {
        val r = repo.saveIngredient(null, Ingredient(nom = name, unite = unit, quantiteEmballage = qty, prixMillimes = price, rendementPourcent = yield))
        ids[key] = (r as HesbaRepository.IngredientSave.Saved).id
    }

    private fun line(key: String, qty: Double, unit: String) = LigneRecette(recetteId = 0, ingredientId = ids[key]!!, quantite = qty, unite = unit)

    private suspend fun seed(repo: HesbaRepository) {
        ing(repo, "flour", t("Farine", "Flour", "دقيق"), "g", 1000.0, 1350)
        ing(repo, "sugar", t("Sucre", "Sugar", "سكر"), "g", 1000.0, 1600)
        ing(repo, "butter", t("Beurre", "Butter", "زبدة"), "g", 200.0, 3900)
        ing(repo, "eggs", t("Œufs", "Eggs", "بيض"), "piece", 30.0, 13500)
        ing(repo, "milk", t("Lait entier", "Whole milk", "حليب كامل الدسم"), "ml", 1000.0, 1450)
        ing(repo, "almonds", t("Amandes décortiquées", "Shelled almonds", "لوز مقشر"), "g", 1000.0, 42000, yield = 90.0)
        ing(repo, "choc", t("Chocolat noir 70 %", "Dark chocolate 70%", "شوكولاتة داكنة 70٪"), "g", 200.0, 7200)
        ing(repo, "yeast", t("Levure boulangère", "Baker's yeast", "خميرة الخبز"), "g", 500.0, 6500)
        ing(repo, "oil", t("Huile d'olive", "Olive oil", "زيت زيتون"), "ml", 1000.0, 18500)
        ing(repo, "pista", t("Pistaches", "Pistachios", "فستق"), "g", 1000.0, null)

        ids["cake"] = repo.saveRecipe(null,
            Recette(nom = t("Gâteau au chocolat", "Chocolate cake", "كعكة الشوكولاتة"), nombreParts = 12, margeVoulue = 60.0, prixVenteMillimes = 2800, emballageParPartMillimes = 150, gazParLotMillimes = 500),
            listOf(line("flour", 250.0, "g"), line("sugar", 200.0, "g"), line("butter", 200.0, "g"), line("eggs", 4.0, "piece"), line("choc", 200.0, "g")))
        ids["croissant"] = repo.saveRecipe(null,
            Recette(nom = t("Croissant au beurre", "Butter croissant", "كرواسون بالزبدة"), nombreParts = 16, margeVoulue = 65.0, prixVenteMillimes = 1200),
            listOf(line("flour", 500.0, "g"), line("butter", 250.0, "g"), line("milk", 150.0, "ml"), line("sugar", 60.0, "g"), line("yeast", 10.0, "g"), line("eggs", 1.0, "piece")))
        ids["horns"] = repo.saveRecipe(null,
            Recette(nom = t("Cornes de gazelle", "Gazelle horns", "كعب الغزال"), nombreParts = 30, margeVoulue = 55.0, prixVenteMillimes = 900),
            listOf(line("almonds", 500.0, "g"), line("sugar", 250.0, "g"), line("flour", 300.0, "g"), line("butter", 100.0, "g")))
        ids["baklawa"] = repo.saveRecipe(null,
            Recette(nom = t("Baklawa aux pistaches", "Pistachio baklava", "بقلاوة بالفستق"), nombreParts = 24, margeVoulue = 60.0),
            listOf(line("pista", 400.0, "g"), line("butter", 200.0, "g"), line("flour", 300.0, "g"), line("sugar", 150.0, "g")))
        ids["bread"] = repo.saveRecipe(null,
            Recette(nom = t("Pain au lait", "Milk bread", "خبز بالحليب"), nombreParts = 10, margeVoulue = 50.0, prixVenteMillimes = 700),
            listOf(line("flour", 500.0, "g"), line("milk", 250.0, "ml"), line("butter", 50.0, "g"), line("sugar", 40.0, "g"), line("yeast", 10.0, "g"), line("eggs", 1.0, "piece")))

        // une hausse de prix récente (historique + alerte sur l'Accueil)
        val butter = db.ingredientDao().parId(ids["butter"]!!)!!
        repo.saveIngredient(butter, butter.copy(prixMillimes = 4400))
    }
}
