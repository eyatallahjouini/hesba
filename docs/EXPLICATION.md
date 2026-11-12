# HESBA expliqué en détail

Ce document explique **le principe de l'appli**, **tout le code** (dossier par dossier), **les formules**,
**la base de données**, **le scanner**, **le design « Le Ticket »** et **les tests**, pour pouvoir présenter
et défendre le projet. À la fin : les questions qu'un jury peut poser, avec les réponses.

---

## 0. Le principe

### Le problème
Une petite pâtisserie, un fast-food ou un petit restaurant tunisien fixe souvent ses prix « au feeling »
ou en copiant le voisin. Quand le beurre ou la farine augmente, le coût de chaque part augmente aussi,
mais le prix de vente ne bouge pas : **la marge fond sans qu'on s'en rende compte**.

### La solution HESBA (« حسبة » = un calcul)
1. On note ses **ingrédients** avec le prix payé (ex. beurre 200 g à 4,400 DT).
2. On **compose une recette** : quelles quantités, et combien de parts elle donne.
3. HESBA calcule **le vrai coût d'une part**, en dinars et millimes, pertes et coûts cachés compris.
4. On choisit une **marge voulue** (ex. 60 %) : HESBA donne le **prix de vente conseillé**.
5. Quand un prix change (à la main ou en **scannant une facture**), toutes les recettes sont recalculées
   et l'**Accueil prévient** les recettes qui passent sous leur marge.

### Les règles de base
* **100 % gratuit**, sans abonnement, sans serveur.
* **Les données restent sur le téléphone** (base SQLite privée). L'appli n'a aucun serveur et n'envoie
  pas tes recettes ni tes prix. (La bibliothèque ML Kit de Google, utilisée pour lire les factures, déclare
  l'accès Internet dans le manifeste ; son modèle de lecture est inclus dans l'appli et marche hors ligne.)
* **Aucun chiffre inventé** : pas de vente simulée, pas d'IA qui décide d'un prix. L'OCR lit et propose,
  l'utilisateur confirme, les calculs sont des formules exactes.
* Hors périmètre (retirés volontairement) : bilan mensuel, suivi des ventes, seuil de rentabilité.

### Le parcours d'un utilisateur
```
1er lancement : histoire → langue → créer un compte
        ↓
Accueil : « Commence par noter ton premier ingrédient »
        ↓
Ingrédients : + Farine 1 kg 1,350 DT, Beurre 200 g 4,400 DT…
        ↓
Mes recettes : + Gâteau au chocolat, 12 parts, marge voulue 60 %
        ↓
Fiche recette : coût par part 1,363 DT, prix conseillé 3,408 DT, ticket détaillé
        ↓
Plus tard : facture du fournisseur scannée → prix mis à jour → recettes recalculées
        ↓
Accueil : carte « 4 à corriger » et « À faire maintenant : Cornes de gazelle → Mettre à 1,950 DT »
```

---

## 1. L'architecture (comment le code est organisé)

```
┌──────────────── Écrans : Activities + layouts XML ─────────────────┐
│ Onboarding · Login · Accueil · Mes recettes · Fiche · Composer     │
│ Ingrédients · Profil · Simulateur · Courses · Scanner · Analyses   │
│ ui/ : thème, formats, mouvement, vues sur mesure (ticket, barre…)  │
└───────────────┬────────────────────────────────────────────────────┘
                │ observent (Flow) / demandent une action
┌───────────────▼──────── Logique : Kotlin pur, sans Android ─────────┐
│ CostCalculator : toutes les formules, en BigDecimal                 │
│ domain/ : argent, unités, recherche, simulateur, courses, alertes,  │
│           phrase de l'Accueil, parseur de factures, correspondance  │
└───────────────┬────────────────────────────────────────────────────┘
                │ lit / écrit
┌───────────────▼──────── Données : Room sur SQLite ─────────────────┐
│ HesbaRepository : SEUL endroit qui écrit, transactions             │
│ HesbaDatabase v2 + Migrations (1 → 2) · DAO · entités              │
└────────────────────────────────────────────────────────────────────┘
```

**Pourquoi trois étages ?**
* La **logique ne dépend pas d'Android** : on la teste sur l'ordinateur en quelques secondes
  (66 tests unitaires), sans émulateur.
* Les **écrans ne calculent rien eux-mêmes** : ils appellent `CostCalculator` (via `RecipeCosting`). Un seul
  endroit pour les formules = un seul endroit à vérifier.
* Le **repository est le seul à écrire** : c'est là qu'on garantit « nouveau prix + historique + recalcul »
  dans **une seule transaction** (tout ou rien).

**Choix techniques** : Kotlin + vues XML (pas de Jetpack Compose), Room 2.7 sur SQLite, Material 3,
coroutines + Flow, CameraX, ML Kit, Tesseract. minSdk 24 (Android 7), cible Android 17 (API 37).

### Le flux réactif (pourquoi l'écran se met à jour tout seul)
Room renvoie des `Flow` : dès qu'une table change, le Flow émet la nouvelle valeur.
`HesbaRepository.workspace()` combine 4 Flows (utilisateur, ingrédients, recettes, lignes) en un objet
`Workspace`. Chaque écran fait :

```kotlin
repo.workspace().collect { ws -> afficher(ws) }   // relancé à chaque changement en base
```

Exemple : tu modifies le prix du beurre → la table `ingredient` change → `workspace()` émet → Mes recettes,
la fiche et l'Accueil se redessinent avec les nouveaux coûts, sans code de « rafraîchissement ».

---

## 2. Visite du code, fichier par fichier

Chemin de base : `app/src/main/java/com/example/hesba/`.

### 2.1 Les écrans (racine du package)

| Fichier | Ce qu'il fait |
|---|---|
| `HesbaApp.kt` | Démarrage de l'appli : applique le thème choisi (clair / sombre / système). |
| `MainActivity.kt` | Aiguillage : connecté → Accueil ; jamais lancé → premier lancement ; sinon → connexion. |
| `OnboardingActivity.kt` | Premier lancement en 3 pages : histoire animée (3 phrases + ticket qui se remplit), choix de la langue (appliquée tout de suite, RTL en arabe), créer un compte / se connecter. Montré une seule fois (`Settings.onboardingDone`). |
| `LoginActivity.kt` / `RegisterActivity.kt` | Connexion et inscription locales (voir `auth/`), mode « essayer sans compte ». |
| `HomeActivity.kt` | Accueil en grille Bento : salutation selon l'heure, marge moyenne, scanner, recettes à corriger, prix qui a bougé, une action à faire (prix conseillé en un geste), meilleure marge, simulateur, courses. Sans données, la carte « À faire maintenant » guide vers le premier ingrédient puis la première recette. |
| `RecettesActivity.kt` | Mes recettes : carte de restaurant, recherche fixe en haut, tri nom / coût / marge, état « aucun résultat », bouton carré d'ajout. |
| `RecipeDetailActivity.kt` | Fiche recette : coût par part, ticket détaillé « imprimé », prix pratiqué et marge réelle, conseiller de marge (curseur), actions (courses, dupliquer, PDF, modifier, supprimer). |
| `ComposeRecipeActivity.kt` | Créer / modifier une recette : lignes d'ingrédients, unités, coûts cachés, marge, prix ; coût recalculé à chaque frappe. |
| `IngredientsActivity.kt` | Liste des ingrédients en « ardoise du marché », recherche, prix ramené au kg / L / pièce. |
| `NouvelIngredientActivity.kt` | Ajouter / modifier un ingrédient (unité, emballage, prix, rendement, autres noms, historique) ; suppression protégée. |
| `ProfilActivity.kt` | Commerce, charges (loyer, salaires…), langue, thème, sauvegarde / restauration, factures, mot de passe, déconnexion. |
| `WhatIfActivity.kt` | Simulateur « et si » : changer un prix (+x %, +x DT, nouveau prix) et voir l'effet ; rien n'est enregistré sans confirmation. |
| `ShoppingListActivity.kt` | Liste de courses : recette + nombre de parts → quantités à acheter et coût estimé. |
| `ScanActivity.kt` | Scanner : caméra (CameraX), galerie, PDF ; choix Auto / Français / العربية. |
| `ScanReviewActivity.kt` | Lecture OCR, vérification ligne par ligne, confirmation, résultat (recettes touchées). |
| `InvoicesActivity.kt` | Factures enregistrées (images privées, suppression). |
| `InsightsActivity.kt` | Évolution des prix : plus fortes hausses, recettes les plus touchées (données réelles). |
| `BottomNav.kt` | Les 4 onglets du bas et la navigation entre eux. |
| `CostCalculator.kt` | **Toutes les formules** (section 4). |
| `RecipeCosting.kt` | Passe des tables de la base (`Recette`, `LigneRecette`, `Ingredient`, `Utilisateur`) aux entrées de `CostCalculator`. |

### 2.2 `data/` : la base de données

| Fichier | Rôle |
|---|---|
| `HesbaDatabase.kt` | Déclaration Room (version 2, tables, migrations). Jamais de migration destructive. |
| `Migrations.kt` | Passage v1 → v2 sans perte (section 5). |
| `Utilisateur.kt`, `Ingredient.kt`, `Recette.kt`, `LigneRecette.kt`, `Suivi.kt`, `Vente.kt` | Les entités (= les tables). `Suivi.kt` contient `PrixHistorique`, `Facture`, `Synonyme`. |
| `Daos.kt` | Les requêtes SQL (une interface DAO par table). |
| `HesbaRepository.kt` | Toutes les écritures : ingrédient (doublons refusés, historique), recette, prix groupés (`applyPrices`), factures, synonymes, transfert du mode essai. |
| `BackupManager.kt` | Sauvegarde : copie du fichier SQLite vers un fichier choisi par l'utilisateur ; restauration. |

### 2.3 `domain/` : la logique pure (testée sur l'ordinateur)

| Fichier | Rôle |
|---|---|
| `Money.kt` | La règle d'arrondi unique, conversion dinars ↔ millimes, lecture d'un montant saisi. |
| `NumberParser.kt` | Lit « 1,250 », « 1.250 », « ١٫٢٥٠ » (chiffres arabes) → nombre exact. |
| `Units.kt` | Conversions g / kg / mg, ml / cl / L, pièce / douzaine / emballage. |
| `TextNormalizer.kt` | Enlève accents et majuscules, unifie les lettres arabes : base de la recherche et de la correspondance. |
| `RecipeSearch.kt` | Recherche (sans accents, arabe) et tri des recettes. |
| `Planning.kt` | `Workspace` (toutes les données d'un compte), `WhatIf` (simulateur), `ShoppingList` (courses), `Insights` (analyses). |
| `HomeSummary.kt` | Les alertes de l'Accueil (marge sous l'objectif, coût incomplet, prix non fixé, prix changé). |
| `HomeHeadline.kt` | Le premier pas à proposer (premier ingrédient, première recette) et le décompte des alertes. |
| `HomeDashboard.kt` | Les chiffres des cartes de l'Accueil : marge moyenne et objectif moyen, recettes sous l'objectif, meilleure marge, prix qui a le plus bougé. |
| `Greeting.kt` | Bonjour ou Bonsoir selon l'heure (matin 5 h – 12 h, après-midi 12 h – 18 h, soir ensuite). |
| `invoice/OcrLayout.kt` | Remet sur une même ligne les morceaux de texte lus par l'OCR (droite → gauche en arabe). |
| `invoice/InvoiceParser.kt` | Extrait fournisseur, date, lignes, quantités, prix, remise, TVA. |
| `invoice/IngredientMatcher.kt` | Trouve l'ingrédient correspondant à une ligne de facture. |
| `invoice/InvoiceReview.kt` | Ancien / nouveau prix, écart, doublons, lignes sûres ou à vérifier. |

### 2.4 `ocr/` : la lecture du texte des factures

| Fichier | Rôle |
|---|---|
| `OcrEngine.kt` | La petite interface commune `OcrEngine` + `OcrRouter` qui choisit le moteur (Auto, Français, Arabe). |
| `MlKitOcrEngine.kt` | Moteur Google ML Kit (latin : français, anglais). |
| `TesseractOcrEngine.kt` | Moteur Tesseract avec le modèle arabe `ara` (`assets/tessdata/ara.traineddata`, 1,4 Mo). |
| `InvoiceImages.kt` | Charge et prépare les images (orientation, taille) ; pages de PDF via `PdfRenderer`. |

### 2.5 `auth/` : les comptes

| Fichier | Rôle |
|---|---|
| `PasswordHasher.kt` | PBKDF2-HMAC-SHA256, 120 000 tours, sel aléatoire de 16 octets. |
| `AuthRepository.kt` | Inscription (vérifications), connexion, changement de mot de passe. |
| `SessionManager.kt` | Retient l'identifiant du compte connecté (−1 = mode essai). |

### 2.6 `ui/` : tout ce qui est commun aux écrans

| Fichier | Rôle |
|---|---|
| `BaseActivity.kt` | Base de tous les écrans : bord à bord, marges système et clavier, session, base, messages, transitions. |
| `Format.kt` (`Fmt`) | Affichage des nombres selon la langue : « 1 250,500 DT », « 1,250.500 TND », « 1.250,500 د.ت » ; chiffres 0-9 en arabe (usage tunisien) isolés pour rester lisibles en RTL. |
| `Settings.kt` | Thème, langue, premier lancement fait ou non. |
| `Motion.kt` | Règles de mouvement : durées, courbes, entrée des listes une seule fois, retour haptique ; tout est coupé si « Supprimer les animations » est activé sur le téléphone. |
| `Transitions.kt` | Transformation de conteneur Material : le bouton carré ou une ligne « grandit » jusqu'au nouvel écran. |
| `Hairline.kt` | Filet fin entre les lignes d'une liste (au lieu de cartes). |
| `Dialogs.kt`, `Picker.kt` | Boîtes de dialogue et listes de choix. |
| `ImpactViews.kt` | Affichage « avant → après » des recettes touchées (simulateur, scanner). |
| `RecipePdf.kt` | Fiche recette en PDF (avec ou sans prix), partagée par le menu Android. |
| `RecipePhotos.kt` | Photos de recettes en stockage privé, miniatures. |
| `widget/ReceiptLayout.kt` | Le **ticket** : papier à bord dentelé, effet « imprimé » de haut en bas. |
| `widget/DotLeaderView.kt` | Les **pointillés** entre un nom et un prix (ardoise, ticket). |
| `widget/MarginBarView.kt` | La **barre de marge** sous chaque recette (trait = marge voulue). |
| `widget/RollingNumberView.kt` | Les **chiffres qui roulent** du conseiller de marge. |
| `widget/NavBar.kt` | La **barre du bas** avec l'indicateur safran qui glisse. |
| `widget/ScanLineView.kt` | La **ligne de lecture** du scanner, qui ne bouge que pendant l'OCR réel. |

### 2.7 Ressources (`app/src/main/res/`)

* `layout/` : un fichier XML par écran (`activity_…`) et par ligne de liste (`item_…`).
* `values/strings.xml`, `values-en/`, `values-ar/` : **tous les textes** en français (par défaut),
  anglais et arabe ; les pluriels arabes ont leurs 6 formes (zéro, un, deux, quelques, beaucoup, autre).
* `values/colors_theme.xml` et `values-night/colors_theme.xml` : les rôles de couleur, clair et sombre.
* `values/type.xml` : les styles de texte ; `values/styles.xml` : les composants ; `values/themes.xml` : le thème.
* `font/` : IBM Plex Sans Arabic et IBM Plex Mono ; `font-ar/` remplace la police « ticket » en arabe.
* `drawable/` : icônes au trait (une seule famille), fonds (ardoise, ticket…).

---

## 3. L'argent : millimes et BigDecimal

Le dinar a **3 décimales** (1 DT = 1000 millimes).

* **Stockage** : tous les montants sont des **millimes entiers** (`Long`). 1,250 DT est enregistré `1250`.
  Un entier ne fait jamais d'erreur d'arrondi.
* **Calcul** : `BigDecimal` (décimaux exacts), jamais `Double`.
  Avec `Double`, 0,1 + 0,2 = 0,30000000000000004 ; avec `BigDecimal`, 0,3 exactement.
* **Politique d'arrondi unique** (`domain/Money.kt`) :
  1. aucun arrondi pendant le calcul : le prix au gramme peut valoir 3,8 millimes ; les divisions gardent
     10 décimales (`INTERNAL_SCALE = 10`) ;
  2. on arrondit **au millime le plus proche** (HALF_UP : 0,0005 → 0,001) seulement ce qui est affiché ou
     enregistré : coût d'une ligne, coût du lot, coût par part, prix conseillé ;
  3. le prix conseillé part du coût par part **déjà arrondi**, pour qu'on puisse refaire le calcul à la main
     avec les chiffres de l'écran.

---

## 4. Les formules (`CostCalculator.kt`)

Exemple simple : **sandwich**, 10 parts, marge voulue 40 %.
Harissa : 1 kg acheté 6,000 DT. Pain : 0,250 DT la pièce. Recette : 50 g de harissa, 10 pains.

| Étape | Formule | Exemple |
|---|---|---|
| Prix réel par unité | prix de l'emballage ÷ (quantité × rendement %) | 6000 ÷ 1000 g = 6 millimes/g |
| Coût d'une ligne | quantité utilisée × prix réel par unité | 50 g × 6 = 300 ; 10 × 250 = 2500 |
| Coût du lot | somme des lignes + gaz/électricité + main-d'œuvre | 2800 millimes |
| Coût par part | coût du lot ÷ parts + emballage par part + part du loyer | 2800 ÷ 10 = **0,280 DT** |
| Prix conseillé | coût par part ÷ (1 − marge) | 280 ÷ 0,60 = 466,67 → **0,467 DT** |
| Marge réelle | (prix de vente − coût) ÷ prix de vente | (500 − 280) ÷ 500 = **44 %** |
| Bénéfice par part | prix de vente − coût par part | 500 − 280 = **0,220 DT** |

**Attention à la différence marge / coefficient** : une marge de 40 % ne veut pas dire « coût + 40 % ».
Coût + 40 % = 0,392 DT, et (0,392 − 0,280) ÷ 0,392 = 28,6 % seulement. C'est pour ça qu'on divise par
(1 − marge).

Exemple réel des captures (Gâteau au chocolat, 12 parts) : lignes 0,338 + 0,320 + 4,400 + 1,800 + 7,200
+ gaz 0,500 = lot **14,558 DT** ; ÷ 12 = 1,213 ; + emballage 0,150 = **1,363 DT** par part ;
prix conseillé à 60 % = 1,363 ÷ 0,4 = **3,408 DT**.

### Le rendement (les pertes)
1 kg de pommes de terre acheté 1,000 DT, 800 g utilisables après épluchage : rendement 80 %.
Les 200 g perdus ont été payés, donc :

> prix réel = prix payé ÷ (quantité × 80 %) = 1,000 ÷ 0,8 = 1,250 DT le kilo **utilisable**

Dans la recette, on écrit la quantité réellement utilisée. Les ingrédients d'avant la migration ont un
rendement de **100 %** : leurs coûts n'ont pas changé.

### Les coûts cachés (facultatifs, par recette)
* **Emballage par part** (boîte, sachet) : ajouté à chaque part.
* **Gaz / électricité du lot** et **main-d'œuvre du lot** : ajoutés au lot, donc divisés par les parts.
* **Part du loyer** (Profil) : loyer mensuel ÷ parts produites par mois.
* **Estimation de main-d'œuvre** (bouton dans Composer) : salaire mensuel ÷ heures par mois × temps de
  préparation. Si le profil n'est pas rempli, **rien n'est ajouté** (pas de valeur inventée).

### Les cas difficiles (aucune erreur cachée)
| Cas | Ce que fait HESBA |
|---|---|
| 0 part | pas de coût par part (« — »), demande de saisir les parts |
| Quantité 0 | la ligne compte 0, refusée à l'enregistrement |
| Prix manquant | recette « coût incomplet » (étiquette + alerte sur l'Accueil) |
| Unité incompatible (kg pour un ingrédient en pièces) | signalé « Unité ? », pas de faux calcul |
| Marge ≥ 100 % ou négative | refusée (division impossible) |

### Prix pratiqué et alertes
Chaque recette garde **le prix auquel tu vends vraiment**. Quand un ingrédient augmente, le coût monte
mais le prix de vente ne change pas tout seul : la **marge réelle** baisse. Si elle passe sous la marge
voulue → alerte sur l'Accueil et barre de marge rouge dans Mes recettes.

---

## 5. La base de données

SQLite gérée par **Room**, fichier `hesba.db` dans la **mémoire privée de l'appli sur le téléphone**
(les autres applis n'y ont pas accès). *(Le fichier `hesba.db` qui traînait à la racine du dépôt Git était
une copie corrompue, inutilisée ; il a été supprimé.)*

| Table | Contenu |
|---|---|
| `utilisateur` | compte local : prénom, e-mail, empreinte du mot de passe, commerce, charges |
| `ingredient` | nom, unité de base (g, ml, pièce), quantité et prix de l'emballage (millimes), rendement |
| `recette` | nom, parts, marge voulue, prix pratiqué, coûts cachés, dernier coût calculé |
| `ligne_recette` | recette + ingrédient + quantité + unité (**jamais le prix**) |
| `prix_historique` | chaque changement de prix : ancien, nouveau, source (manuel, facture, simulateur) |
| `facture` | factures scannées : fournisseur, date, chemin de l'image privée |
| `synonyme` | autres noms d'un ingrédient (ex. « فارينة » = Farine), appris par le scanner |
| `vente` | table d'origine conservée telle quelle, non utilisée (décision à prendre) |

**Pourquoi la ligne de recette ne garde pas le prix ?** Pour qu'un nouveau prix d'ingrédient se
répercute automatiquement dans toutes les recettes qui l'utilisent.

**Liens entre tables** : supprimer une recette supprime ses lignes (CASCADE) ; on ne peut pas supprimer
un ingrédient utilisé (RESTRICT) : l'écran prévient et liste les recettes concernées.

### Les mots de passe
On ne garde jamais le mot de passe, seulement `sel:empreinte` calculée avec **PBKDF2-HMAC-SHA256,
120 000 tours, sel aléatoire de 16 octets**. Deux comptes avec le même mot de passe ont des empreintes
différentes, et on ne peut pas retrouver le mot de passe à partir de l'empreinte.
Le mode « Essayer sans compte » enregistre sous l'identifiant −1 ; en créant un compte ensuite,
ces données lui sont rattachées.

### La migration (version 1 → 2)
La v1 stockait les prix en `Double`. La v2 les stocke en millimes. La migration :
1. lit les colonnes existantes (elle accepte la v1 d'Eya **et** la v1 modifiée d'Aya, qui avait changé
   les tables sans changer le numéro de version) ;
2. crée les nouvelles tables et **copie** les données en convertissant `prix × 1000` arrondi ;
3. supprime les anciennes tables et renomme les nouvelles ;
4. ajoute les tables historique, facture et synonyme.

Seul l'arrondi au millime peut changer une valeur (0,46666… → 0,467). Vérifié par `MigrationTest` sur
l'émulateur avec de vraies données. **Jamais** de `fallbackToDestructiveMigration` (qui effacerait tout).

---

## 6. Le scanner de factures

```
Photo / galerie / PDF ─► OCR ─► reconstitution des lignes ─► parseur ─► correspondance
   ─► écran « Vérifier » (corriger, accepter, ignorer) ─► confirmation
   ─► UNE transaction : prix + historique + recalcul ─► résultat (recettes touchées)
```

1. **Entrée** : caméra (CameraX), galerie, ou PDF (chaque page devient une image avec `PdfRenderer`).
   Images rangées dans `filesDir/factures` : privé, exclu des sauvegardes Google, supprimable.
2. **OCR**, sur le téléphone et gratuit, derrière l'interface `OcrEngine` :
   * français / anglais : **ML Kit** (modèle inclus, hors ligne) ;
   * arabe standard : **Tesseract** (bibliothèque Tesseract4Android, licence Apache 2.0) + modèle `ara` ;
   * **Auto** : ML Kit d'abord ; si le texte est arabe ou incompris, Tesseract, et on garde la meilleure lecture.
   * Pendant la lecture, l'écran montre la facture et une **ligne qui la parcourt** ; elle s'arrête dès que
     l'OCR a fini (succès ou échec). Pas de pourcentage inventé.
3. **Reconstitution** (`OcrLayout`) : l'OCR découpe le tableau en morceaux ; on remet sur la même ligne
   ceux qui sont à la même hauteur (de droite à gauche en arabe).
4. **Parseur** (`InvoiceParser`) :
   * chiffres ٠-٩ → 0-9, marques invisibles de direction retirées ;
   * fournisseur, date, remise, TVA, total, lignes produit ;
   * une ligne est **sûre** si quantité × prix unitaire = total ; sinon les champs devinés sont marqués
     « à vérifier » ;
   * un prix absent reste **vide** : on n'invente jamais un prix.
5. **Correspondance** (`IngredientMatcher`) : sans accents ni majuscules, lettres arabes unifiées
   (أ إ آ → ا, ة → ه, ى → ي), article « ال » ignoré, **distance de Levenshtein** (nombre de lettres à
   changer) pour les fautes d'OCR, table de synonymes. Résultat : Sûr / Probable / À vérifier / Non trouvé.
   Une association confirmée est **retenue** pour la prochaine fois. L'interface `IngredientMatching`
   permettrait d'ajouter un jour une option en ligne, sans rien changer au reste.
6. **Vérification** : ancien prix → nouveau prix (ramené au même emballage), écart en DT et en %.
   Accepter, Modifier, Ignorer, Tout accepter. Seules les lignes sûres sont pré-acceptées ; deux lignes
   pour le même ingrédient sont refusées. Les lignes trouvées apparaissent une à une.
7. **Confirmation** (`HesbaRepository.applyPrices`) : **une seule transaction** met à jour les prix, écrit
   l'historique, recalcule les recettes. Si une étape échoue, rien n'est enregistré. L'écran de résultat
   montre les recettes dont le coût a changé et celles qui **passent sous leur marge**.
8. **Analyses** : plus fortes hausses et recettes les plus touchées, à partir de l'historique réel.

**Limites honnêtes** : factures imprimées (pas d'écriture manuscrite fiable), arabe standard (pas de
dialecte). La lecture arabe **n'a pas été testée sur de vraies factures** ; sur l'émulateur, la lecture
d'une facture simple a dépassé 9 secondes (à mesurer sur un vrai téléphone).

---

## 7. Les écrans

* **Premier lancement** : 1) trois phrases (« Tu connais le goût de tes recettes. / Mais connais-tu leur
  vrai prix ? / HESBA calcule. Toi, tu cuisines. ») pendant que des ingrédients et leurs prix tombent sur
  un ticket, puis le total ; « Passer » discret ; flèche animée (toucher ou glisser) ; 2) langue (Français,
  English, العربية), appliquée immédiatement ; 3) créer un compte ou se connecter.
  Les montants du ticket sont un exemple calculé, pas des données.
* **Accueil** : grille Bento, des cartes arrondies de tailles différentes, une idée par carte. « Bonjour »
  ou « Bonsoir » selon l'heure, avec le prénom ; marge moyenne et son anneau (trait = objectif moyen) ;
  scanner ; nombre de recettes à corriger (touche : la liste complète s'ouvre dessous) ; prix qui a le plus
  bougé ces 14 jours ; « À faire maintenant » : une alerte à la fois, avec le prix conseillé en un geste ;
  meilleure marge ; simulateur ; courses. Les cartes montent l'une après l'autre et les chiffres comptent
  jusqu'à leur valeur, sauf si les animations sont coupées sur le téléphone. Couleurs propres à l'Accueil
  dans `colors_bento.xml` (clair et sombre). La grille est la même dès le premier lancement : sans données,
  « À faire maintenant » propose d'ajouter le premier ingrédient, puis de composer la première recette.
* **Mes recettes** : carte de restaurant : nom au début, **prix de vente** bien visible à la fin (ou prix
  conseillé, signalé), coût par part et marge dessous, fine **barre de marge** ; recherche fixe ; tri.
* **Fiche recette** : coût par part en grand, **ticket** du détail du coût qui s'imprime, prix pratiqué et
  marge réelle, **conseiller de marge** (curseur → prix à pratiquer avec chiffres qui roulent, petite
  vibration à chaque cran, rien n'est enregistré sans confirmation), actions.
* **Composer** : coût recalculé à chaque frappe, unité par ligne, coûts cachés, marge, prix.
* **Ingrédients** : **ardoise du marché** : craie sur ardoise, « Farine ······· 1,350 DT », prix au kg.
* **Simulateur « et si »**, **Liste de courses**, **Profil**, **Scanner**, **Factures**, **Analyses** :
  voir 2.1.

---

## 8. Le design « Le Ticket »

**L'idée** : « Hesba » veut dire un calcul. L'identité vient du **ticket de caisse** : précis, chaleureux,
fait pour la restauration. Utilisé avec mesure : seulement là où il a du sens (fiche recette, scanner,
premier lancement), pas sur chaque écran.

* **Couleurs** : famille safran / brun / crème, adoucie pour les yeux : fond papier chaud au lieu d'un
  blanc éclatant, encre brune profonde, safran réservé à l'action. Contrastes vérifiés (encre 14:1, texte
  safran 5,2:1 sur le fond). Mode sombre : brun très profond, pas de noir pur.
* **Polices** (licence libre SIL OFL, fichier `docs/licences/IBM-Plex-OFL.txt`) :
  * **IBM Plex Sans Arabic** pour tous les textes, en français comme en arabe ;
  * **IBM Plex Mono** pour l'argent et le ticket : tous les chiffres ont la même largeur, donc les montants
    s'alignent colonne par colonne. En arabe, la police ticket devient Plex Sans Arabic (dossier `font-ar/`),
    dont les chiffres ont aussi la même largeur.
* **Peu de cartes** : des filets fins, des pointillés, l'alignement font la hiérarchie.
* **Bouton d'ajout signature** : carré aux coins légèrement arrondis, en bas à droite (à gauche en arabe),
  qui **grandit** jusqu'au formulaire (transformation de conteneur Material). La liste garde un espace en
  bas pour que le bouton ne cache jamais la dernière ligne quand on fait défiler.
* **Mouvement** : court et utile ; listes animées une seule fois au premier chargement ; une ligne de liste
  s'ouvre en fiche ; tout est coupé si le téléphone a « Supprimer les animations ».
* **Accessibilité** : cibles tactiles ≥ 48 dp, descriptions pour TalkBack, éléments décoratifs ignorés par
  TalkBack, retour haptique selon le réglage du téléphone.

---

## 9. Ce qui se passe vraiment (trois exemples pas à pas)

**A. Tu changes le prix du beurre de 3,900 à 4,400 DT**
1. `NouvelIngredientActivity` appelle `repo.saveIngredient(ancien, nouveau)`.
2. Dans une transaction : l'ingrédient est modifié, une ligne `prix_historique` (3,900 → 4,400, source
   manuel) est écrite, le coût mémorisé des recettes qui utilisent le beurre est recalculé.
3. Room voit la table changer → `workspace()` émet → les écrans ouverts se redessinent.
4. `HomeSummary` trouve que le gâteau est passé sous 60 % → alerte ; `HomeHeadline` écrit la phrase.

**B. Tu ouvres la fiche d'une recette**
1. `RecettesActivity` lance `RecipeDetailActivity` avec l'identifiant (la ligne grandit jusqu'à la fiche).
2. La fiche observe `workspace()`, trouve la recette, appelle `ws.cost(recette)` → `CostCalculator`.
3. Le ticket est rempli ligne par ligne puis « imprimé » une seule fois ; le conseiller part de la marge voulue.

**C. Tu confirmes une facture**
1. `ScanReviewActivity` → `repo.applyPrices(lignes acceptées, source facture)`.
2. Une transaction : prix + historique + recalcul ; on compare les coûts avant / après.
3. Écran de résultat : recettes touchées, et celles qui passent sous leur marge.

---

## 10. Les tests

* **66 tests unitaires** (sur l'ordinateur, `gradlew testDebugUnitTest`) : formules, arrondis, unités,
  rendement, coûts cachés, lecture des nombres (chiffres arabes, virgules), parseur de factures français et
  arabe, correspondance, simulateur, liste de courses, analyses, recherche, alertes, phrase de l'Accueil.
* **Tests sur l'émulateur** (`gradlew connectedDebugAndroidTest`) : migration v1 → v2 (version d'Eya et
  version d'Aya, avec vraies données), hachage des mots de passe, parcours complet compte → ingrédient →
  recette → facture → recalcul → alerte, rattachement des données du mode essai.
* **Visite d'écrans** (`ScreenshotTour` + `scripts/screenshots.sh`) : remplit une base **en mémoire** avec
  des données réalistes et photographie chaque écran (dans `docs/screenshots/`). Les vraies données du
  téléphone ne sont jamais touchées ; préférences et langue sont remises à l'identique.

**Bug trouvé grâce à la visite et corrigé** : ouvrir une recette dans l'éditeur faisait planter l'appli.
Le champ « prix » déclenchait un recalcul, qui réécrivait le prix conseillé dans le champ, qui redéclenchait
un recalcul… à l'infini (StackOverflowError). Correction : un prix écrit par le programme ne relance plus
le calcul, et on ne réécrit le champ que si le texte change vraiment.

---

## 11. Limites connues et décisions

* Lecture arabe non testée sur de vraies factures ; durée de l'OCR à mesurer sur un vrai téléphone.
* Vérifications visuelles faites en français, thème clair ; arabe (RTL) et thème sombre à vérifier à la main.
* Écriture manuscrite et dialecte tunisien non pris en charge.
* La sauvegarde copie les données, pas les photos ni les images de factures.
* Décision à prendre : garder ou supprimer la table `vente` (non utilisée).

---

## 12. Questions possibles du jury (et réponses)

**Pourquoi des millimes entiers et pas des `Double` ?**
Un `Double` ne représente pas exactement 0,1 ; les erreurs s'accumulent. Un entier de millimes est exact,
et `BigDecimal` fait les calculs exacts avec un arrondi choisi (au millime, HALF_UP).

**Pourquoi diviser par (1 − marge) et pas multiplier par (1 + marge) ?**
La marge se calcule sur le prix de vente. Coût × 1,4 ne donne qu'une marge de 28,6 % ; coût ÷ 0,6 donne
bien 40 % de marge.

**Que se passe-t-il si l'appli s'arrête en plein milieu d'une mise à jour de prix ?**
Tout est dans une transaction : soit tout est enregistré (prix, historique, recalcul), soit rien.

**Les données sont-elles protégées ?**
Elles sont dans la mémoire privée de l'appli. Les mots de passe sont salés et hachés (PBKDF2, 120 000
tours). Aucune donnée n'est envoyée sur un serveur : il n'y en a pas.

**Pourquoi deux moteurs OCR ?**
ML Kit lit très bien l'écriture latine mais pas l'arabe ; Tesseract lit l'arabe. Les deux sont gratuits et
marchent sur le téléphone. Une interface commune (`OcrEngine`) permet de changer de moteur sans toucher au
reste.

**L'IA peut-elle changer un prix toute seule ?**
Non. L'OCR propose, chaque ligne est vérifiable et modifiable, un prix absent reste vide, et rien n'est
enregistré sans la confirmation de l'utilisateur.

**Comment avez-vous mis à jour la base sans perdre les données des utilisateurs ?**
Migration Room 1 → 2 écrite à la main, testée sur l'émulateur avec de vraies données des deux anciennes
versions ; jamais de migration destructive.

**Pourquoi Kotlin + XML et pas Jetpack Compose ?**
C'était une exigence du projet ; les composants Material 3 et des vues sur mesure (`ReceiptLayout`,
`NavBar`…) suffisent pour une interface soignée.

**Comment l'appli gère-t-elle l'arabe ?**
Tous les textes sont dans `values-ar/strings.xml` ; Android retourne la mise en page (RTL) ; les icônes
directionnelles sont miroir ; les montants gardent les chiffres 0-9 (usage tunisien) et sont isolés pour
rester lisibles dans une phrase de droite à gauche ; la recherche et le scanner unifient les lettres arabes.
