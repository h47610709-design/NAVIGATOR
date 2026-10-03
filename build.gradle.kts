// UzNav — O'zbek Navigator
// Ildiz (root) Gradle fayli. Pluginlar bu yerda faqat e'lon qilinadi,
// ularni qo'llash :app modulida amalga oshiriladi.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}

// Barcha build papkalarini tozalash uchun yordamchi vazifa: ./gradlew clean
tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
