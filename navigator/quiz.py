"""Test va ball tizimining biznes-logikasi (to'liq server tomonda).

Asosiy kafolatlar:
* To'g'ri javob = +10 ball, noto'g'ri = 0 (ayirilmaydi).
* Ball faqat savol BIRINCHI marta tekshirilganda hisoblanadi: ``UPDATE ...
  WHERE chosen_index IS NULL`` atomar tranzaksiyada — takroriy bosish,
  eski tugmalar yoki parallel so'rovlar ballni ikki marta bera olmaydi.
* Oddiy testda aynan 30 ta noyob savol; yetarli bo'lmasa — test boshlanmaydi.
"""
from __future__ import annotations

import json
import random
from dataclasses import dataclass, field

from .config import POINTS_PER_CORRECT, POINTS_PER_WRONG, QUESTIONS_PER_TEST
from .db import Database, now


class QuizError(Exception):
    pass


class NotEnoughQuestions(QuizError):
    def __init__(self, available: int, required: int):
        super().__init__(f"Faqat {available} ta noyob savol bor, {required} ta kerak")
        self.available = available
        self.required = required


class ActiveTestExists(QuizError):
    def __init__(self, test_id: int):
        super().__init__("Faol test mavjud")
        self.test_id = test_id


class TestNotActive(QuizError):
    __test__ = False  # pytest uchun
    pass


@dataclass
class QuestionView:
    test_id: int
    position: int
    total: int
    question_id: int
    text: str
    options: list[str]          # ko'rsatiladigan tartibda
    subject: str
    topic: str
    level: int
    score: int
    correct_count: int
    wrong_count: int
    answered: bool
    kind: str


@dataclass
class AnswerResult:
    already_answered: bool
    is_correct: bool
    points: int
    correct_display_index: int
    chosen_display_index: int
    correct_text: str
    explanation: str
    test_score: int
    correct_count: int
    wrong_count: int
    answered_count: int
    total: int
    user_total_score: int
    is_last: bool
    kind: str


@dataclass
class TestSummary:
    test_id: int
    kind: str
    subject: str | None
    topic: str | None
    level: int | None
    total: int
    correct: int
    wrong: int
    unanswered: int
    score: int
    percent: int
    duration_sec: int
    user_total_score: int
    by_topic: list[tuple[str, int, int]] = field(default_factory=list)       # (mavzu, to'g'ri, jami)
    mistakes: list[dict] = field(default_factory=list)


class QuizService:
    def __init__(self, db: Database, rng: random.Random | None = None, test_size: int = QUESTIONS_PER_TEST):
        self.db = db
        self.rng = rng or random.SystemRandom()
        self.test_size = test_size

    # ------------------------------------------------------------ users
    def ensure_user(self, user_id: int, first_name: str | None = None, username: str | None = None) -> None:
        t = now()
        self.db.execute(
            "INSERT INTO users (id, first_name, username, created_at, updated_at) VALUES (?, ?, ?, ?, ?) "
            "ON CONFLICT(id) DO UPDATE SET first_name = excluded.first_name, username = excluded.username, "
            "updated_at = excluded.updated_at",
            (user_id, first_name, username, t, t),
        )

    def profile(self, user_id: int) -> dict:
        u = self.db.one("SELECT * FROM users WHERE id = ?", (user_id,))
        if not u:
            return {}
        stats = self.db.one(
            "SELECT COUNT(*) n, MAX(correct_count) best, COALESCE(SUM(total_questions),0) q "
            "FROM tests WHERE user_id = ? AND status = 'finished' AND kind = 'normal'", (user_id,))
        rank = self.db.one("SELECT COUNT(*) + 1 r FROM users WHERE total_score > ?", (u["total_score"],))["r"]
        users = self.db.one("SELECT COUNT(*) c FROM users")["c"]
        return {
            "name": u["first_name"] or u["username"] or str(user_id),
            "total_score": u["total_score"],
            "total_correct": u["total_correct"],
            "total_answered": u["total_answered"],
            "tests_finished": stats["n"],
            "best_correct": stats["best"] or 0,
            "open_mistakes": self.count_mistakes(user_id),
            "rank": rank,
            "users": users,
        }

    def history(self, user_id: int, limit: int = 10) -> list[dict]:
        rows = self.db.all(
            "SELECT t.*, s.name subject, tp.name topic FROM tests t "
            "LEFT JOIN subjects s ON s.id = t.subject_id LEFT JOIN topics tp ON tp.id = t.topic_id "
            "WHERE t.user_id = ? AND t.status = 'finished' ORDER BY t.id DESC LIMIT ?", (user_id, limit))
        return [dict(r) for r in rows]

    # ------------------------------------------------------------ catalog
    def subjects(self) -> list[dict]:
        return [dict(r) for r in self.db.all(
            "SELECT s.*, (SELECT COUNT(*) FROM questions q WHERE q.subject_id = s.id) qcount "
            "FROM subjects s ORDER BY s.sort, s.name")]

    def topics(self, subject_id: int) -> list[dict]:
        return [dict(r) for r in self.db.all(
            "SELECT t.*, (SELECT COUNT(*) FROM questions q WHERE q.topic_id = t.id) qcount "
            "FROM topics t WHERE t.subject_id = ? ORDER BY t.sort, t.name", (subject_id,))]

    def subject(self, subject_id: int) -> dict | None:
        r = self.db.one("SELECT * FROM subjects WHERE id = ?", (subject_id,))
        return dict(r) if r else None

    def topic(self, topic_id: int) -> dict | None:
        r = self.db.one("SELECT * FROM topics WHERE id = ?", (topic_id,))
        return dict(r) if r else None

    # ------------------------------------------------------------ tests
    def active_test(self, user_id: int) -> dict | None:
        r = self.db.one("SELECT * FROM tests WHERE user_id = ? AND status = 'active'", (user_id,))
        return dict(r) if r else None

    def abandon_test(self, user_id: int, test_id: int) -> bool:
        cur = self.db.execute(
            "UPDATE tests SET status = 'abandoned', finished_at = ? WHERE id = ? AND user_id = ? AND status = 'active'",
            (now(), test_id, user_id))
        return cur.rowcount > 0

    def _pick_questions(self, user_id: int, subject_id: int, topic_id: int | None, level: int) -> list[int]:
        where = "q.topic_id = ?" if topic_id else "q.subject_id = ?"
        rows = self.db.all(
            f"SELECT q.id, (SELECT MAX(t.started_at) FROM test_questions tq JOIN tests t ON t.id = tq.test_id "
            f"              WHERE tq.question_id = q.id AND t.user_id = ?) AS last_seen "
            f"FROM questions q WHERE {where} AND q.level = ?",
            (user_id, topic_id or subject_id, level))
        if len(rows) < self.test_size:
            # Savollarni takrorlab 30 taga yetkazmaymiz.
            raise NotEnoughQuestions(len(rows), self.test_size)
        unseen = [r["id"] for r in rows if r["last_seen"] is None]
        self.rng.shuffle(unseen)
        picked = unseen[: self.test_size]
        if len(picked) < self.test_size:
            # Avval eng uzoq vaqt oldin ko'rilganlari (imkon qadar qayta bermaslik).
            seen = [r for r in rows if r["last_seen"] is not None]
            self.rng.shuffle(seen)
            seen.sort(key=lambda r: r["last_seen"])
            picked += [r["id"] for r in seen[: self.test_size - len(picked)]]
        self.rng.shuffle(picked)
        assert len(set(picked)) == self.test_size
        return picked

    def _create_test(self, user_id: int, kind: str, subject_id: int | None, topic_id: int | None,
                     level: int | None, question_ids: list[int]) -> int:
        with self.db.tx() as c:
            active = c.execute("SELECT id FROM tests WHERE user_id = ? AND status = 'active'", (user_id,)).fetchone()
            if active:
                raise ActiveTestExists(active["id"])
            cur = c.execute(
                "INSERT INTO tests (user_id, kind, subject_id, topic_id, level, total_questions, started_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                (user_id, kind, subject_id, topic_id, level, len(question_ids), now()))
            test_id = cur.lastrowid
            for pos, qid in enumerate(question_ids, 1):
                n_opts = len(json.loads(c.execute("SELECT options FROM questions WHERE id = ?", (qid,))
                                        .fetchone()["options"]))
                order = list(range(n_opts))
                self.rng.shuffle(order)
                c.execute("INSERT INTO test_questions (test_id, position, question_id, option_order) "
                          "VALUES (?, ?, ?, ?)", (test_id, pos, qid, json.dumps(order)))
        return test_id

    def start_test(self, user_id: int, subject_id: int, topic_id: int | None, level: int) -> int:
        active = self.active_test(user_id)
        if active:
            raise ActiveTestExists(active["id"])
        ids = self._pick_questions(user_id, subject_id, topic_id, level)
        return self._create_test(user_id, "normal", subject_id, topic_id, level, ids)

    # --- Xatolarim ustida ishlash
    def count_mistakes(self, user_id: int) -> int:
        return self.db.one("SELECT COUNT(*) c FROM mistakes WHERE user_id = ? AND resolved_at IS NULL",
                           (user_id,))["c"]

    def mistakes_overview(self, user_id: int) -> list[dict]:
        return [dict(r) for r in self.db.all(
            "SELECT s.name subject, tp.name topic, COUNT(*) n FROM mistakes m "
            "JOIN questions q ON q.id = m.question_id JOIN subjects s ON s.id = q.subject_id "
            "JOIN topics tp ON tp.id = q.topic_id WHERE m.user_id = ? AND m.resolved_at IS NULL "
            "GROUP BY s.name, tp.name ORDER BY n DESC", (user_id,))]

    def start_mistakes_test(self, user_id: int, limit: int | None = None) -> int:
        active = self.active_test(user_id)
        if active:
            raise ActiveTestExists(active["id"])
        rows = self.db.all(
            "SELECT question_id FROM mistakes WHERE user_id = ? AND resolved_at IS NULL "
            "ORDER BY wrong_count DESC, last_wrong_at ASC LIMIT ?", (user_id, limit or self.test_size))
        ids = [r["question_id"] for r in rows]
        if not ids:
            raise NotEnoughQuestions(0, 1)
        self.rng.shuffle(ids)
        return self._create_test(user_id, "mistakes", None, None, None, ids)

    # --- savollar
    def next_position(self, test_id: int) -> int | None:
        r = self.db.one("SELECT MIN(position) p FROM test_questions WHERE test_id = ? AND chosen_index IS NULL",
                        (test_id,))
        return r["p"] if r and r["p"] is not None else None

    def get_question(self, user_id: int, test_id: int, position: int) -> QuestionView:
        r = self.db.one(
            "SELECT t.user_id, t.total_questions, t.score, t.correct_count, t.wrong_count, t.kind, t.status, "
            "       tq.option_order, tq.chosen_index, q.id qid, q.text, q.options, q.level, "
            "       s.name subject, tp.name topic "
            "FROM tests t JOIN test_questions tq ON tq.test_id = t.id "
            "JOIN questions q ON q.id = tq.question_id JOIN subjects s ON s.id = q.subject_id "
            "JOIN topics tp ON tp.id = q.topic_id WHERE t.id = ? AND tq.position = ?", (test_id, position))
        if not r or r["user_id"] != user_id:
            raise QuizError("Savol topilmadi")
        opts = json.loads(r["options"])
        order = json.loads(r["option_order"])
        return QuestionView(
            test_id=test_id, position=position, total=r["total_questions"], question_id=r["qid"],
            text=r["text"], options=[opts[i] for i in order], subject=r["subject"], topic=r["topic"],
            level=r["level"], score=r["score"], correct_count=r["correct_count"], wrong_count=r["wrong_count"],
            answered=r["chosen_index"] is not None, kind=r["kind"])

    def answer(self, user_id: int, test_id: int, position: int, display_index: int) -> AnswerResult:
        """Javobni tekshiradi va ballni ATOMAR hisoblaydi (faqat birinchi marta)."""
        with self.db.tx() as c:
            t = c.execute("SELECT * FROM tests WHERE id = ?", (test_id,)).fetchone()
            if not t or t["user_id"] != user_id:
                raise QuizError("Test topilmadi")
            row = c.execute(
                "SELECT tq.*, q.options, q.correct_index, q.explanation FROM test_questions tq "
                "JOIN questions q ON q.id = tq.question_id WHERE tq.test_id = ? AND tq.position = ?",
                (test_id, position)).fetchone()
            if not row:
                raise QuizError("Savol topilmadi")
            opts = json.loads(row["options"])
            order = json.loads(row["option_order"])
            correct_display = order.index(row["correct_index"])

            def result(already: bool, is_correct: bool, points: int, chosen_display: int, tt) -> AnswerResult:
                answered = c.execute("SELECT COUNT(*) FROM test_questions WHERE test_id = ? AND chosen_index "
                                     "IS NOT NULL", (test_id,)).fetchone()[0]
                return AnswerResult(
                    already_answered=already, is_correct=is_correct, points=points,
                    correct_display_index=correct_display, chosen_display_index=chosen_display,
                    correct_text=opts[row["correct_index"]], explanation=row["explanation"] or "",
                    test_score=tt["score"], correct_count=tt["correct_count"], wrong_count=tt["wrong_count"],
                    answered_count=answered, total=tt["total_questions"],
                    user_total_score=c.execute("SELECT total_score FROM users WHERE id = ?",
                                               (user_id,)).fetchone()[0],
                    is_last=answered >= tt["total_questions"], kind=tt["kind"])

            if row["chosen_index"] is not None:
                # Takroriy javob — ball BERILMAYDI.
                return result(True, bool(row["is_correct"]), 0, order.index(row["chosen_index"]), t)
            if t["status"] != "active":
                raise TestNotActive("Bu test yakunlangan")
            if not 0 <= display_index < len(order):
                raise QuizError("Noto‘g‘ri variant")

            chosen = order[display_index]
            is_correct = chosen == row["correct_index"]
            points = POINTS_PER_CORRECT if is_correct else POINTS_PER_WRONG
            ts = now()
            cur = c.execute(
                "UPDATE test_questions SET chosen_index = ?, is_correct = ?, points = ?, answered_at = ? "
                "WHERE test_id = ? AND position = ? AND chosen_index IS NULL",
                (chosen, int(is_correct), points, ts, test_id, position))
            if cur.rowcount == 0:  # parallel so'rov ulgurib qoldi
                return result(True, is_correct, 0, display_index, t)
            c.execute("UPDATE tests SET score = score + ?, correct_count = correct_count + ?, "
                      "wrong_count = wrong_count + ? WHERE id = ?",
                      (points, int(is_correct), int(not is_correct), test_id))
            c.execute("UPDATE users SET total_score = total_score + ?, total_correct = total_correct + ?, "
                      "total_answered = total_answered + 1, updated_at = ? WHERE id = ?",
                      (points, int(is_correct), ts, user_id))
            if is_correct:
                c.execute("UPDATE mistakes SET resolved_at = ? WHERE user_id = ? AND question_id = ? "
                          "AND resolved_at IS NULL", (ts, user_id, row["question_id"]))
            else:
                c.execute(
                    "INSERT INTO mistakes (user_id, question_id, wrong_count, last_wrong_at) VALUES (?, ?, 1, ?) "
                    "ON CONFLICT(user_id, question_id) DO UPDATE SET wrong_count = wrong_count + 1, "
                    "last_wrong_at = excluded.last_wrong_at, resolved_at = NULL",
                    (user_id, row["question_id"], ts))
            t2 = c.execute("SELECT * FROM tests WHERE id = ?", (test_id,)).fetchone()
            return result(False, is_correct, points, display_index, t2)

    def finish_test(self, user_id: int, test_id: int) -> TestSummary:
        """Testni yakunlaydi (idempotent) va to'liq natijani qaytaradi."""
        self.db.execute(
            "UPDATE tests SET status = 'finished', finished_at = ? WHERE id = ? AND user_id = ? AND status = 'active'",
            (now(), test_id, user_id))
        return self.summary(user_id, test_id)

    def summary(self, user_id: int, test_id: int) -> TestSummary:
        t = self.db.one(
            "SELECT t.*, s.name subject, tp.name topic FROM tests t LEFT JOIN subjects s ON s.id = t.subject_id "
            "LEFT JOIN topics tp ON tp.id = t.topic_id WHERE t.id = ? AND t.user_id = ?", (test_id, user_id))
        if not t:
            raise QuizError("Test topilmadi")
        rows = self.db.all(
            "SELECT tq.position, tq.option_order, tq.chosen_index, tq.is_correct, q.text, q.options, "
            "       q.correct_index, q.explanation, tp.name topic "
            "FROM test_questions tq JOIN questions q ON q.id = tq.question_id JOIN topics tp ON tp.id = q.topic_id "
            "WHERE tq.test_id = ? ORDER BY tq.position", (test_id,))
        by_topic: dict[str, list[int]] = {}
        mistakes = []
        for r in rows:
            stat = by_topic.setdefault(r["topic"], [0, 0])
            stat[1] += 1
            if r["is_correct"]:
                stat[0] += 1
            elif r["chosen_index"] is not None:
                opts = json.loads(r["options"])
                mistakes.append({"position": r["position"], "text": r["text"], "chosen": opts[r["chosen_index"]],
                                 "correct": opts[r["correct_index"]], "explanation": r["explanation"] or "",
                                 "topic": r["topic"]})
        total = t["total_questions"]
        end = t["finished_at"] or now()
        user_total = self.db.one("SELECT total_score FROM users WHERE id = ?", (user_id,))["total_score"]
        return TestSummary(
            test_id=test_id, kind=t["kind"], subject=t["subject"], topic=t["topic"], level=t["level"],
            total=total, correct=t["correct_count"], wrong=t["wrong_count"],
            unanswered=total - t["correct_count"] - t["wrong_count"], score=t["score"],
            percent=round(100 * t["correct_count"] / total) if total else 0,
            duration_sec=max(0, end - t["started_at"]), user_total_score=user_total,
            by_topic=[(k, v[0], v[1]) for k, v in by_topic.items()], mistakes=mistakes)
