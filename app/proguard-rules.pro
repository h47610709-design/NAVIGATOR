# UzNav — ProGuard/R8 qoidalari
# Standart holatda release build'da minify o'chirilgan, lekin yoqilsa
# quyidagi qoidalar Mapsforge va AndroidSVG ning to'g'ri ishlashini ta'minlaydi.

# Mapsforge
-keep class org.mapsforge.** { *; }
-dontwarn org.mapsforge.**

# Mapsforge render-temalari XML orqali sinflarni yuklaydi
-keep class org.mapsforge.map.rendertheme.** { *; }

# AndroidSVG
-keep class com.caverock.androidsvg.** { *; }
-dontwarn com.caverock.androidsvg.**

# XmlPullParser
-dontwarn org.xmlpull.v1.**
-keep class org.xmlpull.** { *; }

# Kotlin
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings { <fields>; }

# Ilovaning model sinflari (JSON orqali saqlanadi)
-keep class uz.uznav.navigator.model.** { *; }
