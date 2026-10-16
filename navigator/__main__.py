"""Ishga tushirish: ``python -m navigator``  ·  Tekshirish: ``python -m navigator --check``"""
from __future__ import annotations

import argparse
import asyncio
import logging
import logging.handlers
import os
import re
import sys
from pathlib import Path

from aiogram import Bot, Dispatcher
from aiogram.client.default import DefaultBotProperties
from aiogram.enums import ParseMode
from aiogram.exceptions import (TelegramAPIError, TelegramNetworkError, TelegramRetryAfter, TelegramServerError,
                                TelegramUnauthorizedError)
from aiogram.types import BotCommand, ErrorEvent, User

from .config import Config
from .content.catalog import seed
from .db import Database
from .effects import Celebrator
from .fireworks import ensure_fireworks
from .handlers import router
from .quiz import QuizService

log = logging.getLogger("navigator")

TOKEN_RE = re.compile(r"^\d{5,15}:[A-Za-z0-9_-]{30,}$")
BOT_COMMANDS = [
    BotCommand(command="start", description="Bosh menyu"),
    BotCommand(command="test", description="Yangi test (30 ta savol)"),
    BotCommand(command="mistakes", description="Xatolarim ustida ishlash"),
    BotCommand(command="profile", description="Profil va umumiy ball"),
    BotCommand(command="history", description="Test tarixi"),
    BotCommand(command="help", description="Yordam"),
]


# ------------------------------------------------------------------ logging
class RedactSecrets(logging.Filter):
    """Token tasodifan log matniga tushsa ham, uni *** bilan almashtiradi."""

    def __init__(self, secrets: list[str]):
        super().__init__()
        self.secrets = [s for s in secrets if s]

    def filter(self, record: logging.LogRecord) -> bool:
        if not self.secrets:
            return True
        msg = record.getMessage()
        exc = logging.Formatter().formatException(record.exc_info) if record.exc_info else ""
        if any(s in msg or s in exc for s in self.secrets):
            for s in self.secrets:
                msg = msg.replace(s, "***")
                exc = exc.replace(s, "***")
            record.msg = msg + (("\n" + exc) if exc else "")
            record.args = None
            record.exc_info = None
            record.exc_text = None
        return True


def setup_logging(secrets: list[str]) -> None:
    level = os.getenv("LOG_LEVEL", "INFO").upper()
    fmt = logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s")
    handlers: list[logging.Handler] = [logging.StreamHandler(sys.stdout)]  # hosting loglari (journalctl / panel)
    log_file = os.getenv("LOG_FILE", "").strip()
    if log_file:
        Path(log_file).parent.mkdir(parents=True, exist_ok=True)
        handlers.append(logging.handlers.RotatingFileHandler(log_file, maxBytes=2_000_000, backupCount=3,
                                                             encoding="utf-8"))
    redact = RedactSecrets(secrets)
    root = logging.getLogger()
    root.handlers.clear()
    root.setLevel(level)
    for h in handlers:
        h.setFormatter(fmt)
        h.addFilter(redact)
        root.addHandler(h)

    def excepthook(exc_type, exc, tb):
        logging.getLogger("navigator").critical("Kutilmagan xato — bot to‘xtadi", exc_info=(exc_type, exc, tb))

    sys.excepthook = excepthook


def validate_config(config: Config) -> list[str]:
    """Xatolar ro'yxati (token qiymati hech qachon chiqarilmaydi)."""
    errors = []
    if not config.bot_token:
        errors.append("BOT_TOKEN topilmadi. Hostingning Environment Variables bo‘limiga BOT_TOKEN qo‘shing "
                      "(lokal ishga tushirishda — .env fayliga).")
    elif not TOKEN_RE.match(config.bot_token):
        errors.append("BOT_TOKEN formati noto‘g‘ri (kutilgan ko‘rinish: 123456789:ABC...). "
                      "Ortiqcha bo‘sh joy yoki qo‘shtirnoq yo‘qligini tekshiring.")
    return errors


# ------------------------------------------------------------------ build
async def on_error(event: ErrorEvent) -> bool:
    """Handlerdagi har qanday xato logga yoziladi, foydalanuvchiga esa yumshoq xabar."""
    upd = event.update
    log.exception("Update #%s ni qayta ishlashda xato: %s", upd.update_id, event.exception,
                  exc_info=event.exception)
    try:
        if upd.callback_query:
            await upd.callback_query.answer("⚠️ Xatolik yuz berdi. Iltimos, qayta urinib ko‘ring.", show_alert=True)
        elif upd.message:
            await upd.message.answer("⚠️ Xatolik yuz berdi. Iltimos, qayta urinib ko‘ring yoki /start bosing.")
    except Exception:  # noqa: BLE001
        pass
    return True


def build(config: Config, bot: Bot | None = None) -> tuple[Dispatcher, Bot, Database, Celebrator]:
    db = Database(config.db_path)
    seed(db)
    bot = bot or Bot(config.bot_token, default=DefaultBotProperties(parse_mode=ParseMode.HTML))
    try:
        gifs = ensure_fireworks()
    except Exception:  # noqa: BLE001 — GIF yaratilmasa ham bot ishlayveradi (matnli effekt)
        log.exception("Salyut GIF yaratilmadi — matnli effekt ishlatiladi")
        gifs = []
    celebrator = Celebrator(bot, db, config, gifs)
    dp = Dispatcher(quiz=QuizService(db), celebrator=celebrator, config=config)
    if router.parent_router is not None:  # build() qayta chaqirilganda (masalan, testlarda)
        router._parent_router = None
    dp.include_router(router)
    dp.errors.register(on_error)
    return dp, bot, db, celebrator


# ------------------------------------------------------------------ check
async def run_check(config: Config) -> int:
    ok = True

    def line(good: bool, text: str):
        nonlocal ok
        ok &= good
        print(("✅ " if good else "❌ ") + text)

    line(sys.version_info >= (3, 10), f"Python {sys.version.split()[0]} (3.10+ kerak, tavsiya: 3.12)")
    errors = validate_config(config)
    line(not errors, "BOT_TOKEN o‘rnatilgan, formati to‘g‘ri" if not errors else errors[0])
    try:
        db = Database(config.db_path)
        seed(db)
        n = db.one("SELECT COUNT(*) c FROM questions")["c"]
        users = db.one("SELECT COUNT(*) c FROM users")["c"]
        line(True, f"Baza: {config.db_path} (yozish mumkin) · savollar: {n} · foydalanuvchilar: {users}")
        db.close()
    except Exception as e:  # noqa: BLE001
        line(False, f"Baza ochilmadi ({config.db_path}): {e}")
    try:
        line(True, f"Salyut GIF: {len(ensure_fireworks())} ta")
    except Exception as e:  # noqa: BLE001
        line(False, f"Salyut GIF yaratilmadi: {e} (bot matnli effekt bilan ishlaydi)")
    if not errors:
        bot = Bot(config.bot_token)
        try:
            me = await asyncio.wait_for(bot.get_me(), timeout=15)
            line(True, f"Telegram bilan aloqa bor: @{me.username} ({me.first_name})")
        except (TelegramAPIError, asyncio.TimeoutError, OSError) as e:
            name = type(e).__name__
            hint = "token bekor qilingan/noto‘g‘ri bo‘lishi mumkin" if "Unauthorized" in name else "tarmoqni tekshiring"
            line(False, f"Telegram bilan aloqa yo‘q: {name} — {hint}")
        finally:
            await bot.session.close()
    print("\nNatija:", "hammasi joyida ✅" if ok else "muammolar bor ❌")
    return 0 if ok else 1


# ------------------------------------------------------------------ main
EXIT_CONFIG_ERROR = 2   # BOT_TOKEN yo'q / formati noto'g'ri (systemd qayta urinmaydi)
EXIT_BAD_TOKEN = 3      # Telegram tokenni rad etdi (bekor qilingan yoki xato)


async def wait_for_telegram(bot: Bot) -> User:
    """Telegram'ga ulanguncha kutadi (masalan, server endi yonganda tarmoq hali tayyor bo'lmasa)."""
    delay = 5
    while True:
        try:
            return await bot.get_me()
        except TelegramUnauthorizedError:
            log.critical("Telegram BOT_TOKEN ni rad etdi (Unauthorized). Token bekor qilingan yoki noto‘g‘ri — "
                         "@BotFather dan yangi token olib, hosting sozlamalarida BOT_TOKEN ni yangilang.")
            sys.exit(EXIT_BAD_TOKEN)
        except TelegramRetryAfter as e:
            await asyncio.sleep(e.retry_after)
        except (TelegramNetworkError, TelegramServerError, asyncio.TimeoutError, OSError) as e:
            log.warning("Telegram'ga ulanib bo‘lmadi (%s). %d soniyadan keyin qayta urinish...", type(e).__name__, delay)
            await asyncio.sleep(delay)
            delay = min(delay * 2, 300)


async def main() -> None:
    config = Config.from_env()
    setup_logging([config.bot_token])
    errors = validate_config(config)
    if errors:
        for e in errors:
            log.critical(e)
        sys.exit(EXIT_CONFIG_ERROR)
    log.info("NAVIGATOR ishga tushmoqda · Python %s · baza: %s · BOT_TOKEN: o‘rnatilgan",
             sys.version.split()[0], config.db_path)
    dp, bot, db, celebrator = build(config)
    try:
        me = await wait_for_telegram(bot)
        log.info("Telegram'ga ulandi: @%s (%s)", me.username, me.first_name)
        try:
            await celebrator.setup()
            await bot.set_my_commands(BOT_COMMANDS)
        except TelegramAPIError as e:
            log.warning("Buyruqlar menyusi/stickerlar sozlanmadi (%s) — bot baribir ishlaydi", type(e).__name__)
        # Polling davomida tarmoq uzilsa, aiogram o'zi kutib qayta ulanadi.
        await dp.start_polling(bot, allowed_updates=dp.resolve_used_update_types())
    finally:
        log.info("Bot to‘xtatildi")
        db.close()
        await bot.session.close()


def cli() -> None:
    parser = argparse.ArgumentParser(prog="python -m navigator", description="NAVIGATOR Telegram boti")
    parser.add_argument("--check", action="store_true",
                        help="Konfiguratsiya, baza va Telegram aloqasini tekshirish (token ko‘rsatilmaydi)")
    args = parser.parse_args()
    if args.check:
        sys.exit(asyncio.run(run_check(Config.from_env())))
    asyncio.run(main())


if __name__ == "__main__":
    cli()
