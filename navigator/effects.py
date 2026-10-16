"""To'g'ri javob uchun 🎆 salyut effektlari.

Ustuvorlik tartibi (bittasi ishlamasa, keyingisiga o'tiladi):
1. Animatsion Telegram sticker (``CELEBRATION_STICKER_SET`` yoki ``CELEBRATION_STICKERS``)
2. GIF/MP4 (``CELEBRATION_GIFS``) yoki botning o'zi yaratgan salyut GIF'i
3. Chiroyli emoji + matnli effekt

Bundan tashqari tabrik xabari Telegram'ning to'liq ekranli 🎉 "message effect"i
bilan yuboriladi (shaxsiy chatlarda). Qo'llab-quvvatlanmasa — effektsiz qayta yuboriladi.
"""
from __future__ import annotations

import logging
import random
from dataclasses import dataclass
from pathlib import Path

from aiogram import Bot
from aiogram.exceptions import TelegramAPIError, TelegramBadRequest
from aiogram.types import FSInputFile, InlineKeyboardMarkup

from .config import Config
from .db import Database

log = logging.getLogger(__name__)

CELEBRATION_EMOJIS = set("🎆🎇🎉🥳🎊✨🏆⭐🌟💥") | {"🔥", "👏"}

TEXT_FIREWORKS = [
    "🎆 ✨ 🎇 ✨ 🎆\n✨ 🥳 <b>ZO‘R!</b> 🥳 ✨\n🎇 ✨ 🎆 ✨ 🎇",
    "· 🎇 · · 🎆 · · 🎇 ·\n🎉 <b>Salyut sizning sharafingizga!</b> 🎉\n· 🎆 · · 🎇 · · 🎆 ·",
    "💥🎆💥🎆💥\n🌟 <b>Ajoyib!</b> 🌟\n🎊🎇🎊🎇🎊",
]
CHEERS = [
    "🎆 Salyut! Shunday davom eting!",
    "🥳 Siz zo‘rsiz! Keyingi savolga tayyormisiz?",
    "🎇 Ajoyib! Bilimingiz porlayapti!",
    "🎊 Barakalla! Yana bitta g‘alaba!",
]


@dataclass
class _Media:
    kind: str      # "sticker" | "gif" | "local_gif"
    ref: str       # file_id / URL / lokal yo'l


class Celebrator:
    def __init__(self, bot: Bot, db: Database, config: Config, local_gifs: list[Path] | None = None):
        self.bot = bot
        self.db = db
        self.config = config
        self.effect_id = config.effect_id
        self.media: list[_Media] = [_Media("sticker", s) for s in config.sticker_ids]
        self.media += [_Media("gif", g) for g in config.gif_sources]
        self.local: list[_Media] = [_Media("local_gif", str(p)) for p in (local_gifs or [])]

    async def setup(self) -> None:
        """Sticker to'plamidan salyut/bayram emojili animatsion stickerlarni yuklaydi."""
        if not self.config.sticker_set:
            return
        try:
            sset = await self.bot.get_sticker_set(self.config.sticker_set)
        except TelegramAPIError as e:
            log.warning("Sticker to'plami yuklanmadi (%s): %s", self.config.sticker_set, e)
            return
        good = [s for s in sset.stickers if (s.is_animated or s.is_video) and s.emoji and
                any(ch in CELEBRATION_EMOJIS for ch in s.emoji)]
        if not good:
            good = [s for s in sset.stickers if s.is_animated or s.is_video]
        self.media += [_Media("sticker", s.file_id) for s in good]
        log.info("Salyut uchun %d ta animatsion sticker topildi", len(good))

    # ------------------------------------------------------------------
    async def send_with_effect(self, chat_id: int, text: str, **kw):
        """Xabarni 🎉 message effect bilan yuboradi; effekt mumkin bo'lmasa — oddiy."""
        if self.effect_id:
            try:
                return await self.bot.send_message(chat_id, text, message_effect_id=self.effect_id, **kw)
            except TelegramBadRequest as e:
                log.info("Message effect ishlamadi (%s), effektsiz yuborilmoqda", e)
        return await self.bot.send_message(chat_id, text, **kw)

    async def _send_media(self, chat_id: int, m: _Media, keyboard: InlineKeyboardMarkup | None) -> bool:
        caption = random.choice(CHEERS)
        try:
            if m.kind == "sticker":
                await self.bot.send_sticker(chat_id, m.ref, reply_markup=keyboard)
                return True
            if m.kind == "gif":
                await self.bot.send_animation(chat_id, m.ref, caption=caption, reply_markup=keyboard)
                return True
            # Lokal GIF: birinchi marta yuklanadi, keyin file_id keshdan olinadi
            key = f"gif_file_id:{Path(m.ref).name}"
            cached = self.db.kv_get(key)
            if cached:
                try:
                    await self.bot.send_animation(chat_id, cached, caption=caption, reply_markup=keyboard)
                    return True
                except TelegramBadRequest:
                    self.db.kv_delete(key)  # token o'zgargan bo'lishi mumkin — qayta yuklaymiz
            msg = await self.bot.send_animation(chat_id, FSInputFile(m.ref, filename="salyut.gif"),
                                                caption=caption, reply_markup=keyboard)
            fid = (msg.animation or msg.document).file_id if (msg.animation or msg.document) else None
            if fid:
                self.db.kv_set(key, fid)
            return True
        except TelegramAPIError as e:
            log.warning("Salyut media yuborilmadi (%s): %s", m.kind, e)
            return False

    async def celebrate(self, chat_id: int, header: str, keyboard: InlineKeyboardMarkup | None) -> None:
        """To'g'ri javob: tabrik xabari (+effekt) → animatsion sticker/GIF (+keyingi savol tugmasi)."""
        await self.send_with_effect(chat_id, header)
        candidates = self.media[:] or self.local[:]
        random.shuffle(candidates)
        fallbacks = [m for m in self.local if m not in candidates]
        for m in candidates + fallbacks:
            if await self._send_media(chat_id, m, keyboard):
                return
        # Animatsion fayl yo'q / yuborib bo'lmadi — chiroyli matnli effekt
        await self.bot.send_message(chat_id, random.choice(TEXT_FIREWORKS), reply_markup=keyboard)
