#!/usr/bin/env bash
# NAVIGATOR botini ishga tushirish (Linux / macOS)
set -e
cd "$(dirname "$0")"
PY=${PYTHON:-python3}
if [ ! -d .venv ]; then
  echo "📦 Virtual muhit yaratilmoqda..."
  "$PY" -m venv .venv
fi
.venv/bin/pip install -q --upgrade pip
.venv/bin/pip install -q -r requirements.txt
if [ ! -f .env ] || grep -q "replace-me" .env; then
  read -rp "🔑 @BotFather dan olingan BOT_TOKEN ni kiriting: " TOKEN
  sed "s|^BOT_TOKEN=.*|BOT_TOKEN=$TOKEN|" .env.example > .env
  echo "✅ Token .env fayliga saqlandi"
fi
echo "🚀 Bot ishga tushdi. To'xtatish uchun Ctrl+C."
exec .venv/bin/python -m navigator
