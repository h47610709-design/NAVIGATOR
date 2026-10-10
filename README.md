# 🧭 NAVIGATOR — test boti (Telegram)

Fan → mavzu → daraja bo‘yicha **aynan 30 ta savolli** testlar, o‘yin uslubidagi **ball tizimi**,
har bir to‘g‘ri javobda **🎆 salyut effekti**, batafsil natijalar va «Xatolarim ustida ishlash» bo‘limi.

## Ishga tushirish

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
cp .env.example .env        # BOT_TOKEN ni kiriting
.venv/bin/python -m navigator
```

Ma’lumotlar `data/navigator.db` (SQLite) faylida saqlanadi — bot qayta ishga tushganda ballar,
faol testlar va tarix yo‘qolmaydi. Eski xabarlardagi tugmalar ham ishlashda davom etadi.

Testlar: `.venv/bin/pip install -r requirements-dev.txt && .venv/bin/python -m pytest`

## 🎉 Ball tizimi (server tomonda)

| Holat | Ball |
|---|---|
| To‘g‘ri javob | **+10** |
| Noto‘g‘ri javob | 0 (ayirilmaydi) |
| Qayta bosish / javobni o‘zgartirish | 0 — «allaqachon javob bergansiz» ogohlantirishi |

* Ball faqat javob **birinchi marta** tekshirilganda hisoblanadi: `UPDATE … WHERE chosen_index IS NULL`
  bitta atomar tranzaksiyada test balli va profildagi umumiy ball bilan birga yangilanadi.
* Variantlar tartibi har bir test uchun aralashtirib, bazada saqlanadi — to‘g‘ri javob server tomonda tekshiriladi.
* Har bir foydalanuvchining test tarixi alohida saqlanadi (`/history`), umumiy ball — profilda (`/profile`).

## 🎆 To‘g‘ri javobdagi effektlar

1. Tabrik: «🎉 Barakalla! To‘g‘ri javob! +10 ball» + 🎆🎉🥳 + yangilangan ball hisoblagichi —
   Telegram’ning to‘liq ekranli **🎉 message effect**i bilan (qo‘llab-quvvatlanmasa, effektsiz).
2. Animatsion salyut, ustuvorlik bo‘yicha:
   * `CELEBRATION_STICKER_SET` — sticker to‘plamidan 🎆🎇🎉🥳🎊✨ emojili **animatsion stickerlar**;
   * `CELEBRATION_STICKERS` / `CELEBRATION_GIFS` — tayyor `file_id` yoki GIF URL’lari;
   * aks holda botning **o‘zi yaratgan salyut GIF’lari** (Pillow, 3 xil variant). Ular bir marta
     yuklanadi, so‘ng `file_id` bazada keshlanadi.
   * Hech biri ishlamasa — chiroyli emoji/matnli salyut.
3. «➡️ Keyingi savol» tugmasi.

Noto‘g‘ri javobda: «🙂 Afsus, bu safar xato. To‘g‘ri javob: …», qisqa izoh, savol
«🔁 Xatolarim ustida ishlash» bo‘limiga qo‘shiladi. Bu bo‘limda to‘g‘ri javob berilgan savol ro‘yxatdan chiqadi.

## 📝 30 ta savol qoidasi

* Savollar tanlangan fan/mavzu (yoki «Aralash»)/darajaga mos, bazadan tasodifiy tanlanadi.
* Bir test ichida takror yo‘q (`UNIQUE(test_id, question_id)` + `content_hash` dublikatlarga qarshi).
* Avval foydalanuvchi **hali ko‘rmagan** savollar, yetmasa — eng uzoq vaqt oldin ko‘rilganlari.
* 30 ta noyob savol bo‘lmasa, test boshlanmaydi: foydalanuvchiga xabar beriladi va
  «🤖 Yangi savollar yaratish» (generatorli mavzular) yoki «📥 Bazani to‘ldirish» taklif qilinadi.

## 🏆 Natija

Jami savollar, to‘g‘ri/noto‘g‘ri, ball, foiz, davomiylik, mavzular bo‘yicha natija, xatolar va izohlar.
Rag‘batlantiruvchi xabar: 27–30 🏆, 21–26 🎉, 15–20 💪, 0–14 📚.

## Savollar bazasi

| Fan | Mavzular | Manba |
|---|---|---|
| 🧮 Matematika | Arifmetika, Foizlar, Tenglamalar, Geometriya | cheksiz generator (izohli) |
| 🇬🇧 Ingliz tili | So‘z boyligi, Noto‘g‘ri fe’llar | ro‘yxat (har darajada 32–40) |
| 🌍 Geografiya | Davlatlar poytaxtlari | ro‘yxat (har darajada 40) |
| 🏛 Tarix | O‘zbekiston tarixi | `navigator/content/questions/tarix.json` (12 ta — to‘ldirish kerak) |

Yangi savollar qo‘shish: `navigator/content/questions/*.json` ga fayl qo‘ying (startda import qilinadi)
yoki admin (`ADMIN_IDS`) botga `.json` faylni `/import` izohi bilan yuboradi:

```json
[{"subject": "Tarix", "topic": "O‘zbekiston tarixi", "level": "oson",
  "question": "…?", "options": ["…", "…", "…", "…"], "correct": 0, "explanation": "…"}]
```

## Tuzilma

```
navigator/
  __main__.py      ishga tushirish, DI
  config.py        .env sozlamalari, qoidalar (10 ball, 30 savol)
  db.py            SQLite sxema
  quiz.py          test/ball biznes-logikasi
  effects.py       salyut: message effect, sticker, GIF, matnli fallback
  fireworks.py     animatsion salyut GIF generatori
  handlers.py      Telegram handlerlari
  keyboards.py     tugmalar va callback-data
  texts.py         o‘zbekcha matnlar
  content/         katalog, generatorlar, JSON savollar
tests/             unit + end-to-end (soxta Telegram sessiyasi)
```
