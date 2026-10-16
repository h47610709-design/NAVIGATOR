import io
import logging
import os
import subprocess
import sys
from pathlib import Path

from navigator.__main__ import RedactSecrets, validate_config
from navigator.config import Config

ROOT = Path(__file__).resolve().parents[1]
FAKE = "123456789:AAFakeTokenForTestsOnly_abcdefghijklmn"


def test_validate_config():
    assert validate_config(Config(bot_token=""))[0].startswith("BOT_TOKEN topilmadi")
    assert "formati" in validate_config(Config(bot_token="abc"))[0]
    assert validate_config(Config(bot_token=FAKE)) == []
    # xato matnida token hech qachon bo'lmaydi
    assert all(FAKE not in e for e in validate_config(Config(bot_token=FAKE + " x")))


def test_config_strips_quotes(monkeypatch):
    monkeypatch.setenv("BOT_TOKEN", f' "{FAKE}" ')
    assert Config.from_env().bot_token == FAKE


def test_token_is_redacted_in_logs():
    buf = io.StringIO()
    h = logging.StreamHandler(buf)
    h.addFilter(RedactSecrets([FAKE]))
    lg = logging.getLogger("redact-test")
    lg.addHandler(h)
    lg.propagate = False
    lg.error("url=https://api.telegram.org/bot%s/getMe", FAKE)
    try:
        raise RuntimeError(f"boom {FAKE}")
    except RuntimeError:
        lg.exception("xato")
    out = buf.getvalue()
    assert FAKE not in out and "***" in out and "boom" in out


def _run(args, env_extra, tmp_path):
    env = {k: v for k, v in os.environ.items() if k not in ("BOT_TOKEN",)}
    env.update({"DB_PATH": str(tmp_path / "x.db"), "PYTHONPATH": str(ROOT)}, **env_extra)
    return subprocess.run([sys.executable, "-m", "navigator", *args], cwd=tmp_path, env=env,
                          capture_output=True, text=True, timeout=60)


def test_missing_token_exits_with_clear_message(tmp_path):
    r = _run([], {"BOT_TOKEN": ""}, tmp_path)
    assert r.returncode == 2
    assert "BOT_TOKEN topilmadi" in r.stdout + r.stderr


def test_check_mode_never_prints_token(tmp_path):
    r = _run(["--check"], {"BOT_TOKEN": FAKE}, tmp_path)
    out = r.stdout + r.stderr
    assert FAKE not in out and FAKE.split(":")[1] not in out
    assert "BOT_TOKEN o‘rnatilgan, formati to‘g‘ri" in out
    assert "Baza:" in out and "savollar:" in out
    assert "Telegram bilan aloqa" in out  # sandboxda tarmoq yo'q yoki token soxta → ❌, bu kutilgan
