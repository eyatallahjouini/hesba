pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Tesseract4Android (OCR arabe) n'est publié que sur JitPack : dépôt limité à ce seul groupe
        maven("https://jitpack.io") {
            content { includeGroup("cz.adaptech.tesseract4android") }
        }
    }
}

rootProject.name = "hesba"
include(":app")
