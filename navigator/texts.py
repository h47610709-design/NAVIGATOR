"""Foydalanuvchiga ko'rsatiladigan matnlar (o'zbek tilida)."""
from __future__ import annotations

from datetime import datetime
from html import escape

from .config import OPTION_LETTERS, POINTS_PER_CORRECT
from .content.catalog import LEVELS
from .quiz import AnswerResult, QuestionView, TestSummary

BTN_START_TEST = "📝 Test boshlash"
BTN_MISTAKES = "🔁 Xatolarim ustida ishlash"
BTN_PROFILE = "👤 Profil"
BTN_HISTORY = "📜 Test tarixi"
BTN_HELP = "ℹ️ Yordam"

CORRECT_HEADLINE = f"🎉 Barakalla! To‘g‘ri javob! +{POINTS_PER_CORRECT} ball"
WRONG_HEADLINE = "🙂 Afsus, bu safar xato. To‘g‘ri javob:"


def e(s) -> str:
    return escape(str(s), quote=False)


def welcome(name: str) -> str:
    return (
        f"👋 Assalomu alaykum, <b>{e(name)}</b>!\n\n"
        "🧭 <b>NAVIGATOR</b> — test orqali bilimingizni sinab ko‘ring.\n\n"
        f"• Har bir testda aynan <b>30 ta</b> savol\n"
        f"• Har bir to‘g‘ri javob — <b>+{POINTS_PER_CORRECT} ball</b> 🎆\n"
        "• Xato javob uchun ball ayirilmaydi 🙂\n"
        "• Xatolaringiz «Xatolarim ustida ishlash» bo‘limida saqlanadi\n\n"
        "Boshlash uchun «📝 Test boshlash» tugmasini bosing."
    )


HELP = (
    "ℹ️ <b>Qanday ishlaydi?</b>\n\n"
    "1️⃣ Fan → mavzu → darajani tanlang.\n"
    "2️⃣ 30 ta savolga javob bering. Har bir savolga faqat <b>bir marta</b> javob berish mumkin — "
    "ball faqat birinchi javob uchun hisoblanadi.\n"
    f"3️⃣ To‘g‘ri javob: <b>+{POINTS_PER_CORRECT}</b> ball va salyut 🎆. Noto‘g‘ri: 0 ball, izoh va to‘g‘ri javob.\n"
    "4️⃣ Test oxirida batafsil natija: foiz, vaqt, mavzular bo‘yicha tahlil va xatolar izohi.\n"
    "5️⃣ «🔁 Xatolarim ustida ishlash» — xato qilingan savollarni qayta ishlash.\n\n"
    "Buyruqlar: /start, /test, /profile, /history, /mistakes, /help"
)


def level_name(level: int | None) -> str:
    return LEVELS.get(level or 0, "🎲 Aralash")


def progress_bar(done: int, total: int, width: int = 10) -> str:
    filled = round(width * done / total) if total else 0
    return "▰" * filled + "▱" * (width - filled)


def score_line(score: int, correct: int, wrong: int, answered: int, total: int) -> str:
    return f"⭐ Ball: <b>{score}</b>  ·  ✅ {correct}  ·  ❌ {wrong}  ·  {answered}/{total}"


def question_text(q: QuestionView) -> str:
    title = "🔁 Xatolar ustida ishlash" if q.kind == "mistakes" else f"{e(q.subject)} › {e(q.topic)}"
    lines = [
        f"📝 <b>Savol {q.position}/{q.total}</b>  ·  {title}  ·  {level_name(q.level)}",
        f"{progress_bar(q.position - 1, q.total)}",
        score_line(q.score, q.correct_count, q.wrong_count, q.correct_count + q.wrong_count, q.total),
        "",
        f"<b>{e(q.text)}</b>",
        "",
    ]
    lines += [f"<b>{OPTION_LETTERS[i]})</b> {e(opt)}" for i, opt in enumerate(q.options)]
    return "\n".join(lines)


def answered_question_text(q: QuestionView, r: AnswerResult) -> str:
    """Javob berilgandan keyin savol xabari (tugmalarsiz) qanday ko'rinishi."""
    title = "🔁 Xatolar ustida ishlash" if q.kind == "mistakes" else f"{e(q.subject)} › {e(q.topic)}"
    lines = [f"📝 <b>Savol {q.position}/{q.total}</b>  ·  {title}  ·  {level_name(q.level)}", "",
             f"<b>{e(q.text)}</b>", ""]
    for i, opt in enumerate(q.options):
        mark = ""
        if i == r.correct_display_index:
            mark = " ✅"
        elif i == r.chosen_display_index:
            mark = " ❌"
        lines.append(f"<b>{OPTION_LETTERS[i]})</b> {e(opt)}{mark}")
    lines.append("")
    lines.append(f"Sizning javobingiz: <b>{OPTION_LETTERS[r.chosen_display_index]}</b>"
                 + (" — to‘g‘ri 🎉" if r.is_correct else " — xato"))
    return "\n".join(lines)


def correct_feedback(r: AnswerResult) -> str:
    return (f"<b>{CORRECT_HEADLINE}</b>\n🎆🎉🥳\n\n"
            + score_line(r.test_score, r.correct_count, r.wrong_count, r.answered_count, r.total)
            + f"\n🏅 Umumiy ballingiz: <b>{r.user_total_score}</b>")


def wrong_feedback(r: AnswerResult) -> str:
    letter = OPTION_LETTERS[r.correct_display_index]
    lines = [f"{WRONG_HEADLINE} <b>{letter}) {e(r.correct_text)}</b>"]
    if r.explanation:
        lines.append(f"\n💡 <b>Izoh:</b> {e(r.explanation)}")
    if r.kind == "mistakes":
        lines.append("\n📌 Bu savol «Xatolarim ustida ishlash» ro‘yxatida qoladi — keyinroq yana urinib ko‘ring.")
    else:
        lines.append("\n📌 Savol «🔁 Xatolarim ustida ishlash» bo‘limiga qo‘shildi.")
    lines.append("Ball ayirilmadi — davom etamiz! 💪\n")
    lines.append(score_line(r.test_score, r.correct_count, r.wrong_count, r.answered_count, r.total))
    return "\n".join(lines)


def encouragement(correct: int, total: int) -> str:
    """Faqat rag'batlantirish uchun. 30 talik test uchun: 27+/21+/15+/0+."""
    if total <= 0:
        return ""
    if correct * 10 >= total * 9:
        return "🏆 Ajoyib natija! Siz juda yaxshi tayyorlangansiz!"
    if correct * 10 >= total * 7:
        return "🎉 Juda yaxshi! Yana ozgina mashq qiling!"
    if correct * 10 >= total * 5:
        return "💪 Yaxshi harakat! Xatolar ustida ishlashni davom ettiring!"
    return "📚 Tushkunlikka tushmang! Mavzularni qayta o‘rganib, yana urinib ko‘ring!"


def duration(sec: int) -> str:
    h, rem = divmod(int(sec), 3600)
    m, s = divmod(rem, 60)
    parts = []
    if h:
        parts.append(f"{h} soat")
    if h or m:
        parts.append(f"{m} daqiqa")
    parts.append(f"{s} soniya")
    return " ".join(parts)


def summary_text(s: TestSummary) -> str:
    if s.kind == "mistakes":
        title = "🔁 Xatolar ustida ishlash yakunlandi!"
        scope = "Xato qilingan savollar"
    else:
        title = "🏁 Test yakunlandi!"
        scope = f"{e(s.subject)} › {e(s.topic or '🎲 Aralash (barcha mavzular)')} · {level_name(s.level)}"
    lines = [
        f"<b>{title}</b>",
        scope,
        "",
        f"📋 Jami savollar: <b>{s.total} ta</b>",
        f"✅ To‘g‘ri javoblar: <b>{s.correct}</b>",
        f"❌ Noto‘g‘ri javoblar: <b>{s.wrong}</b>",
    ]
    if s.unanswered:
        lines.append(f"⏭ Javob berilmagan: <b>{s.unanswered}</b>")
    lines += [
        f"⭐ To‘plangan ball: <b>{s.score}</b>",
        f"📊 Natija: <b>{s.percent}%</b>  {progress_bar(s.correct, s.total)}",
        f"⏱ Davomiylik: <b>{duration(s.duration_sec)}</b>",
        f"🏅 Umumiy ballingiz: <b>{s.user_total_score}</b>",
        "",
        f"<b>{encouragement(s.correct, s.total)}</b>",
    ]
    if s.by_topic:
        lines += ["", "📚 <b>Mavzular bo‘yicha natija:</b>"]
        for topic, ok, n in s.by_topic:
            pct = round(100 * ok / n) if n else 0
            icon = "🟢" if pct >= 80 else "🟡" if pct >= 50 else "🔴"
            lines.append(f"{icon} {e(topic)}: {ok}/{n} ({pct}%)")
    return "\n".join(lines)


def mistakes_blocks(s: TestSummary) -> list[str]:
    """Xato qilingan savollar va izohlar (bir nechta xabarga bo'linadi)."""
    if not s.mistakes:
        return ["🌟 <b>Bironta ham xato yo‘q!</b> Zo‘r natija! 🎆"] if s.correct == s.total else []
    items = []
    for m in s.mistakes:
        block = (f"<b>{m['position']}. {e(m['text'])}</b>\n"
                 f"   ❌ Sizning javobingiz: {e(m['chosen'])}\n"
                 f"   ✅ To‘g‘ri javob: {e(m['correct'])}")
        if m["explanation"]:
            block += f"\n   💡 {e(m['explanation'])}"
        items.append(block)
    return chunk_messages("🧐 <b>Xato qilingan savollar va izohlar:</b>", items)


def chunk_messages(header: str, items: list[str], limit: int = 3800) -> list[str]:
    out, cur = [], header
    for it in items:
        if len(cur) + len(it) + 2 > limit:
            out.append(cur)
            cur = it
        else:
            cur += "\n\n" + it
    out.append(cur)
    return out


def not_enough_text(available: int, required: int, subject: str, topic: str | None, level: int,
                    can_generate: bool) -> str:
    lines = [
        "⚠️ <b>Savollar yetarli emas</b>",
        "",
        f"{e(subject)} › {e(topic or 'Aralash')} · {level_name(level)} bo‘limida hozircha "
        f"<b>{available} ta</b> noyob savol bor, test uchun esa aynan <b>{required} ta</b> kerak.",
        "",
        "Testlar sifati uchun savollarni takrorlab 30 taga yetkazmaymiz.",
    ]
    if can_generate:
        lines.append("\n🤖 «Yangi savollar yaratish» tugmasini bosing — bot bu mavzu uchun yangi noyob savollar yaratadi.")
    lines.append("📥 Yoki «Bazani to‘ldirish» orqali yangi savollar qo‘shing (adminlar JSON fayl yuborishi mumkin).")
    lines.append("🔙 Yoki boshqa mavzu/darajani tanlang.")
    return "\n".join(lines)


IMPORT_HELP = (
    "📥 <b>Bazani to‘ldirish</b>\n\n"
    "Admin quyidagi formatdagi <b>.json</b> faylni <code>/import</code> izohi bilan yuborishi mumkin:\n\n"
    "<pre>[\n  {\"subject\": \"Tarix\", \"topic\": \"O‘zbekiston tarixi\",\n"
    "   \"level\": \"oson\", \"question\": \"Savol matni?\",\n"
    "   \"options\": [\"A\", \"B\", \"C\", \"D\"], \"correct\": 0,\n"
    "   \"explanation\": \"Qisqa izoh\"}\n]</pre>\n\n"
    "• <code>level</code>: oson / o‘rta / qiyin\n"
    "• <code>correct</code>: to‘g‘ri variant indeksi (0 dan) yoki matni\n"
    "• Dublikat savollar avtomatik o‘tkazib yuboriladi."
)


def profile_text(p: dict) -> str:
    acc = round(100 * p["total_correct"] / p["total_answered"]) if p["total_answered"] else 0
    return (
        f"👤 <b>{e(p['name'])}</b>\n\n"
        f"🏅 Umumiy ball: <b>{p['total_score']}</b>\n"
        f"🥇 Reyting: <b>{p['rank']}-o‘rin</b> ({p['users']} ta foydalanuvchi orasida)\n"
        f"📝 Yakunlangan testlar: <b>{p['tests_finished']}</b>\n"
        f"✅ To‘g‘ri javoblar: <b>{p['total_correct']}</b> / {p['total_answered']} ({acc}%)\n"
        f"🏆 Eng yaxshi natija: <b>{p['best_correct']}/30</b>\n"
        f"🔁 Ishlanmagan xatolar: <b>{p['open_mistakes']}</b>"
    )


def history_text(items: list[dict]) -> str:
    if not items:
        return "📜 Hali yakunlangan testlar yo‘q. «📝 Test boshlash» bilan boshlang!"
    lines = ["📜 <b>Oxirgi testlaringiz:</b>", ""]
    for t in items:
        date = datetime.fromtimestamp(t["started_at"]).strftime("%d.%m.%Y %H:%M")
        if t["kind"] == "mistakes":
            scope = "🔁 Xatolar ustida ishlash"
        else:
            scope = f"{e(t['subject'])} › {e(t['topic'] or 'Aralash')} · {level_name(t['level'])}"
        pct = round(100 * t["correct_count"] / t["total_questions"]) if t["total_questions"] else 0
        dur = duration((t["finished_at"] or t["started_at"]) - t["started_at"])
        lines.append(f"#{t['id']} · {date}\n{scope}\n✅ {t['correct_count']}/{t['total_questions']} · "
                     f"⭐ {t['score']} ball · {pct}% · ⏱ {dur}\n")
    return "\n".join(lines)
