import json
import random

import pytest

from navigator.content.catalog import count_questions, generate_for, import_questions, seed
from navigator.content.generators import GENERATORS
from navigator.db import Database
from navigator.quiz import ActiveTestExists, NotEnoughQuestions, QuizService, TestNotActive
from navigator.texts import encouragement

UID = 1001


@pytest.fixture
def db(tmp_path):
    d = Database(tmp_path / "t.db")
    seed(d)
    yield d
    d.close()


@pytest.fixture
def quiz(db):
    q = QuizService(db, rng=random.Random(42))
    q.ensure_user(UID, "Ali", "ali")
    return q


def ids(db, subject, topic):
    s = db.one("SELECT id FROM subjects WHERE name = ?", (subject,))["id"]
    t = db.one("SELECT id FROM topics WHERE subject_id = ? AND name = ?", (s, topic))["id"]
    return s, t


def correct_display(db, test_id, pos):
    r = db.one("SELECT tq.option_order, q.correct_index FROM test_questions tq JOIN questions q "
               "ON q.id = tq.question_id WHERE tq.test_id = ? AND tq.position = ?", (test_id, pos))
    return json.loads(r["option_order"]).index(r["correct_index"])


def test_test_has_exactly_30_unique_questions_matching_filters(db, quiz):
    s, t = ids(db, "Matematika", "Foizlar")
    test_id = quiz.start_test(UID, s, t, 2)
    rows = db.all("SELECT q.* FROM test_questions tq JOIN questions q ON q.id = tq.question_id WHERE tq.test_id = ?",
                  (test_id,))
    assert len(rows) == 30
    assert len({r["id"] for r in rows}) == 30
    assert len({r["content_hash"] for r in rows}) == 30
    assert all(r["topic_id"] == t and r["level"] == 2 and r["subject_id"] == s for r in rows)


def test_question_has_full_metadata(db):
    r = db.one("SELECT * FROM questions LIMIT 1")
    for col in ("id", "subject_id", "topic_id", "level", "text", "options", "correct_index", "explanation"):
        assert r[col] is not None
    assert len(json.loads(r["options"])) == 4


def test_scoring_plus10_zero_and_no_double_points(db, quiz):
    s, t = ids(db, "Matematika", "Arifmetika")
    test_id = quiz.start_test(UID, s, t, 1)
    c = correct_display(db, test_id, 1)
    r1 = quiz.answer(UID, test_id, 1, c)
    assert r1.is_correct and r1.points == 10 and r1.test_score == 10 and r1.user_total_score == 10
    # Qayta bosish — ball qayta berilmaydi
    again = quiz.answer(UID, test_id, 1, c)
    assert again.already_answered and again.points == 0 and again.test_score == 10
    # Boshqa variantga o'zgartirib ham bo'lmaydi
    again2 = quiz.answer(UID, test_id, 1, (c + 1) % 4)
    assert again2.already_answered and again2.is_correct  # birinchi javob saqlanadi
    wrong = (correct_display(db, test_id, 2) + 1) % 4
    r2 = quiz.answer(UID, test_id, 2, wrong)
    assert not r2.is_correct and r2.points == 0 and r2.test_score == 10  # ayirilmaydi
    assert db.one("SELECT total_score FROM users WHERE id = ?", (UID,))["total_score"] == 10


def test_wrong_answer_goes_to_mistakes_and_mistakes_mode_resolves(db, quiz):
    s, t = ids(db, "Geografiya", "Davlatlar poytaxtlari")
    test_id = quiz.start_test(UID, s, t, 1)
    for pos in range(1, 31):
        c = correct_display(db, test_id, pos)
        quiz.answer(UID, test_id, pos, c if pos > 3 else (c + 1) % 4)
    summary = quiz.finish_test(UID, test_id)
    assert (summary.total, summary.correct, summary.wrong, summary.score) == (30, 27, 3, 270)
    assert summary.percent == 90 and len(summary.mistakes) == 3
    assert summary.by_topic == [("Davlatlar poytaxtlari", 27, 30)]
    assert quiz.count_mistakes(UID) == 3

    m_id = quiz.start_mistakes_test(UID)
    assert db.one("SELECT total_questions FROM tests WHERE id = ?", (m_id,))[0] == 3
    for pos in range(1, 4):
        quiz.answer(UID, m_id, pos, correct_display(db, m_id, pos))
    quiz.finish_test(UID, m_id)
    assert quiz.count_mistakes(UID) == 0
    assert quiz.profile(UID)["total_score"] == 300


def test_only_one_active_test_and_finished_test_is_locked(db, quiz):
    s, t = ids(db, "Matematika", "Arifmetika")
    test_id = quiz.start_test(UID, s, t, 1)
    with pytest.raises(ActiveTestExists):
        quiz.start_test(UID, s, t, 2)
    quiz.finish_test(UID, test_id)
    with pytest.raises(TestNotActive):
        quiz.answer(UID, test_id, 5, 0)


def test_not_enough_unique_questions_never_pads_with_duplicates(db, quiz):
    s, t = ids(db, "Tarix", "O‘zbekiston tarixi")
    with pytest.raises(NotEnoughQuestions) as e:
        quiz.start_test(UID, s, t, 1)
    assert e.value.available == 12 and e.value.required == 30
    assert db.one("SELECT COUNT(*) FROM tests")[0] == 0


def test_generate_more_questions_unblocks_test(db, quiz):
    s, t = ids(db, "Matematika", "Geometriya")
    db.execute("DELETE FROM questions WHERE topic_id = ? AND level = 3 AND id NOT IN "
               "(SELECT id FROM questions WHERE topic_id = ? AND level = 3 LIMIT 10)", (t, t))
    with pytest.raises(NotEnoughQuestions):
        quiz.start_test(UID, s, t, 3)
    assert generate_for(db, t, 3, 60) > 20
    assert count_questions(db, s, t, 3) >= 30
    quiz.start_test(UID, s, t, 3)


def test_avoids_previously_seen_questions(db, quiz):
    s, t = ids(db, "Ingliz tili", "So‘z boyligi")  # 40 ta savol
    first = quiz.start_test(UID, s, t, 1)
    quiz.finish_test(UID, first)
    second = quiz.start_test(UID, s, t, 1)
    a = {r[0] for r in db.all("SELECT question_id FROM test_questions WHERE test_id = ?", (first,))}
    b = {r[0] for r in db.all("SELECT question_id FROM test_questions WHERE test_id = ?", (second,))}
    assert len(b) == 30
    assert len(b - a) == 10  # barcha 10 ta ko'rilmagan savol albatta kiritilgan
    assert len(a & b) == 20  # qolgani — eng uzoq ko'rilganlar (takrorsiz)


def test_state_survives_restart(tmp_path):
    path = tmp_path / "persist.db"
    db = Database(path)
    seed(db)
    q = QuizService(db, rng=random.Random(1))
    q.ensure_user(UID, "Ali", None)
    s, t = ids(db, "Matematika", "Tenglamalar")
    test_id = q.start_test(UID, s, t, 1)
    q.answer(UID, test_id, 1, correct_display(db, test_id, 1))
    db.close()

    db2 = Database(path)
    seed(db2)  # qayta ishga tushganda seed idempotent
    q2 = QuizService(db2)
    assert q2.profile(UID)["total_score"] == 10
    assert q2.active_test(UID)["id"] == test_id
    assert q2.next_position(test_id) == 2
    assert q2.answer(UID, test_id, 1, 0).already_answered
    db2.close()


def test_history_is_per_user(db, quiz):
    quiz.ensure_user(2002, "Vali", None)
    s, t = ids(db, "Matematika", "Arifmetika")
    tid = quiz.start_test(UID, s, t, 1)
    quiz.finish_test(UID, tid)
    assert len(quiz.history(UID)) == 1
    assert quiz.history(2002) == []


def test_encouragement_thresholds():
    assert encouragement(30, 30).startswith("🏆") and encouragement(27, 30).startswith("🏆")
    assert encouragement(26, 30).startswith("🎉") and encouragement(21, 30).startswith("🎉")
    assert encouragement(20, 30).startswith("💪") and encouragement(15, 30).startswith("💪")
    assert encouragement(14, 30).startswith("📚") and encouragement(0, 30).startswith("📚")


def test_import_validates_and_dedupes(db):
    item = {"subject": "Fizika", "topic": "Mexanika", "level": "o‘rta", "question": "Tezlik birligi?",
            "options": ["m/s", "kg", "N", "J"], "correct": "m/s", "explanation": "SI da tezlik m/s"}
    assert import_questions(db, [item, item]) == (1, [])
    added, errors = import_questions(db, [{**item, "question": "X", "options": ["a", "a"]}])
    assert added == 0 and errors


@pytest.mark.parametrize("key", list(GENERATORS))
def test_generators_produce_valid_questions(key):
    g = GENERATORS[key]
    for level in (1, 2, 3):
        drafts = list(g.produce(level, 50, random.Random(level)))
        assert len(drafts) >= 30 or not g.infinite
        for d in drafts:
            assert len(d.options) == 4 and len({o.lower() for o in d.options}) == 4
            assert 0 <= d.correct_index < 4 and d.explanation
