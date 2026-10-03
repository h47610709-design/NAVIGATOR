# UzNav — O'zbek Navigator

**100% o'zbek tilidagi, to'liq oflayn ishlaydigan avtomobil navigatori (Android, Kotlin).**

UzNav OpenStreetMap ma'lumotlari asosidagi **Mapsforge `.map`** oflayn xaritasidan foydalanadi.
Xarita telefon xotirasiga bir marta yuklab olinadi — shundan keyin **internetsiz**:

* xarita ko'rsatiladi (kunduzgi va tungi rejim),
* GPS orqali joylashuv aniqlanadi,
* manzil qidiriladi (ko'cha, uy raqami, shahar, tuman, mahalla, obyekt nomi),
* marshrut **telefonning o'zida** hisoblanadi (A\* algoritmi),
* burilishlar ko'rsatiladi va **o'zbekcha ovoz** bilan aytiladi ("300 metrdan keyin o'ngga buriling"),
* marshrutdan chiqib ketilsa, **oflayn qayta hisoblash** avtomatik ishlaydi.

Internet **faqat** xaritani yuklab olish va yangilash uchun kerak.
Hech qanday ro'yxatdan o'tish, akkaunt yoki server yo'q — **joylashuv faqat telefon ichida qayta ishlanadi**.

| | |
|---|---|
| Til | Kotlin (JVM 17) |
| Minimal Android | 7.0 (API 24) |
| Target / Compile SDK | 34 |
| Qurilma talabi | GPS, ~200 MB bo'sh joy (O'zbekiston xaritasi) |
| Litsenziya | MIT (xarita — OpenStreetMap / ODbL, Mapsforge — LGPL-3.0) |

---

## 1. Imkoniyatlar

### Asosiy ekran
* Butun ekranli xarita, mashina belgisi (harakat yo'nalishi bo'yicha buriladi).
* Qidiruv paneli — **"Qayerga boramiz?"**, **"Qayerga?"** tugmasi, **Uy** va **Ish** tugmalari.
* GPS holati: *GPS yaxshi / GPS qidirilmoqda / GPS o'chirilgan* (rangli ko'rsatkich).
* Kattalashtirish / kichiklashtirish (+ / −) va "Mening joylashuvim" tugmalari.
* Joriy tezlik (km/soat) va mavjud bo'lsa tezlik cheklovi belgisi.
* Xaritani uzoq bosganda — *"Shu yerga borasizmi?"* oynasi.

### Marshrut
* Uchta variant: **Eng tez**, **Eng qisqa**, **Pullik yo'lsiz** — har biri uchun masofa va vaqt ko'rsatiladi, foydalanuvchi tanlaydi.
* Faqat avtomobil uchun yaroqli yo'llar (piyoda yo'laklari, zinapoyalar, `access=private` hisobga olinadi; bir tomonlama yo'llar — `oneway` — to'liq qo'llab-quvvatlanadi).
* Navigatsiya paytida: keyingi burilish belgisi, burilishgacha masofa, qolgan masofa, qolgan vaqt, yetib borish vaqti (ETA), marshrut chizig'i.
* Marshrutdan ~45 m dan ortiq chetlashilsa (ketma-ket 3 ta GPS o'lchovi) — **"Qayta marshrut hisoblanmoqda…"** va yangi marshrut (oflayn).
* Manzilga yetganda — **"Manzilingizga yetib keldingiz"**.
* Fon xizmati (foreground service) — ilova yopilsa ham navigatsiya davom etadi, bildirishnomada qolgan masofa ko'rinadi.

### Qidiruv (oflayn)
* `.map` fayl ichidagi POI va nomlangan yo'llar bo'yicha qidiradi + ilovaga kiritilgan o'zbek **gazetteer** (88 ta shahar, viloyat markazi, tuman va yirik obyekt) bo'yicha.
* Uy raqamini ham tushunadi: `Amir Temur ko'chasi 108, Toshkent`.
* Lotin/kirill va `o'/oʻ/o`, `g'/gʻ/g`, `sh/ş`, `ch/ç` yozuv farqlariga chidamli (`util/UzText.kt`).
* **Sevimli joylar** (Uy, Ish, boshqa joylar) va **Qidiruv tarixi** (oxirgi 60 ta).

### Sozlamalar
* Mavzu: *Tizim bo'yicha / Yorug' rejim / Tungi rejim* (xarita ham tungi rejimda qoraytiriladi).
* Ovozli yo'riqnoma: yoqish/o'chirish, **"Ovozni sinash"**, tizim TTS sozlamalariga o'tish.
* Tezlik cheklovidan oshganda ogohlantirish, xaritaning harakat yo'nalishi bo'yicha burilishi, navigatsiya paytida ekran o'chmasligi, xaritani faqat Wi-Fi orqali yuklash.

### Xaritalar bo'limi
**Xaritani yuklab olish**, **Xarita yangilash** (serverdagi sana bilan solishtiradi), **Yuklangan xaritalar** (hajmi, sanasi, o'chirish).
Hududlar: **O'zbekiston (butun davlat)** — barcha viloyatlar: Toshkent, Samarqand, Andijon, Farg'ona, Namangan, Buxoro, Qarshi, Nukus va boshqalar — hamda Qozog'iston, Qirg'iziston, Tojikiston, Turkmaniston, Afg'oniston.

---

## 2. Repozitoriy tuzilishi

```
NAVIGATOR/
├── .github/workflows/build-apk.yml   # Har push'da debug + release APK avtomatik quriladi
├── build.gradle.kts                  # Ildiz Gradle fayli (plaginlar)
├── settings.gradle.kts               # Modullar va repozitoriylar
├── gradle/libs.versions.toml         # Barcha kutubxona versiyalari (version catalog)
├── gradle/wrapper/                   # Gradle 8.9 wrapper (jar bilan birga)
├── gradlew / gradlew.bat             # Wrapper skriptlari
├── gradle.properties
├── LICENSE
└── app/
    ├── build.gradle.kts              # App moduli: SDK, imzolash, bog'liqliklar
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/uz_joylar.json     # O'zbekiston gazetteer'i (oflayn qidiruv uchun)
        ├── java/uz/uznav/navigator/
        │   ├── UzNavApp.kt           # Application: barcha xizmatlar shu yerda yaratiladi
        │   ├── data/                 # Sozlamalar, sevimlilar/tarix, xarita menejeri
        │   ├── location/             # GPS (LocationManager) → Flow
        │   ├── map/                  # Mapsforge xarita boshqaruvi, tungi tema
        │   ├── model/                # GeoPoint, Place, Route, ManeuverType…
        │   ├── nav/                  # Navigatsiya dvigateli, kontroller, fon xizmati
        │   ├── routing/              # Graf qurish + A* marshrut hisoblash
        │   ├── search/               # Oflayn qidiruv va gazetteer
        │   ├── ui/                   # 6 ta ekran (Activity) + ViewModel + adapterlar
        │   ├── util/                 # O'zbekcha matn/format yordamchilari
        │   └── voice/                # O'zbekcha iboralar + Android TTS
        └── res/
            ├── drawable/             # Burilish belgilari va ikonkalar (vektor)
            ├── layout/               # 7 ta layout
            ├── mipmap*/              # Ilova ikonkasi
            ├── values/               # strings (o'zbekcha), colors, themes
            ├── values-night/         # Tungi rejim ranglari
            └── xml/                  # Zaxira (backup) qoidalari
```

### Yaratilgan asosiy fayllar

| Fayl | Vazifasi |
|---|---|
| `UzNavApp.kt` | `Application` — prefs, xarita menejeri, GPS, ovoz, router, navigatsiya bitta joyda |
| `data/AppPrefs.kt` | Barcha sozlamalar (`SharedPreferences`), mavzuni qo'llash |
| `data/PlaceStores.kt` | `FavoritesStore` (`sevimli.json`) va `HistoryStore` (`tarix.json`) |
| `data/MapManager.kt` | Xaritani yuklab olish (progress bilan), yangilanishni tekshirish (HTTP HEAD), o'chirish, `MultiMapDataStore` ochish |
| `location/LocationEngine.kt` | GPS + tarmoq provayderi, `StateFlow<Fix>`, GPS holati |
| `map/MapController.kt` | `MapView` sozlash, tema, marshrut chizig'i, mashina/manzil belgisi, xaritani burish |
| `map/NightThemeCallback.kt` | Xarita ranglarini tungi rejimga aylantirish |
| `routing/GraphExtractor.kt` | `.map` fayldan yo'l grafini qurish (z14 plitkalari) |
| `routing/AStarRouter.kt` | A\* algoritmi (CSR graf, binar uyum) |
| `routing/RouteAssembler.kt` | Burilishlarni aniqlash (burchak bo'yicha), masofa/vaqt, ko'rsatmalar |
| `routing/Router.kt` | 3 ta variantni hisoblash, kesh, bekor qilish |
| `search/OfflineSearch.kt` | Plitka bo'ylab qidiruv, uy raqamini yaqinlik bo'yicha moslash |
| `nav/NavigationEngine.kt` | Marshrutga "yopishtirish", qadamlar, ETA, chetlashishni aniqlash |
| `nav/NavigationService.kt` | Foreground service + bildirishnoma |
| `voice/UzbekPhrases.kt` | O'zbekcha iboralar ("300 metrdan keyin o'ngga buriling") |
| `voice/UzbekVoice.kt` | Android TTS (uz → ru → en zaxirasi bilan) |
| `ui/MainActivity.kt` | Asosiy ekran |
| `ui/SearchActivity.kt` | Qidiruv ekrani |
| `ui/FavoritesActivity.kt`, `ui/HistoryActivity.kt` | Sevimlilar va tarix |
| `ui/MapsActivity.kt` | Xaritalarni yuklash/yangilash/o'chirish |
| `ui/SettingsActivity.kt` | Sozlamalar |

---

## 3. Android Studio'da ochish va qurish

**Kerakli dasturlar:** Android Studio (Koala/Ladybug yoki yangiroq), JDK 17 (Android Studio bilan birga keladi), Android SDK 34.

1. Repozitoriyni klonlang:
   ```bash
   git clone https://github.com/h47610709-design/NAVIGATOR.git
   cd NAVIGATOR
   ```
2. Android Studio → **File → Open** → shu papkani tanlang.
3. Gradle sinxronizatsiyasini kuting (birinchi marta kutubxonalar yuklanadi — internet kerak).
4. Telefonni USB orqali ulang (**USB debugging** yoqilgan bo'lsin) yoki emulyator oching
   (emulyatorda GPS'ni *Extended controls → Location* orqali berish mumkin).
5. **Run ▶** tugmasini bosing.

### Terminal orqali
```bash
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # app/build/outputs/apk/release/app-release.apk
./gradlew installDebug       # ulangan telefonga to'g'ridan-to'g'ri o'rnatadi
```
Windows'da `gradlew.bat assembleDebug`.

### Release'ni o'z kalitingiz bilan imzolash (ixtiyoriy)
Standart holatda `assembleRelease` **debug kalit** bilan imzolaydi, shuning uchun u har doim ishlaydi.
O'z kalitingizni ishlatish uchun ildiz papkada `keystore.properties` yarating (u `.gitignore`da):

```properties
storeFile=uznav.jks
storePassword=PAROL
keyAlias=uznav
keyPassword=PAROL
```
Kalit yaratish:
```bash
keytool -genkey -v -keystore uznav.jks -alias uznav -keyalg RSA -keysize 2048 -validity 10000
```

---

## 4. APK'ni GitHub orqali olish

Har bir `push`da **GitHub Actions** avtomatik ravishda debug va release APK quradi
(`.github/workflows/build-apk.yml`).

1. GitHub'da repozitoriyni oching → yuqoridagi **Actions** bo'limi.
2. **"APK qurish"** ish oqimidagi oxirgi muvaffaqiyatli (✅) ishga bosing.
3. Sahifaning pastidagi **Artifacts** bo'limidan yuklab oling:
   * **UzNav-debug-apk** — sinov uchun (`app-debug.apk`),
   * **UzNav-release-apk** — asosiy versiya (`app-release.apk`).
4. Yuklangan `.zip` arxivni oching — ichida `.apk` fayli bor.

> Eslatma: Artifacts'ni yuklash uchun GitHub'ga kirgan bo'lish kerak. Artefaktlar 90 kun saqlanadi.
> Ish oqimini qo'lda ham ishga tushirish mumkin: **Actions → APK qurish → Run workflow**.

---

## 5. APK'ni telefonga o'rnatish

1. `.apk` faylni telefonga ko'chiring (USB, Telegram, Google Drive yoki to'g'ridan-to'g'ri yuklab oling).
2. Fayl menejerida APK'ni bosing.
3. Android **"Noma'lum manbalardan o'rnatish"** (Install unknown apps) ruxsatini so'raydi —
   *Sozlamalar → Ilovalar → Maxsus ruxsatlar → Noma'lum ilovalarni o'rnatish* → brauzer/fayl menejeri uchun ruxsat bering.
4. **O'rnatish** → **Ochish**.
5. Birinchi ishga tushirishda:
   * **Joylashuv ruxsati** ("Ilova ishlatilayotganda") — GPS uchun shart;
   * **Bildirishnoma ruxsati** (Android 13+) — fonda navigatsiya uchun;
   * Ilova o'zi **"O'zbekiston xaritasini yuklab olasizmi?"** deb so'raydi → **Ha** (taxminan 138 MB, Wi-Fi tavsiya etiladi).
6. Xarita yuklangach ilova **to'liq oflayn** ishlaydi.

> Debug va release APK'lar turli `applicationId` ga ega (`…navigator.debug` va `…navigator`),
> shuning uchun ikkalasini bir telefonga birga o'rnatish mumkin.

---

## 6. Oflayn xaritani qo'lda qo'shish

Xaritani internetsiz (masalan, kompyuter orqali) qo'shish ham mumkin:

1. `uzbekistan.map` faylini yuklab oling:
   <https://download.mapsforge.org/maps/v5/asia/uzbekistan.map>
2. Faylni telefonning quyidagi papkasiga ko'chiring:
   ```
   Android/data/uz.uznav.navigator/files/maps/uzbekistan.map
   ```
   (debug versiya uchun: `Android/data/uz.uznav.navigator.debug/files/maps/`)
   Aniq yo'l **Sozlamalar → Xaritalarni boshqarish** ekranida ham yozib qo'yilgan.
3. Ilovani qayta oching — xarita avtomatik topiladi.

Papkadagi **barcha** `.map` fayllar birga ishlatiladi (`MultiMapDataStore`), ya'ni bir nechta davlat xaritasini qo'shsa bo'ladi.
O'zingiz tayyorlagan `.map` faylni ham (masalan, [mapsforge-map-writer](https://github.com/mapsforge/mapsforge/blob/master/docs/Getting-Started-Map-Writer.md) orqali Osmosis bilan) shu papkaga qo'yish mumkin.

---

## 7. Bog'liqliklar

| Kutubxona | Versiya | Nima uchun |
|---|---|---|
| `org.mapsforge:mapsforge-core / map / map-reader / map-android / themes` | 0.25.0 | Oflayn OSM xaritasi (`.map`), rendering, overlaylar |
| `com.caverock:androidsvg` | 1.4 | Mapsforge render-temalaridagi SVG belgilar |
| `androidx.core:core-ktx` | 1.13.1 | Android asosiy kengaytmalari |
| `androidx.appcompat:appcompat` | 1.7.0 | Activity, mavzular |
| `com.google.android.material:material` | 1.12.0 | Material 3 interfeys elementlari |
| `androidx.constraintlayout` | 2.1.4 | Asosiy ekran joylashuvi |
| `androidx.recyclerview` | 1.3.2 | Ro'yxatlar |
| `androidx.preference:preference-ktx` | 1.2.1 | Sozlamalarni saqlash |
| `androidx.lifecycle:*-ktx`, `lifecycle-service` | 2.8.6 | ViewModel, `LifecycleService` |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.8.1 | Asinxron hisob-kitoblar |

Qo'shimcha API kaliti, hisob yoki pullik xizmat **talab qilinmaydi**.
Ovoz uchun Android'ning o'z **TTS** dvigateli ishlatiladi (qo'shimcha kutubxona yo'q).

---

## 8. Muhim texnik eslatmalar va cheklovlar

Bular ilovaning kamchiligi emas, balki **Mapsforge `.map` formati**ning xususiyatidir —
standart `.map` fayllarda ba'zi OSM teglari saqlanmaydi:

| Teg | Holat | Ilovadagi yechim |
|---|---|---|
| `maxspeed` | `.map` ichida **yo'q** | Tezlik cheklovi yo'l turi bo'yicha baholanadi (`routing/RoadTags.kt`: avtomagistral 110, trunk 100, asosiy yo'llar 70, `living_street` 20 km/soat). Ko'rsatilgan qiymat — taxminiy. |
| `toll` | `.map` ichida **yo'q** | "Pullik yo'lsiz" varianti avtomagistrallarga jarima koeffitsienti qo'yib hisoblanadi; o'zingiz `tag-mapping.xml` bilan tayyorlagan xaritada haqiqiy `toll` tegi ishlaydi. |
| `junction=roundabout` | `.map` ichida **yo'q** | Aylanma harakat geometriya (burilish burchaklari ketma-ketligi) bo'yicha aniqlanadi. |
| `addr:street` | `.map` ichida **yo'q** (faqat `addr:housenumber` bor) | Uy raqami eng yaqin (150 m gacha) nomlangan ko'chaga bog'lanadi. |
| Trafik (probka) | Internet talab qiladi | Qo'llab-quvvatlanmaydi — ilova to'liq oflayn ishlashga mo'ljallangan. |

Boshqa eslatmalar:

* **TTS tili.** Android'da o'zbek tili uchun TTS hamma telefonda ham mavjud emas.
  `UzbekVoice` avval `uz_UZ`, keyin `ru_RU`, so'ng standart tilni sinaydi — matn baribir o'zbekcha aytiladi
  (zaxira dvigatelda talaffuz biroz boshqacha bo'lishi mumkin). Holat **Sozlamalar → Ovozli yo'riqnoma** ostida yoziladi.
  O'zbek tilini qo'shish: *Sozlamalar → Til va kiritish → Matnni nutqqa aylantirish → Tilni yuklash*.
* **Xotira.** Marshrut grafi faqat kerakli hudud plitkalaridan quriladi (z14), shuning uchun
  O'zbekiston bo'ylab uzoq marshrut ham telefonda hisoblanadi; juda uzoq (>600 km) marshrutlar bir necha soniya olishi mumkin.
* **Birinchi ishga tushirish.** Xarita yuklanmaguncha ekranda
  *"Xarita yuklanmagan — Menyu → Xaritalar"* yozuvi turadi; qidiruv faqat gazetteer bo'yicha ishlaydi.
* **Maxfiylik.** Joylashuv ma'lumotlari hech qayerga yuborilmaydi; tarmoqqa faqat `download.mapsforge.org` dan xarita olish uchun murojaat qilinadi. Akkaunt, analitika, reklama yo'q.

---

## 9. Hissa qo'shish

Pull request'lar ochiq. Kod uslubi: Kotlin official, izohlar **o'zbek tilida**.
Yangi matn qo'shsangiz — uni `res/values/strings.xml` ichiga o'zbekcha yozing.

Xarita ma'lumotlari © [OpenStreetMap](https://www.openstreetmap.org/copyright) hissadorlari (ODbL).
