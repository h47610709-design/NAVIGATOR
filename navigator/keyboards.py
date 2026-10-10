from __future__ import annotations

from aiogram.filters.callback_data import CallbackData
from aiogram.types import InlineKeyboardButton, InlineKeyboardMarkup, KeyboardButton, ReplyKeyboardMarkup
from aiogram.utils.keyboard import InlineKeyboardBuilder

from . import texts
from .config import OPTION_LETTERS
from .content.catalog import LEVELS


class SubjectCb(CallbackData, prefix="s"):
    subject_id: int


class TopicCb(CallbackData, prefix="t"):
    subject_id: int
    topic_id: int  # 0 = aralash


class LevelCb(CallbackData, prefix="l"):
    subject_id: int
    topic_id: int
    level: int


class AnswerCb(CallbackData, prefix="a"):
    test_id: int
    pos: int
    opt: int


class NextCb(CallbackData, prefix="n"):
    test_id: int


class ActiveCb(CallbackData, prefix="act"):
    action: str  # cont | drop
    test_id: int


class FillCb(CallbackData, prefix="f"):
    action: str  # gen | import
    subject_id: int
    topic_id: int
    level: int


class MistakesCb(CallbackData, prefix="m"):
    action: str  # start


class MenuCb(CallbackData, prefix="menu"):
    action: str  # subjects | mistakes


def main_menu() -> ReplyKeyboardMarkup:
    return ReplyKeyboardMarkup(
        keyboard=[
            [KeyboardButton(text=texts.BTN_START_TEST)],
            [KeyboardButton(text=texts.BTN_MISTAKES)],
            [KeyboardButton(text=texts.BTN_PROFILE), KeyboardButton(text=texts.BTN_HISTORY)],
            [KeyboardButton(text=texts.BTN_HELP)],
        ],
        resize_keyboard=True,
    )


def subjects_kb(subjects: list[dict]) -> InlineKeyboardMarkup:
    b = InlineKeyboardBuilder()
    for s in subjects:
        b.button(text=f"{s['emoji']} {s['name']} ({s['qcount']})", callback_data=SubjectCb(subject_id=s["id"]))
    b.adjust(1)
    return b.as_markup()


def topics_kb(subject_id: int, topics: list[dict]) -> InlineKeyboardMarkup:
    b = InlineKeyboardBuilder()
    for t in topics:
        b.button(text=f"📖 {t['name']} ({t['qcount']})", callback_data=TopicCb(subject_id=subject_id, topic_id=t["id"]))
    if len(topics) > 1:
        b.button(text="🎲 Aralash (barcha mavzular)", callback_data=TopicCb(subject_id=subject_id, topic_id=0))
    b.button(text="🔙 Fanlar", callback_data=MenuCb(action="subjects"))
    b.adjust(1)
    return b.as_markup()


def levels_kb(subject_id: int, topic_id: int) -> InlineKeyboardMarkup:
    b = InlineKeyboardBuilder()
    for lvl, name in LEVELS.items():
        b.button(text=name, callback_data=LevelCb(subject_id=subject_id, topic_id=topic_id, level=lvl))
    b.button(text="🔙 Mavzular", callback_data=SubjectCb(subject_id=subject_id))
    b.adjust(3, 1)
    return b.as_markup()


def answers_kb(test_id: int, pos: int, n_options: int) -> InlineKeyboardMarkup:
    row = [InlineKeyboardButton(text=OPTION_LETTERS[i], callback_data=AnswerCb(test_id=test_id, pos=pos, opt=i).pack())
           for i in range(n_options)]
    return InlineKeyboardMarkup(inline_keyboard=[row])


def next_kb(test_id: int, is_last: bool = False) -> InlineKeyboardMarkup:
    text = "🏁 Natijani ko‘rish" if is_last else "➡️ Keyingi savol"
    return InlineKeyboardMarkup(inline_keyboard=[[InlineKeyboardButton(text=text,
                                                                       callback_data=NextCb(test_id=test_id).pack())]])


def active_test_kb(test_id: int) -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(inline_keyboard=[
        [InlineKeyboardButton(text="▶️ Davom ettirish", callback_data=ActiveCb(action="cont", test_id=test_id).pack())],
        [InlineKeyboardButton(text="🗑 Yakunlamasdan bekor qilish",
                              callback_data=ActiveCb(action="drop", test_id=test_id).pack())],
    ])


def not_enough_kb(subject_id: int, topic_id: int, level: int, can_generate: bool) -> InlineKeyboardMarkup:
    b = InlineKeyboardBuilder()
    if can_generate:
        b.button(text="🤖 Yangi savollar yaratish",
                 callback_data=FillCb(action="gen", subject_id=subject_id, topic_id=topic_id, level=level))
    b.button(text="📥 Bazani to‘ldirish",
             callback_data=FillCb(action="import", subject_id=subject_id, topic_id=topic_id, level=level))
    b.button(text="🔙 Boshqa daraja", callback_data=TopicCb(subject_id=subject_id, topic_id=topic_id))
    b.button(text="🔙 Boshqa mavzu", callback_data=SubjectCb(subject_id=subject_id))
    b.adjust(1)
    return b.as_markup()


def after_summary_kb(has_mistakes: bool) -> InlineKeyboardMarkup:
    b = InlineKeyboardBuilder()
    b.button(text="📝 Yangi test", callback_data=MenuCb(action="subjects"))
    if has_mistakes:
        b.button(text="🔁 Xatolarim ustida ishlash", callback_data=MenuCb(action="mistakes"))
    b.adjust(1)
    return b.as_markup()


def mistakes_kb() -> InlineKeyboardMarkup:
    b = InlineKeyboardBuilder()
    b.button(text="▶️ Xatolarni qayta ishlash", callback_data=MistakesCb(action="start"))
    return b.as_markup()
