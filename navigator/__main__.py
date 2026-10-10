"""Ishga tushirish: ``python -m navigator``"""
from __future__ import annotations

import asyncio
import logging
import sys

from aiogram import Bot, Dispatcher
from aiogram.client.default import DefaultBotProperties
from aiogram.enums import ParseMode
from aiogram.types import BotCommand

from .config import Config
from .content.catalog import seed
from .db import Database
from .effects import Celebrator
from .fireworks import ensure_fireworks
from .handlers import router
from .quiz import QuizService


def build(config: Config, bot: Bot | None = None) -> tuple[Dispatcher, Bot, Database, Celebrator]:
    db = Database(config.db_path)
    seed(db)
    bot = bot or Bot(config.bot_token, default=DefaultBotProperties(parse_mode=ParseMode.HTML))
    try:
        gifs = ensure_fireworks()
    except Exception:  # noqa: BLE001 — GIF yaratilmasa ham bot ishlayveradi (matnli effekt)
        logging.exception("Salyut GIF yaratilmadi")
        gifs = []
    celebrator = Celebrator(bot, db, config, gifs)
    dp = Dispatcher(quiz=QuizService(db), celebrator=celebrator, config=config)
    if router.parent_router is not None:  # build() qayta chaqirilganda (masalan, testlarda)
        router._parent_router = None
    dp.include_router(router)
    return dp, bot, db, celebrator


async def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    config = Config.from_env()
    if not config.bot_token:
        sys.exit("BOT_TOKEN topilmadi. .env faylini yarating (.env.example ga qarang).")
    dp, bot, db, celebrator = build(config)
    try:
        await celebrator.setup()
        await bot.set_my_commands([
            BotCommand(command="start", description="Bosh menyu"),
            BotCommand(command="test", description="Yangi test (30 ta savol)"),
            BotCommand(command="mistakes", description="Xatolarim ustida ishlash"),
            BotCommand(command="profile", description="Profil va umumiy ball"),
            BotCommand(command="history", description="Test tarixi"),
            BotCommand(command="help", description="Yordam"),
        ])
        await dp.start_polling(bot)
    finally:
        db.close()
        await bot.session.close()


if __name__ == "__main__":
    asyncio.run(main())
