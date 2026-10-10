"""Bot konfiguratsiyasi (.env yoki muhit o'zgaruvchilaridan)."""
from __future__ import annotations

import os
from dataclasses import dataclass, field

try:  # python-dotenv ixtiyoriy
    from dotenv import load_dotenv

    load_dotenv()
except ImportError:  # pragma: no cover
    pass

# Ballar va test hajmi qat'iy belgilangan — server tomonda o'zgarmas qoidalar.
POINTS_PER_CORRECT = 10
POINTS_PER_WRONG = 0
QUESTIONS_PER_TEST = 30
OPTION_LETTERS = "ABCDEF"

# Telegram Bot API "message effect" — 🎉 (faqat shaxsiy chatlarda ishlaydi)
DEFAULT_PARTY_EFFECT_ID = "5046509860389126442"


def _csv(value: str | None) -> list[str]:
    return [v.strip() for v in (value or "").split(",") if v.strip()]


@dataclass
class Config:
    bot_token: str = ""
    db_path: str = "data/navigator.db"
    admin_ids: set[int] = field(default_factory=set)
    sticker_set: str = ""
    sticker_ids: list[str] = field(default_factory=list)
    gif_sources: list[str] = field(default_factory=list)
    effect_id: str | None = DEFAULT_PARTY_EFFECT_ID

    @classmethod
    def from_env(cls) -> "Config":
        effect = os.getenv("CELEBRATION_EFFECT_ID", DEFAULT_PARTY_EFFECT_ID).strip()
        return cls(
            bot_token=os.getenv("BOT_TOKEN", "").strip(),
            db_path=os.getenv("DB_PATH", "data/navigator.db").strip(),
            admin_ids={int(x) for x in _csv(os.getenv("ADMIN_IDS")) if x.lstrip("-").isdigit()},
            sticker_set=os.getenv("CELEBRATION_STICKER_SET", "").strip(),
            sticker_ids=_csv(os.getenv("CELEBRATION_STICKERS")),
            gif_sources=_csv(os.getenv("CELEBRATION_GIFS")),
            effect_id=None if effect in ("", "0") else effect,
        )
