# 🚀 NAVIGATOR botini bepul serverga joylash (24/7)

> Holat: **2026-yil oktabr** holatiga tekshirilgan. Bepul tariflar shartlari o‘zgarib turadi —
> ro‘yxatdan o‘tishdan oldin xizmat sahifasini ko‘zdan kechiring.

## Qaysi hosting va nima uchun

Bot **polling** usulida ishlaydi (Telegram’ga o‘zi doimiy so‘rov yuboradi) va ma’lumotlarni **SQLite**
faylida saqlaydi. Demak, hosting: (1) jarayonni uxlatmasligi, (2) diskdagi faylni o‘chirmasligi kerak.

| Xizmat | 24/7 polling | Baza saqlanadimi | Karta | Xulosa |
|---|---|---|---|---|
| **Oracle Cloud Always Free** (VM) | ✅ | ✅ (200 GB disk) | kerak (faqat tasdiq uchun) | ⭐ **Tanlandi** |
| Google Cloud e2-micro (VM) | ✅ | ✅ (30 GB) | kerak | Zaxira; faqat AQSH regionlari |
| Railway Free | ⚠️ $1/oy kredit | ✅ (volume 0.5 GB) | shart emas | Bot ~175 MB RAM → oyning ~yarmiga yetadi |
| Render Free | ❌ 15 daqiqada uxlaydi | ❌ fayllar o‘chadi | shart emas | Mos emas |
| Koyeb | ❌ 2026-fevraldan yangi foydalanuvchilarga bepul tarif yo‘q | — | — | Mos emas |
| PythonAnywhere Free | ❌ always-on task yo‘q | — | — | Mos emas |

**Oracle Cloud Always Free** — botni 24/7, bepul va ma’lumotlarni yo‘qotmasdan ishlatadigan yagona
haqiqiy variant: muddatsiz bepul VM, doimiy disk, `systemd` orqali avtomatik qayta ishga tushirish.

Cheklovlar (halol):
- Ro‘yxatdan o‘tishda **Visa/Mastercard** so‘raladi (vaqtincha bloklanadigan summa qaytariladi). Ba’zi virtual/prepaid kartalar rad etilishi mumkin.
- **Bo‘sh turgan VM qaytarib olinishi mumkin:** 7 kun davomida CPU (95-persentil) va tarmoq < 20% bo‘lsa
  (A1 da xotira ham < 20%). Bot yengil bo‘lgani uchun bu xavf real → yechim pastda (**Pay As You Go** ga o‘tish).
- Always Free resurslari faqat **Home Region**da yaratiladi va regionni keyin o‘zgartirib bo‘lmaydi.
- SLA (ishlash kafolati) yo‘q.

---

## 1-qadam. Oracle Cloud akkaunt

1. https://www.oracle.com/cloud/free/ → **Start for free**.
2. **Home Region**: Telegram serverlariga yaqin — **Germany Central (Frankfurt)** yoki **Netherlands Northwest (Amsterdam)**.
3. Karta ma’lumotlarini kiriting (tasdiqlash uchun).
4. **Tavsiya etiladi:** *Billing & Cost Management → Upgrade and Manage Payment* → **Pay As You Go** ga o‘ting.
   Always Free limitlari ichida pul olinmaydi, lekin bo‘sh turgan VM endi qaytarib olinmaydi.
   Xavfsizlik uchun *Budgets* bo‘limida **$1 budget alert** yarating — kutilmagan xarajat bo‘lsa xat keladi.

## 2-qadam. Virtual server (VM) yaratish

*Compute → Instances → Create instance*:

| Maydon | Qiymat |
|---|---|
| Image | **Canonical Ubuntu 24.04** (Python 3.12 bilan keladi) |
| Shape | **VM.Standard.E2.1.Micro** (Always Free-eligible, 1 GB RAM) — yoki bo‘sh bo‘lsa **VM.Standard.A1.Flex** 1 OCPU / 6 GB |
| Networking | Public subnet, **Assign a public IPv4 address** ✅ |
| SSH keys | **Generate a key pair for me** → **Save private key** (faylni saqlab qo‘ying!) |

**Create** → holati *Running* bo‘lgach, **Public IP address** ni ko‘chirib oling.

> Bot polling ishlatgani uchun **hech qanday port ochish shart emas** — firewall sozlamalariga tegmang.

## 3-qadam. Serverga ulanish

Windows 10/11 — **PowerShell**:
```powershell
# kalit faylga faqat o'zingizga ruxsat (bir marta)
icacls "$HOME\Downloads\ssh-key.key" /inheritance:r /grant:r "$($env:USERNAME):(R)"
ssh -i "$HOME\Downloads\ssh-key.key" ubuntu@<PUBLIC_IP>
```
macOS / Linux:
```bash
chmod 600 ~/Downloads/ssh-key.key
ssh -i ~/Downloads/ssh-key.key ubuntu@<PUBLIC_IP>
```

## 4-qadam. Botni o‘rnatish (serverda)

```bash
git clone https://github.com/h47610709-design/NAVIGATOR.git
cd NAVIGATOR
bash deploy/server.sh install
```

> ⚠️ Agar Pull Request hali `main` ga birlashtirilmagan bo‘lsa, ikkinchi qatorni shunday yozing:
> `git clone -b arena/d904f6b5-navigator https://github.com/h47610709-design/NAVIGATOR.git`

Skript:
1. Python 3, venv, git o‘rnatadi va kutubxonalarni (`requirements.txt`) yuklaydi;
2. **BOT_TOKEN ni yashirin so‘raydi** (yozganda ekranda ko‘rinmaydi) va uni
   `/etc/navigator/navigator.env` ga saqlaydi — ruxsat `600`, faqat `root` o‘qiy oladi, GitHub’ga tushmaydi;
3. konfiguratsiyani tekshiradi (`python -m navigator --check`);
4. `navigator-bot` systemd xizmatini yoqadi: **qulasa — 10 soniyada qayta ishga tushadi, server qayta yonsa — avtomatik ishga tushadi.**

## 5-qadam. Boshqarish va loglar

```bash
bash deploy/server.sh status   # holat (active (running) bo'lishi kerak)
bash deploy/server.sh logs     # jonli loglar (chiqish: Ctrl+C)
bash deploy/server.sh check    # token/baza/Telegram aloqasi tekshiruvi (token ko'rsatilmaydi)
bash deploy/server.sh token    # BOT_TOKEN ni almashtirish
bash deploy/server.sh update   # GitHub'dagi yangi kodni o'rnatish
bash deploy/server.sh backup   # bazaning zaxira nusxasi → backups/
```

Muvaffaqiyatli ishga tushgan logda quyidagilar bo‘ladi:
```
INFO navigator: NAVIGATOR ishga tushmoqda · Python 3.12.x · baza: ... · BOT_TOKEN: o‘rnatilgan
INFO navigator: Telegram'ga ulandi: @sizning_botingiz (...)
INFO aiogram.dispatcher: Start polling
```

Kunlik avtomatik zaxira (ixtiyoriy): `crontab -e` va qator qo‘shing:
```
30 3 * * * cd ~/NAVIGATOR && bash deploy/server.sh backup >/dev/null 2>&1
```

## 6-qadam. Telegram’da tekshirish

1. Telegram’da botingizni oching → **/start** → salomlashuv va menyu chiqishi kerak.
2. «📝 Test boshlash» → fan → mavzu → daraja → savollar kelishi kerak.
3. Serverni qayta yuklab ko‘ring: `sudo reboot` → 1–2 daqiqadan keyin bot yana javob beradi.

## Muammolar

| Logdagi xabar | Sabab / yechim |
|---|---|
| `BOT_TOKEN topilmadi` | `bash deploy/server.sh token` |
| `Telegram BOT_TOKEN ni rad etdi (Unauthorized)` | Token bekor qilingan/xato → @BotFather’dan yangisini olib, `server.sh token` |
| `Telegram'ga ulanib bo‘lmadi ... qayta urinish` | Tarmoq vaqtincha yo‘q — bot o‘zi qayta ulanadi |
| `Conflict: terminated by other getUpdates request` | Bot boshqa joyda ham ishlayapti (masalan, kompyuteringizda) — bittasini to‘xtating |
| Instance *Stopped* holatida | Oracle bo‘sh VM’ni to‘xtatgan → **Start** bosing (disk saqlanadi, bot avtomatik yonadi); Pay As You Go ga o‘ting |
| `Out of host capacity` (VM yaratishda) | Boshqa Availability Domain yoki E2.1.Micro shape’ni tanlang, keyinroq urinib ko‘ring |

---

## Zaxira variant: Railway (kartasiz, lekin to‘liq 24/7 emas)

Railway’da birinchi 30 kun uchun bir martalik **$5** beriladi, keyin har oy **$1** kredit. Bot ~175 MB RAM ishlatadi,
shuning uchun $1 kredit oyning taxminan yarmiga yetadi; kredit tugasa xizmat keyingi oygacha to‘xtaydi.
Sinab ko‘rish yoki vaqtinchalik ishlatish uchun mos.

1. https://railway.com → **Login with GitHub** → **New Project → Deploy from GitHub repo** → `NAVIGATOR`.
2. Servis → **Variables** (maxfiy saqlanadi):
   - `BOT_TOKEN` = tokeningiz
   - `DB_PATH` = `/data/navigator.db`
   - `TZ` = `Asia/Tashkent`
3. Servis ustida o‘ng tugma → **Attach Volume** → Mount path: `/data` (bazani saqlash uchun **shart**).
4. **Settings → Deploy**: Start Command = `python -m navigator`; Restart Policy = *Always* (yoki *On Failure*);
   *Serverless / App Sleeping* — **o‘chiq** bo‘lsin.
5. **Deployments → View Logs** — loglar shu yerda. Python versiyasi `.python-version` faylidan (3.12) olinadi.
