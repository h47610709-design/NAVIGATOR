"""End-to-end: aiogram Dispatcher + soxta Telegram sessiyasi (tarmoqsiz)."""
import itertools
import json
import time
from typing import Any

import pytest
from aiogram import Bot
from aiogram.client.default import DefaultBotProperties
from aiogram.client.session.base import BaseSession
from aiogram.exceptions import TelegramBadRequest
from aiogram.methods import (AnswerCallbackQuery, EditMessageReplyMarkup, EditMessageText, SendAnimation,
                             SendMessage, SendSticker, TelegramMethod)
from aiogram.types import Update

from navigator.__main__ import build
from navigator.config import Config
from navigator.texts import CORRECT_HEADLINE, WRONG_HEADLINE

UID = 777
_ids = itertools.count(100)


class FakeSession(BaseSession):
    def __init__(self, reject_effects: bool = False):
        super().__init__()
        self.calls: list[TelegramMethod] = []
        self.reject_effects = reject_effects

    async def make_request(self, bot, method: TelegramMethod, timeout=None) -> Any:
        if self.reject_effects and getattr(method, "message_effect_id", None):
            raise TelegramBadRequest(method=method, message="Bad Request: EFFECT_ID_INVALID")
        self.calls.append(method)
        base = {"message_id": next(_ids), "date": int(time.time()), "chat": {"id": UID, "type": "private"}}
        if isinstance(method, (SendMessage, EditMessageText)):
            return _msg(bot, {**base, "text": method.text})
        if isinstance(method, SendAnimation):
            return _msg(bot, {**base, "animation": {"file_id": "GIF_FILE_ID", "file_unique_id": "u",
                                                     "width": 320, "height": 320, "duration": 2}})
        if isinstance(method, SendSticker):
            return _msg(bot, {**base, "sticker": {"file_id": method.sticker, "file_unique_id": "s", "type": "regular",
                                                   "width": 512, "height": 512, "is_animated": True,
                                                   "is_video": False}})
        if isinstance(method, (AnswerCallbackQuery, EditMessageReplyMarkup)):
            return True
        return True

    async def close(self):
        pass

    async def stream_content(self, *a, **kw):  # pragma: no cover
        yield b""


def _msg(bot, data):
    from aiogram.types import Message
    return Message.model_validate(data, context={"bot": bot})


class Harness:
    def __init__(self, tmp_path, **session_kw):
        self.session = FakeSession(**session_kw)
        self.bot = Bot("42:TEST", session=self.session, default=DefaultBotProperties(parse_mode="HTML"))
        self.config = Config(bot_token="42:TEST", db_path=str(tmp_path / "bot.db"))
        self.dp, _, self.db, self.celebrator = build(self.config, self.bot)
        self.quiz = self.dp["quiz"]
        self.uid = itertools.count(1)

    def _user(self):
        return {"id": UID, "is_bot": False, "first_name": "Ali"}

    async def text(self, text):
        self.session.calls.clear()
        upd = Update.model_validate({"update_id": next(self.uid), "message": {
            "message_id": next(_ids), "date": int(time.time()), "chat": {"id": UID, "type": "private"},
            "from": self._user(), "text": text}}, context={"bot": self.bot})
        await self.dp.feed_update(self.bot, upd)
        return list(self.session.calls)

    async def press(self, data):
        self.session.calls.clear()
        upd = Update.model_validate({"update_id": next(self.uid), "callback_query": {
            "id": str(next(_ids)), "from": self._user(), "chat_instance": "ci", "data": data,
            "message": {"message_id": next(_ids), "date": int(time.time()),
                        "chat": {"id": UID, "type": "private"}, "text": "x"}}}, context={"bot": self.bot})
        await self.dp.feed_update(self.bot, upd)
        return list(self.session.calls)

    def last_buttons(self, calls):
        for c in reversed(calls):
            rm = getattr(c, "reply_markup", None)
            if rm is not None and hasattr(rm, "inline_keyboard"):
                return [b for row in rm.inline_keyboard for b in row]
        return []


def correct_opt(db, test_id, pos):
    r = db.one("SELECT tq.option_order, q.correct_index FROM test_questions tq JOIN questions q "
               "ON q.id = tq.question_id WHERE tq.test_id = ? AND tq.position = ?", (test_id, pos))
    return json.loads(r["option_order"]).index(r["correct_index"])


async def start_test(h: Harness, subject="Matematika", topic="Arifmetika", level=1):
    await h.text("/start")
    calls = await h.text("📝 Test boshlash")
    subj = next(b for b in h.last_buttons(calls) if subject in b.text)
    calls = await h.press(subj.callback_data)
    top = next(b for b in h.last_buttons(calls) if topic in b.text)
    calls = await h.press(top.callback_data)
    lvl = h.last_buttons(calls)[level - 1]
    return await h.press(lvl.callback_data)


async def test_full_30_question_flow_with_fireworks(tmp_path):
    h = Harness(tmp_path)
    calls = await start_test(h)
    q1 = [c for c in calls if isinstance(c, SendMessage) and "Savol 1/30" in c.text]
    assert q1, "Birinchi savol yuborilmadi"
    test_id = h.quiz.active_test(UID)["id"]

    for pos in range(1, 31):
        c = correct_opt(h.db, test_id, pos)
        opt = c if pos % 5 else (c + 1) % 4  # har 5-savol xato → 24 to'g'ri
        calls = await h.press(f"a:{test_id}:{pos}:{opt}")
        sends = [x for x in calls if isinstance(x, SendMessage)]
        if opt == c:
            congrats = sends[0]
            assert congrats.text.startswith(f"<b>{CORRECT_HEADLINE}</b>")
            assert congrats.message_effect_id == "5046509860389126442"
            assert "🎆🎉🥳" in congrats.text and "Ball:" in congrats.text
            anim = [x for x in calls if isinstance(x, SendAnimation)]
            assert anim, "Salyut GIF yuborilmadi"
            if pos < 30:
                assert anim[0].reply_markup.inline_keyboard[0][0].text == "➡️ Keyingi savol"
        else:
            assert sends[0].text.startswith(WRONG_HEADLINE)
            assert "Izoh:" in sends[0].text and "Xatolarim ustida ishlash" in sends[0].text
        # takroriy bosish → ogohlantirish, ball yo'q
        dup = await h.press(f"a:{test_id}:{pos}:{opt}")
        alert = next(x for x in dup if isinstance(x, AnswerCallbackQuery))
        assert alert.show_alert and "allaqachon" in alert.text
        if pos < 30:
            calls = await h.press(f"n:{test_id}")
            assert any(isinstance(x, SendMessage) and f"Savol {pos + 1}/30" in x.text for x in calls)

    # 30-javobdan keyin natija avtomatik chiqadi
    texts_ = [x.text for x in calls if isinstance(x, SendMessage)]
    summary = next(t for t in texts_ if "Test yakunlandi" in t)
    for needle in ("Jami savollar: <b>30 ta</b>", "To‘g‘ri javoblar: <b>24</b>", "Noto‘g‘ri javoblar: <b>6</b>",
                   "To‘plangan ball: <b>240</b>", "Natija: <b>80%</b>", "Davomiylik:", "Mavzular bo‘yicha natija",
                   "🎉 Juda yaxshi! Yana ozgina mashq qiling!"):
        assert needle in summary, needle
    assert any("Xato qilingan savollar va izohlar" in t for t in texts_)
    # Profil va GIF keshi
    assert h.quiz.profile(UID)["total_score"] == 240
    assert h.db.kv_get("gif_file_id:fireworks_0.gif") or h.db.kv_get("gif_file_id:fireworks_1.gif") \
        or h.db.kv_get("gif_file_id:fireworks_2.gif")
    prof = await h.text("👤 Profil")
    assert "Umumiy ball: <b>240</b>" in prof[0].text
    hist = await h.text("📜 Test tarixi")
    assert "24/30" in hist[0].text

    # Xatolarim ustida ishlash
    calls = await h.text("🔁 Xatolarim ustida ishlash")
    assert "Ishlanmagan xatolar: <b>6 ta</b>" in calls[0].text
    calls = await h.press("m:start")
    assert any(isinstance(x, SendMessage) and "Savol 1/6" in x.text for x in calls)


async def test_effect_fallback_and_sticker_mode(tmp_path):
    h = Harness(tmp_path, reject_effects=True)
    h.celebrator.media = [type(h.celebrator.local[0])("sticker", "STICKER_ID")]
    await start_test(h, "Geografiya", "poytaxt", 1)
    test_id = h.quiz.active_test(UID)["id"]
    calls = await h.press(f"a:{test_id}:1:{correct_opt(h.db, test_id, 1)}")
    sends = [x for x in calls if isinstance(x, SendMessage)]
    assert sends[0].text.startswith(f"<b>{CORRECT_HEADLINE}</b>") and sends[0].message_effect_id is None
    sticker = next(x for x in calls if isinstance(x, SendSticker))
    assert sticker.sticker == "STICKER_ID" and sticker.reply_markup.inline_keyboard[0][0].text == "➡️ Keyingi savol"


async def test_text_fallback_when_no_animation(tmp_path):
    h = Harness(tmp_path)
    h.celebrator.media, h.celebrator.local = [], []
    await start_test(h)
    test_id = h.quiz.active_test(UID)["id"]
    calls = await h.press(f"a:{test_id}:1:{correct_opt(h.db, test_id, 1)}")
    sends = [x for x in calls if isinstance(x, SendMessage)]
    assert len(sends) == 2 and "🎆" in sends[1].text and sends[1].reply_markup is not None


async def test_not_enough_questions_offers_fill(tmp_path):
    h = Harness(tmp_path)
    calls = await start_test(h, "Tarix", "O‘zbekiston tarixi", 1)
    msg = next(x for x in calls if isinstance(x, (SendMessage, EditMessageText)) and "yetarli emas" in x.text)
    assert "<b>12 ta</b>" in msg.text
    labels = [b.text for b in h.last_buttons(calls)]
    assert "📥 Bazani to‘ldirish" in labels and "🤖 Yangi savollar yaratish" not in labels
    assert h.quiz.active_test(UID) is None


async def test_generate_button_creates_questions_and_starts(tmp_path):
    h = Harness(tmp_path)
    s = h.db.one("SELECT id FROM subjects WHERE name='Matematika'")["id"]
    t = h.db.one("SELECT id FROM topics WHERE name='Foizlar'")["id"]
    h.db.execute("DELETE FROM questions WHERE topic_id = ? AND level = 1 AND id NOT IN "
                 "(SELECT id FROM questions WHERE topic_id = ? AND level = 1 LIMIT 5)", (t, t))
    calls = await start_test(h, "Matematika", "Foizlar", 1)
    gen = next(b for b in h.last_buttons(calls) if "Yangi savollar" in b.text)
    calls = await h.press(gen.callback_data)
    assert any(isinstance(x, SendMessage) and "yangi noyob savol" in x.text for x in calls)
    assert any(isinstance(x, SendMessage) and "Savol 1/30" in x.text for x in calls)
    assert h.quiz.active_test(UID)["subject_id"] == s
