"""Fanlar, mavzular va bazani boshlang'ich to'ldirish (seed) / import."""
from __future__ import annotations

import json
import random
from pathlib import Path
from typing import Any

from ..db import Database
from .generators import GENERATORS

LEVELS = {1: "🟢 Oson", 2: "🟡 O‘rta", 3: "🔴 Qiyin"}
LEVEL_ALIASES = {
    "1": 1, "oson": 1, "easy": 1, "boshlang'ich": 1,
    "2": 2, "o'rta": 2, "o‘rta": 2, "orta": 2, "medium": 2,
    "3": 3, "qiyin": 3, "hard": 3,
}

CATALOG: list[dict[str, Any]] = [
    {"name": "Matematika", "emoji": "🧮", "topics": [
        ("Arifmetika", "math.arithmetic"), ("Foizlar", "math.percent"),
        ("Tenglamalar", "math.equation"), ("Geometriya", "math.geometry"),
    ]},
    {"name": "Ingliz tili", "emoji": "🇬🇧", "topics": [
        ("So‘z boyligi", "en.words"), ("Noto‘g‘ri fe’llar", "en.verbs"),
    ]},
    {"name": "Geografiya", "emoji": "🌍", "topics": [("Davlatlar poytaxtlari", "geo.capitals")]},
    {"name": "Tarix", "emoji": "🏛", "topics": [("O‘zbekiston tarixi", None)]},
]

SEED_PER_LEVEL = 60  # cheksiz generatorlar uchun boshlang'ich hajm
QUESTIONS_DIR = Path(__file__).parent / "questions"


def generate_for(db: Database, topic_id: int, level: int, count: int, source: str = "generated",
                 rng: random.Random | None = None) -> int:
    """Mavzu uchun yangi noyob savollar yaratadi. Qo'shilganlar sonini qaytaradi."""
    row = db.one("SELECT subject_id, generator FROM topics WHERE id = ?", (topic_id,))
    if not row or not row["generator"] or row["generator"] not in GENERATORS:
        return 0
    added = 0
    for d in GENERATORS[row["generator"]].produce(level, count, rng):
        if db.insert_question(row["subject_id"], topic_id, level, d.text, d.options, d.correct_index,
                              d.explanation, source):
            added += 1
    return added


def topic_has_generator(db: Database, topic_id: int | None, subject_id: int) -> bool:
    if topic_id:
        row = db.one("SELECT generator FROM topics WHERE id = ?", (topic_id,))
        return bool(row and row["generator"] in GENERATORS and GENERATORS[row["generator"]].infinite)
    rows = db.all("SELECT generator FROM topics WHERE subject_id = ?", (subject_id,))
    return any(r["generator"] in GENERATORS and GENERATORS[r["generator"]].infinite for r in rows)


def count_questions(db: Database, subject_id: int, topic_id: int | None, level: int) -> int:
    if topic_id:
        return db.one("SELECT COUNT(*) c FROM questions WHERE topic_id = ? AND level = ?", (topic_id, level))["c"]
    return db.one("SELECT COUNT(*) c FROM questions WHERE subject_id = ? AND level = ?", (subject_id, level))["c"]


def seed(db: Database) -> dict[str, int]:
    """Idempotent: katalogni yaratadi va savollar bazasini boshlang'ich to'ldiradi."""
    stats: dict[str, int] = {}
    for s_sort, subj in enumerate(CATALOG):
        sid = db.ensure_subject(subj["name"], subj["emoji"], s_sort)
        for t_sort, (tname, gen) in enumerate(subj["topics"]):
            tid = db.ensure_topic(sid, tname, gen, t_sort)
            if not gen:
                continue
            for level in (1, 2, 3):
                have = count_questions(db, sid, tid, level)
                need = SEED_PER_LEVEL - have
                if GENERATORS[gen].infinite and need <= 0:
                    continue
                rng = random.Random(f"seed-{gen}-{level}-{have}")
                stats[f"{subj['name']}/{tname}/{level}"] = generate_for(db, tid, level, max(need, 0), "seed", rng)
    if QUESTIONS_DIR.exists():
        for f in sorted(QUESTIONS_DIR.glob("*.json")):
            added, _ = import_questions(db, json.loads(f.read_text(encoding="utf-8")), source=f"file:{f.name}")
            stats[f.name] = added
    return stats


def import_questions(db: Database, items: list[dict[str, Any]], source: str = "import") -> tuple[int, list[str]]:
    """JSON ro'yxatdan savollar qo'shadi.

    Format::
        [{"subject": "Tarix", "topic": "Amir Temur", "level": "oson",
          "question": "...", "options": ["A", "B", "C", "D"], "correct": 0,
          "explanation": "..."}]

    ``correct`` — indeks (0 dan) yoki to'g'ri variant matni.
    Qaytaradi: (qo'shilganlar soni, xatolar ro'yxati). Dublikatlar o'tkazib yuboriladi.
    """
    if not isinstance(items, list):
        return 0, ["JSON ro‘yxat (array) bo‘lishi kerak"]
    added, errors = 0, []
    for i, it in enumerate(items, 1):
        try:
            subject = str(it["subject"]).strip()
            topic = str(it["topic"]).strip()
            level = LEVEL_ALIASES.get(str(it.get("level", 1)).strip().lower())
            if level is None:
                raise ValueError("level: oson / o‘rta / qiyin (yoki 1/2/3)")
            text = str(it["question"]).strip()
            options = [str(o).strip() for o in it["options"]]
            correct = it["correct"]
            if isinstance(correct, str) and not correct.isdigit():
                correct = options.index(correct.strip())
            correct = int(correct)
            existing = db.one("SELECT emoji FROM subjects WHERE name = ?", (subject,))
            sid = db.ensure_subject(subject, it.get("emoji") or (existing["emoji"] if existing else "📘"), 100)
            tid = db.ensure_topic(sid, topic, None, 100)
            if db.insert_question(sid, tid, level, text, options, correct, str(it.get("explanation", "")), source):
                added += 1
        except (KeyError, ValueError, TypeError) as e:
            errors.append(f"#{i}: {e}")
    return added, errors
