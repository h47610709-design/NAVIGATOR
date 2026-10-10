"""SQLite ma'lumotlar bazasi: sxema, ulanish va umumiy yordamchilar.

Barcha holat (ballar, testlar, javoblar, xatolar) bazada saqlanadi, shuning
uchun bot qayta ishga tushganda hech narsa yo'qolmaydi.
"""
from __future__ import annotations

import hashlib
import json
import sqlite3
import threading
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator, Sequence

SCHEMA = """
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS users (
    id              INTEGER PRIMARY KEY,          -- Telegram user id
    first_name      TEXT,
    username        TEXT,
    total_score     INTEGER NOT NULL DEFAULT 0,   -- umumiy ball (profil)
    total_correct   INTEGER NOT NULL DEFAULT 0,
    total_answered  INTEGER NOT NULL DEFAULT 0,
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS subjects (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    name    TEXT NOT NULL UNIQUE,
    emoji   TEXT NOT NULL DEFAULT '📘',
    sort    INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS topics (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    subject_id  INTEGER NOT NULL REFERENCES subjects(id) ON DELETE CASCADE,
    name        TEXT NOT NULL,
    generator   TEXT,                             -- avtomatik savol generatori kaliti
    sort        INTEGER NOT NULL DEFAULT 0,
    UNIQUE (subject_id, name)
);

CREATE TABLE IF NOT EXISTS questions (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,  -- yagona savol ID
    subject_id     INTEGER NOT NULL REFERENCES subjects(id),
    topic_id       INTEGER NOT NULL REFERENCES topics(id),
    level          INTEGER NOT NULL CHECK (level IN (1, 2, 3)),
    text           TEXT NOT NULL,
    options        TEXT NOT NULL,                      -- JSON ro'yxat
    correct_index  INTEGER NOT NULL,
    explanation    TEXT NOT NULL DEFAULT '',
    content_hash   TEXT NOT NULL UNIQUE,               -- dublikatlarga qarshi
    source         TEXT NOT NULL DEFAULT 'seed',
    created_at     INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_questions_topic_level ON questions (topic_id, level);
CREATE INDEX IF NOT EXISTS idx_questions_subject_level ON questions (subject_id, level);

CREATE TABLE IF NOT EXISTS tests (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id          INTEGER NOT NULL REFERENCES users(id),
    kind             TEXT NOT NULL DEFAULT 'normal' CHECK (kind IN ('normal', 'mistakes')),
    subject_id       INTEGER REFERENCES subjects(id),
    topic_id         INTEGER REFERENCES topics(id),   -- NULL = aralash
    level            INTEGER,
    total_questions  INTEGER NOT NULL,
    status           TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'finished', 'abandoned')),
    score            INTEGER NOT NULL DEFAULT 0,
    correct_count    INTEGER NOT NULL DEFAULT 0,
    wrong_count      INTEGER NOT NULL DEFAULT 0,
    started_at       INTEGER NOT NULL,
    finished_at      INTEGER
);
CREATE INDEX IF NOT EXISTS idx_tests_user ON tests (user_id, started_at);
-- Har bir foydalanuvchida bir vaqtda faqat bitta faol test
CREATE UNIQUE INDEX IF NOT EXISTS idx_tests_one_active ON tests (user_id) WHERE status = 'active';

CREATE TABLE IF NOT EXISTS test_questions (
    test_id       INTEGER NOT NULL REFERENCES tests(id) ON DELETE CASCADE,
    position      INTEGER NOT NULL,                -- 1..N
    question_id   INTEGER NOT NULL REFERENCES questions(id),
    option_order  TEXT NOT NULL,                   -- JSON: ko'rsatilgan tartib -> asl indeks
    chosen_index  INTEGER,                         -- asl indeks; NULL = javob berilmagan
    is_correct    INTEGER,
    points        INTEGER NOT NULL DEFAULT 0,
    answered_at   INTEGER,
    PRIMARY KEY (test_id, position),
    UNIQUE (test_id, question_id)                  -- bir test ichida savol takrorlanmaydi
);
CREATE INDEX IF NOT EXISTS idx_tq_question ON test_questions (question_id);

CREATE TABLE IF NOT EXISTS mistakes (
    user_id        INTEGER NOT NULL REFERENCES users(id),
    question_id    INTEGER NOT NULL REFERENCES questions(id),
    wrong_count    INTEGER NOT NULL DEFAULT 1,
    last_wrong_at  INTEGER NOT NULL,
    resolved_at    INTEGER,                        -- NULL = hali ishlanmagan
    PRIMARY KEY (user_id, question_id)
);

CREATE TABLE IF NOT EXISTS kv (
    key    TEXT PRIMARY KEY,
    value  TEXT NOT NULL
);
"""


def now() -> int:
    return int(time.time())


def content_hash(subject: str, topic: str, level: int, text: str) -> str:
    norm = " ".join(text.lower().split())
    raw = f"{subject.strip().lower()}|{topic.strip().lower()}|{level}|{norm}"
    return hashlib.sha1(raw.encode("utf-8")).hexdigest()


class Database:
    """Yupqa sqlite3 o'rami. Yozuv tranzaksiyalari qulf bilan ketma-ket bajariladi."""

    def __init__(self, path: str | Path):
        self.path = str(path)
        if self.path != ":memory:":
            Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        self.conn = sqlite3.connect(self.path, check_same_thread=False, isolation_level=None)
        self.conn.row_factory = sqlite3.Row
        self.conn.execute("PRAGMA journal_mode = WAL")
        self.conn.execute("PRAGMA synchronous = NORMAL")
        self.conn.execute("PRAGMA foreign_keys = ON")
        self.conn.executescript(SCHEMA)
        self._lock = threading.RLock()

    def close(self) -> None:
        self.conn.close()

    @contextmanager
    def tx(self) -> Iterator[sqlite3.Connection]:
        """Atomar tranzaksiya (BEGIN IMMEDIATE) — ballarni hisoblash shu ichida."""
        with self._lock:
            self.conn.execute("BEGIN IMMEDIATE")
            try:
                yield self.conn
            except BaseException:
                self.conn.execute("ROLLBACK")
                raise
            else:
                self.conn.execute("COMMIT")

    def one(self, sql: str, params: Sequence = ()) -> sqlite3.Row | None:
        with self._lock:
            return self.conn.execute(sql, params).fetchone()

    def all(self, sql: str, params: Sequence = ()) -> list[sqlite3.Row]:
        with self._lock:
            return self.conn.execute(sql, params).fetchall()

    def execute(self, sql: str, params: Sequence = ()) -> sqlite3.Cursor:
        with self._lock:
            return self.conn.execute(sql, params)

    # --- kv (masalan, yuklangan GIF file_id keshi) ---
    def kv_get(self, key: str) -> str | None:
        row = self.one("SELECT value FROM kv WHERE key = ?", (key,))
        return row["value"] if row else None

    def kv_set(self, key: str, value: str) -> None:
        self.execute(
            "INSERT INTO kv (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            (key, value),
        )

    def kv_delete(self, key: str) -> None:
        self.execute("DELETE FROM kv WHERE key = ?", (key,))

    # --- katalog ---
    def ensure_subject(self, name: str, emoji: str = "📘", sort: int = 0) -> int:
        self.execute(
            "INSERT INTO subjects (name, emoji, sort) VALUES (?, ?, ?) "
            "ON CONFLICT(name) DO UPDATE SET emoji = excluded.emoji",
            (name, emoji, sort),
        )
        return self.one("SELECT id FROM subjects WHERE name = ?", (name,))["id"]

    def ensure_topic(self, subject_id: int, name: str, generator: str | None = None, sort: int = 0) -> int:
        self.execute(
            "INSERT INTO topics (subject_id, name, generator, sort) VALUES (?, ?, ?, ?) "
            "ON CONFLICT(subject_id, name) DO UPDATE SET generator = COALESCE(excluded.generator, topics.generator)",
            (subject_id, name, generator, sort),
        )
        return self.one("SELECT id FROM topics WHERE subject_id = ? AND name = ?", (subject_id, name))["id"]

    def insert_question(
        self,
        subject_id: int,
        topic_id: int,
        level: int,
        text: str,
        options: list[str],
        correct_index: int,
        explanation: str = "",
        source: str = "seed",
    ) -> int | None:
        """Savol qo'shadi. Dublikat bo'lsa None qaytaradi (savollar takrorlanmaydi)."""
        if level not in (1, 2, 3):
            raise ValueError("level 1, 2 yoki 3 bo'lishi kerak")
        if len(options) < 2 or len(set(o.strip().lower() for o in options)) != len(options):
            raise ValueError("Kamida 2 ta, o'zaro farqli javob varianti kerak")
        if not 0 <= correct_index < len(options):
            raise ValueError("To'g'ri javob indeksi noto'g'ri")
        names = self.one(
            "SELECT s.name AS s, t.name AS t FROM topics t JOIN subjects s ON s.id = t.subject_id "
            "WHERE t.id = ? AND s.id = ?",
            (topic_id, subject_id),
        )
        if names is None:
            raise ValueError("Fan yoki mavzu topilmadi")
        h = content_hash(names["s"], names["t"], level, text)
        cur = self.execute(
            "INSERT OR IGNORE INTO questions (subject_id, topic_id, level, text, options, correct_index, "
            "explanation, content_hash, source, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (subject_id, topic_id, level, text, json.dumps(options, ensure_ascii=False), correct_index,
             explanation, h, source, now()),
        )
        return cur.lastrowid if cur.rowcount else None
