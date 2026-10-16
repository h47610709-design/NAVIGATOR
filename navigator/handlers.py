"""Telegram handlerlari (aiogram 3)."""
from __future__ import annotations

import json
import logging

from aiogram import Bot, F, Router
from aiogram.exceptions import TelegramBadRequest
from aiogram.filters import Command, CommandStart
from aiogram.types import CallbackQuery, Message

from . import keyboards as kb
from . import texts
from .config import Config
from .content.catalog import count_questions, generate_for, import_questions, topic_has_generator
from .effects import Celebrator
from .quiz import ActiveTestExists, NotEnoughQuestions, QuizError, QuizService, TestNotActive

log = logging.getLogger(__name__)
router = Router(name="navigator")


def _user(obj: Message | CallbackQuery, quiz: QuizService) -> int:
    u = obj.from_user
    quiz.ensure_user(u.id, u.first_name, u.username)
    return u.id


# ------------------------------------------------------------------ menyu
@router.message(CommandStart())
async def cmd_start(message: Message, quiz: QuizService):
    _user(message, quiz)
    await message.answer(texts.welcome(message.from_user.first_name or ""), reply_markup=kb.main_menu())


@router.message(Command("help"))
@router.message(F.text == texts.BTN_HELP)
async def cmd_help(message: Message, quiz: QuizService):
    _user(message, quiz)
    await message.answer(texts.HELP, reply_markup=kb.main_menu())


@router.message(Command("profile"))
@router.message(F.text == texts.BTN_PROFILE)
async def cmd_profile(message: Message, quiz: QuizService):
    uid = _user(message, quiz)
    await message.answer(texts.profile_text(quiz.profile(uid)))


@router.message(Command("history"))
@router.message(F.text == texts.BTN_HISTORY)
async def cmd_history(message: Message, quiz: QuizService):
    uid = _user(message, quiz)
    await message.answer(texts.history_text(quiz.history(uid)))


@router.message(Command("test"))
@router.message(F.text == texts.BTN_START_TEST)
async def cmd_test(message: Message, quiz: QuizService):
    uid = _user(message, quiz)
    active = quiz.active_test(uid)
    if active:
        await message.answer(_active_text(active), reply_markup=kb.active_test_kb(active["id"]))
        return
    await message.answer("📚 <b>Fanni tanlang:</b>", reply_markup=kb.subjects_kb(quiz.subjects()))


@router.callback_query(kb.MenuCb.filter(F.action == "subjects"))
async def cb_subjects(cb: CallbackQuery, quiz: QuizService):
    uid = _user(cb, quiz)
    await cb.answer()
    active = quiz.active_test(uid)
    if active:
        await cb.message.answer(_active_text(active), reply_markup=kb.active_test_kb(active["id"]))
        return
    await _edit_or_send(cb, "📚 <b>Fanni tanlang:</b>", kb.subjects_kb(quiz.subjects()))


@router.callback_query(kb.SubjectCb.filter())
async def cb_subject(cb: CallbackQuery, callback_data: kb.SubjectCb, quiz: QuizService):
    _user(cb, quiz)
    s = quiz.subject(callback_data.subject_id)
    if not s:
        await cb.answer("Fan topilmadi", show_alert=True)
        return
    await cb.answer()
    await _edit_or_send(cb, f"{s['emoji']} <b>{texts.e(s['name'])}</b>\n\n📖 Mavzuni tanlang:",
                        kb.topics_kb(s["id"], quiz.topics(s["id"])))


@router.callback_query(kb.TopicCb.filter())
async def cb_topic(cb: CallbackQuery, callback_data: kb.TopicCb, quiz: QuizService):
    _user(cb, quiz)
    s = quiz.subject(callback_data.subject_id)
    t = quiz.topic(callback_data.topic_id) if callback_data.topic_id else None
    if not s:
        await cb.answer("Fan topilmadi", show_alert=True)
        return
    await cb.answer()
    title = t["name"] if t else "🎲 Aralash (barcha mavzular)"
    await _edit_or_send(cb, f"{s['emoji']} <b>{texts.e(s['name'])}</b> › {texts.e(title)}\n\n🎯 Darajani tanlang:",
                        kb.levels_kb(s["id"], callback_data.topic_id))


@router.callback_query(kb.LevelCb.filter())
async def cb_level(cb: CallbackQuery, callback_data: kb.LevelCb, bot: Bot, quiz: QuizService):
    uid = _user(cb, quiz)
    await cb.answer()
    await _start_normal_test(bot, cb.message.chat.id, uid, quiz, callback_data.subject_id,
                             callback_data.topic_id, callback_data.level, cb)


async def _start_normal_test(bot: Bot, chat_id: int, uid: int, quiz: QuizService, subject_id: int,
                             topic_id: int, level: int, cb: CallbackQuery | None = None):
    s = quiz.subject(subject_id)
    t = quiz.topic(topic_id) if topic_id else None
    try:
        test_id = quiz.start_test(uid, subject_id, topic_id or None, level)
    except ActiveTestExists as e:
        active = quiz.active_test(uid)
        await bot.send_message(chat_id, _active_text(active), reply_markup=kb.active_test_kb(e.test_id))
        return
    except NotEnoughQuestions as e:
        can_gen = topic_has_generator(quiz.db, topic_id or None, subject_id)
        text = texts.not_enough_text(e.available, e.required, s["name"], t["name"] if t else None, level, can_gen)
        markup = kb.not_enough_kb(subject_id, topic_id, level, can_gen)
        if cb:
            await _edit_or_send(cb, text, markup)
        else:
            await bot.send_message(chat_id, text, reply_markup=markup)
        return
    scope = f"{s['emoji']} {texts.e(s['name'])} › {texts.e(t['name'] if t else 'Aralash')} · {texts.level_name(level)}"
    intro = (f"🚀 <b>Test boshlandi!</b>\n{scope}\n\n📋 Savollar soni: <b>{quiz.test_size} ta</b>\n"
             f"⭐ Har bir to‘g‘ri javob: <b>+10 ball</b>\nOmad! 🍀")
    if cb:
        await _edit_or_send(cb, intro, None)
    else:
        await bot.send_message(chat_id, intro)
    await send_next(bot, chat_id, uid, test_id, quiz)


def _active_text(active: dict) -> str:
    answered = active["correct_count"] + active["wrong_count"]
    return (f"⏳ Sizda yakunlanmagan test bor: <b>{answered}/{active['total_questions']}</b> savol, "
            f"⭐ {active['score']} ball.\n\nDavom ettirasizmi?")


async def _edit_or_send(cb: CallbackQuery, text: str, markup):
    try:
        await cb.message.edit_text(text, reply_markup=markup)
    except TelegramBadRequest:
        await cb.message.answer(text, reply_markup=markup)


# ------------------------------------------------------------------ savollar
async def send_next(bot: Bot, chat_id: int, uid: int, test_id: int, quiz: QuizService,
                    celebrator: Celebrator | None = None):
    pos = quiz.next_position(test_id)
    if pos is None:
        await send_summary(bot, chat_id, uid, test_id, quiz, celebrator)
        return
    q = quiz.get_question(uid, test_id, pos)
    await bot.send_message(chat_id, texts.question_text(q), reply_markup=kb.answers_kb(test_id, pos, len(q.options)))


async def send_summary(bot: Bot, chat_id: int, uid: int, test_id: int, quiz: QuizService,
                       celebrator: Celebrator | None = None):
    s = quiz.finish_test(uid, test_id)
    text = texts.summary_text(s)
    blocks = texts.mistakes_blocks(s)
    markup = kb.after_summary_kb(quiz.count_mistakes(uid) > 0)
    if celebrator and s.total and s.correct * 10 >= s.total * 7:
        await celebrator.send_with_effect(chat_id, text, reply_markup=None if blocks else markup)
    else:
        await bot.send_message(chat_id, text, reply_markup=None if blocks else markup)
    for i, block in enumerate(blocks):
        await bot.send_message(chat_id, block, reply_markup=markup if i == len(blocks) - 1 else None)


@router.callback_query(kb.AnswerCb.filter())
async def cb_answer(cb: CallbackQuery, callback_data: kb.AnswerCb, bot: Bot, quiz: QuizService,
                    celebrator: Celebrator):
    uid = _user(cb, quiz)
    try:
        r = quiz.answer(uid, callback_data.test_id, callback_data.pos, callback_data.opt)
    except TestNotActive:
        await cb.answer("Bu test allaqachon yakunlangan.", show_alert=True)
        return
    except QuizError as e:
        await cb.answer(str(e), show_alert=True)
        return
    if r.already_answered:
        await cb.answer("⚠️ Bu savolga allaqachon javob bergansiz. Ball qayta berilmaydi.", show_alert=True)
        return
    await cb.answer("🎉 +10 ball!" if r.is_correct else "🙂 Xato — ball ayirilmaydi")
    # Savol xabarini yangilaymiz: tugmalar olib tashlanadi, to'g'ri/xato belgilanadi
    try:
        q = quiz.get_question(uid, callback_data.test_id, callback_data.pos)
        await cb.message.edit_text(texts.answered_question_text(q, r), reply_markup=None)
    except TelegramBadRequest:
        pass
    chat_id = cb.message.chat.id
    markup = None if r.is_last else kb.next_kb(callback_data.test_id)
    if r.is_correct:
        await celebrator.celebrate(chat_id, texts.correct_feedback(r), markup)
    else:
        await bot.send_message(chat_id, texts.wrong_feedback(r), reply_markup=markup)
    if r.is_last:
        await send_summary(bot, chat_id, uid, callback_data.test_id, quiz, celebrator)


@router.callback_query(kb.NextCb.filter())
async def cb_next(cb: CallbackQuery, callback_data: kb.NextCb, bot: Bot, quiz: QuizService, celebrator: Celebrator):
    uid = _user(cb, quiz)
    active = quiz.active_test(uid)
    if not active or active["id"] != callback_data.test_id:
        await cb.answer("Bu test yakunlangan yoki bekor qilingan.", show_alert=True)
        return
    await cb.answer()
    try:
        await cb.message.edit_reply_markup(reply_markup=None)
    except TelegramBadRequest:
        pass
    await send_next(bot, cb.message.chat.id, uid, callback_data.test_id, quiz, celebrator)


@router.callback_query(kb.ActiveCb.filter())
async def cb_active(cb: CallbackQuery, callback_data: kb.ActiveCb, bot: Bot, quiz: QuizService,
                    celebrator: Celebrator):
    uid = _user(cb, quiz)
    if callback_data.action == "drop":
        ok = quiz.abandon_test(uid, callback_data.test_id)
        await cb.answer("Test bekor qilindi" if ok else "Test topilmadi")
        await _edit_or_send(cb, "🗑 Test bekor qilindi. To‘plangan ballar saqlanib qoladi.\n\n📚 <b>Fanni tanlang:</b>",
                            kb.subjects_kb(quiz.subjects()))
        return
    active = quiz.active_test(uid)
    if not active or active["id"] != callback_data.test_id:
        await cb.answer("Bu test yakunlangan.", show_alert=True)
        return
    await cb.answer()
    await send_next(bot, cb.message.chat.id, uid, callback_data.test_id, quiz, celebrator)


# ------------------------------------------------------------------ bazani to'ldirish
@router.callback_query(kb.FillCb.filter(F.action == "gen"))
async def cb_generate(cb: CallbackQuery, callback_data: kb.FillCb, bot: Bot, quiz: QuizService):
    uid = _user(cb, quiz)
    d = callback_data
    await cb.answer("🤖 Yangi savollar yaratilmoqda...")
    topic_ids = [d.topic_id] if d.topic_id else [t["id"] for t in quiz.topics(d.subject_id)]
    added = 0
    for tid in topic_ids:
        need = max(40, quiz.test_size - count_questions(quiz.db, d.subject_id, tid, d.level) + 10)
        added += generate_for(quiz.db, tid, d.level, need)
    await cb.message.answer(f"✅ Bazaga <b>{added} ta</b> yangi noyob savol qo‘shildi.")
    await _start_normal_test(bot, cb.message.chat.id, uid, quiz, d.subject_id, d.topic_id, d.level)


@router.callback_query(kb.FillCb.filter(F.action == "import"))
async def cb_import_info(cb: CallbackQuery, callback_data: kb.FillCb, bot: Bot, quiz: QuizService, config: Config):
    uid = _user(cb, quiz)
    await cb.answer()
    if uid in config.admin_ids:
        await cb.message.answer(texts.IMPORT_HELP)
        return
    s = quiz.subject(callback_data.subject_id)
    t = quiz.topic(callback_data.topic_id) if callback_data.topic_id else None
    have = count_questions(quiz.db, callback_data.subject_id, callback_data.topic_id or None, callback_data.level)
    note = (f"📥 So‘rov: {texts.e(s['name'])} › {texts.e(t['name'] if t else 'Aralash')} · "
            f"{texts.level_name(callback_data.level)} — hozir {have} ta savol, 30 ta kerak. "
            f"Foydalanuvchi: {uid}")
    sent = 0
    for admin in config.admin_ids:
        try:
            await bot.send_message(admin, note)
            sent += 1
        except TelegramBadRequest:
            pass
    await cb.message.answer("📨 Adminlarga bazani to‘ldirish haqida so‘rov yuborildi. Rahmat!" if sent else
                            "ℹ️ Bazani faqat adminlar to‘ldira oladi. Hozircha boshqa mavzu yoki darajani tanlang.")


@router.message(F.document, F.caption.startswith("/import"))
async def doc_import(message: Message, bot: Bot, quiz: QuizService, config: Config):
    uid = _user(message, quiz)
    if uid not in config.admin_ids:
        await message.answer("⛔ Savollarni faqat adminlar import qila oladi.")
        return
    try:
        buf = await bot.download(message.document)
        data = json.loads(buf.read().decode("utf-8-sig"))
    except (ValueError, UnicodeDecodeError) as e:
        await message.answer(f"❌ JSON o‘qilmadi: {texts.e(e)}")
        return
    added, errors = import_questions(quiz.db, data, source=f"tg:{uid}")
    text = f"✅ Import yakunlandi: <b>{added} ta</b> yangi savol qo‘shildi."
    if errors:
        text += f"\n⚠️ Xatolar ({len(errors)}):\n" + "\n".join(texts.e(x) for x in errors[:15])
    await message.answer(text)


# ------------------------------------------------------------------ xatolar ustida ishlash
@router.message(Command("mistakes"))
@router.message(F.text == texts.BTN_MISTAKES)
async def cmd_mistakes(message: Message, quiz: QuizService):
    uid = _user(message, quiz)
    text, markup = _mistakes_view(uid, quiz)
    await message.answer(text, reply_markup=markup)


@router.callback_query(kb.MenuCb.filter(F.action == "mistakes"))
async def cb_mistakes_menu(cb: CallbackQuery, quiz: QuizService):
    uid = _user(cb, quiz)
    await cb.answer()
    text, markup = _mistakes_view(uid, quiz)
    await cb.message.answer(text, reply_markup=markup)


def _mistakes_view(uid: int, quiz: QuizService):
    n = quiz.count_mistakes(uid)
    if not n:
        return "🌟 Sizda ishlanmagan xatolar yo‘q! Yangi test bilan bilimingizni sinang.", None
    lines = [f"🔁 <b>Xatolarim ustida ishlash</b>\n\nIshlanmagan xatolar: <b>{n} ta</b>\n"]
    for row in quiz.mistakes_overview(uid)[:10]:
        lines.append(f"• {texts.e(row['subject'])} › {texts.e(row['topic'])}: {row['n']} ta")
    lines.append(f"\nBir mashg‘ulotda {min(n, quiz.test_size)} ta savol. To‘g‘ri javob — +10 ball va savol "
                 "ro‘yxatdan chiqariladi. Xato bo‘lsa, ro‘yxatda qoladi.")
    return "\n".join(lines), kb.mistakes_kb()


@router.callback_query(kb.MistakesCb.filter(F.action == "start"))
async def cb_mistakes_start(cb: CallbackQuery, bot: Bot, quiz: QuizService, celebrator: Celebrator):
    uid = _user(cb, quiz)
    try:
        test_id = quiz.start_mistakes_test(uid)
    except ActiveTestExists as e:
        await cb.answer()
        await cb.message.answer(_active_text(quiz.active_test(uid)), reply_markup=kb.active_test_kb(e.test_id))
        return
    except NotEnoughQuestions:
        await cb.answer("Ishlanmagan xatolar yo‘q 🎉", show_alert=True)
        return
    await cb.answer()
    await _edit_or_send(cb, "🔁 <b>Xatolar ustida ishlash boshlandi!</b> Omad! 🍀", None)
    await send_next(bot, cb.message.chat.id, uid, test_id, quiz, celebrator)
