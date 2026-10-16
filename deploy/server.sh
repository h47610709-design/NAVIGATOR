#!/usr/bin/env bash
# NAVIGATOR botini Ubuntu serverga (Oracle Cloud Always Free, Google Cloud e2-micro yoki istalgan VPS)
# o'rnatish va boshqarish.
#
#   bash deploy/server.sh install   — o'rnatish (token yashirin so'raladi) va 24/7 xizmatni yoqish
#   bash deploy/server.sh update    — GitHub'dan yangilash va qayta ishga tushirish
#   bash deploy/server.sh token     — BOT_TOKEN ni almashtirish
#   bash deploy/server.sh check     — konfiguratsiya va Telegram aloqasini tekshirish
#   bash deploy/server.sh status    — xizmat holati
#   bash deploy/server.sh logs      — jonli loglar (chiqish: Ctrl+C)
#   bash deploy/server.sh backup    — bazaning zaxira nusxasi (backups/ papkasiga)
set -euo pipefail

SERVICE=navigator-bot
ENV_DIR=/etc/navigator
ENV_FILE=$ENV_DIR/navigator.env
APP_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
RUN_USER=${SUDO_USER:-$(id -un)}
PASS_ENV=BOT_TOKEN,DB_PATH,ADMIN_IDS,LOG_LEVEL,LOG_FILE,TZ,CELEBRATION_STICKER_SET,CELEBRATION_STICKERS,CELEBRATION_GIFS,CELEBRATION_EFFECT_ID

say()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m✔ %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m✘ %s\033[0m\n' "$*" >&2; exit 1; }

ask_token() {
  local token=""
  while true; do
    # -s: yozilayotgan token ekranda ko'rinmaydi
    read -rsp "🔑 @BotFather bergan BOT_TOKEN ni kiriting (ekranda ko'rinmaydi): " token; echo
    token=$(printf '%s' "$token" | tr -d '[:space:]"'"'")
    [[ $token =~ ^[0-9]{5,15}:[A-Za-z0-9_-]{30,}$ ]] && break
    echo "Token formati noto'g'ri (kutilgan: 123456789:ABC...). Qaytadan kiriting."
  done
  TOKEN=$token
}

write_env() {
  local admin_ids=""
  if sudo test -f "$ENV_FILE"; then
    admin_ids=$(sudo sed -n 's/^ADMIN_IDS=//p' "$ENV_FILE" | head -n1)
  fi
  if [[ -z $admin_ids ]]; then
    read -rp "👤 Admin Telegram ID (ixtiyoriy, @userinfobot dan bilish mumkin; bo'sh qoldirsa bo'ladi): " admin_ids
  fi
  sudo install -d -m 700 "$ENV_DIR"
  # printf — bash ichki buyrug'i, token jarayonlar ro'yxatida (ps) ko'rinmaydi
  printf '%s\n' \
    "# NAVIGATOR maxfiy sozlamalari — faqat root o'qiy oladi. GitHub'ga yuklamang!" \
    "BOT_TOKEN=$TOKEN" \
    "DB_PATH=$APP_DIR/data/navigator.db" \
    "ADMIN_IDS=$admin_ids" \
    "TZ=Asia/Tashkent" \
    "LOG_LEVEL=INFO" | sudo tee "$ENV_FILE" >/dev/null
  sudo chmod 600 "$ENV_FILE"
  sudo chown root:root "$ENV_FILE"
  unset TOKEN
  ok "Sozlamalar saqlandi: $ENV_FILE (ruxsat: 600, faqat root)"
}

run_check() {
  sudo bash -c "set -a; . '$ENV_FILE'; set +a; cd '$APP_DIR'; \
    exec sudo -u '$RUN_USER' --preserve-env=$PASS_ENV '$APP_DIR/.venv/bin/python' -m navigator --check"
}

install_deps() {
  command -v apt-get >/dev/null || die "Bu skript Ubuntu/Debian uchun. Serverda Ubuntu 22.04/24.04 tanlang."
  say "Tizim paketlari o'rnatilmoqda"
  sudo apt-get update -qq
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq python3 python3-venv python3-pip git fonts-dejavu-core sqlite3 >/dev/null
  python3 -c 'import sys; assert sys.version_info >= (3, 10), sys.version' \
    || die "Python 3.10+ kerak (topildi: $(python3 -V)). Ubuntu 24.04 da Python 3.12 bor."
  ok "$(python3 -V)"
}

setup_venv() {
  say "Virtual muhit va kutubxonalar"
  [[ -d $APP_DIR/.venv ]] || python3 -m venv "$APP_DIR/.venv"
  "$APP_DIR/.venv/bin/pip" install -q --upgrade pip
  "$APP_DIR/.venv/bin/pip" install -q -r "$APP_DIR/requirements.txt"
  mkdir -p "$APP_DIR/data"
  ok "Kutubxonalar o'rnatildi"
}

install_service() {
  say "systemd xizmati ($SERVICE)"
  sed -e "s|__USER__|$RUN_USER|g" -e "s|__DIR__|$APP_DIR|g" "$APP_DIR/deploy/navigator-bot.service" \
    | sudo tee /etc/systemd/system/$SERVICE.service >/dev/null
  sudo systemctl daemon-reload
  sudo systemctl enable --now $SERVICE
  sudo systemctl restart $SERVICE
  ok "Xizmat yoqildi: server qayta yonganda ham avtomatik ishga tushadi"
}

case "${1:-}" in
  install)
    [[ $EUID -eq 0 && -z ${SUDO_USER:-} ]] && die "Skriptni root sifatida emas, oddiy foydalanuvchi (masalan, ubuntu) sifatida ishga tushiring."
    install_deps
    setup_venv
    if sudo test -f "$ENV_FILE" && sudo grep -q '^BOT_TOKEN=.\+' "$ENV_FILE"; then
      ok "Mavjud sozlamalar topildi ($ENV_FILE) — token qayta so'ralmaydi (almashtirish: server.sh token)"
    else
      ask_token; write_env
    fi
    say "Tekshiruv"
    run_check || echo "⚠️  Tekshiruvda muammo bor — yuqoridagi xabarlarni o'qing."
    install_service
    sleep 5
    sudo systemctl --no-pager --lines=15 status $SERVICE || true
    say "Tayyor! Loglarni ko'rish: bash deploy/server.sh logs"
    ;;
  update)
    say "GitHub'dan yangilanmoqda"
    git -C "$APP_DIR" pull --ff-only
    setup_venv
    sudo systemctl restart $SERVICE
    ok "Yangilandi va qayta ishga tushirildi"
    ;;
  token)
    ask_token; write_env
    sudo systemctl restart $SERVICE
    ok "Token yangilandi, bot qayta ishga tushirildi"
    ;;
  check)  run_check ;;
  status) sudo systemctl --no-pager status $SERVICE ;;
  logs)   sudo journalctl -u $SERVICE -f -n 100 --no-pager ;;
  backup)
    mkdir -p "$APP_DIR/backups"
    out="$APP_DIR/backups/navigator-$(date +%Y%m%d-%H%M%S).db"
    sqlite3 "$APP_DIR/data/navigator.db" ".backup '$out'"
    ok "Zaxira: $out"
    ;;
  *)
    sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
