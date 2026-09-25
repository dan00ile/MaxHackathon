#!/usr/bin/env bash
# Установка бэкенда на VPS (Ubuntu 22.04/24.04) и автообновление из master.
# Первый запуск (от root):
#   curl -fsSL https://raw.githubusercontent.com/dan00ile/MaxHackathon/master/deploy/setup.sh | bash
# Дальше systemd-таймер раз в 2 минуты вызывает `setup.sh update`: если в master
# новые коммиты — git reset на них и пересборка контейнеров. .env не трогается.
set -euo pipefail

REPO=git@github.com:dan00ile/MaxHackathon.git
DIR=/opt/maxhackathon

update() {
  cd "$DIR"
  git fetch -q origin master
  local target; target=$(git rev-parse origin/master)
  # .deployed пишется только после успешной сборки — упавшая сборка повторится на следующем тике
  [ "$target" = "$(cat .deployed 2>/dev/null)" ] && return 0
  git reset -q --hard "$target"
  GIT_SHA="$target" docker compose up -d --build   # GIT_SHA → /health (S4c)
  echo "$target" > .deployed
  echo "$(date -Is) обновлено до ${target:0:7}"
}

set_env() {  # set_env KEY VALUE — без sed, чтобы спецсимволы в токенах не ломали замену
  grep -v "^$1=" .env > .env.tmp || true
  echo "$1=$2" >> .env.tmp
  mv .env.tmp .env
}

if [ "${1:-}" = "update" ]; then update; exit 0; fi

command -v docker >/dev/null || curl -fsSL https://get.docker.com | sh
command -v git >/dev/null || { apt-get update -q && apt-get install -y -q git; }
[ -d "$DIR/.git" ] || git clone -q "$REPO" "$DIR"
cd "$DIR"

if [ ! -f .env ]; then
  cp .env.example .env
  chmod 600 .env
  read -rsp "MAX_BOT_TOKEN: " t </dev/tty; echo; set_env MAX_BOT_TOKEN "$t"
  read -rsp "GIGACHAT_AUTH_KEY (Enter — пропустить): " g </dev/tty; echo; set_env GIGACHAT_AUTH_KEY "$g"
  set_env POSTGRES_PASSWORD "$(openssl rand -hex 16)"
  set_env CORS_ORIGIN "https://dan00ile.github.io"
fi

# HTTPS-адрес <ip>.sslip.io (домен не нужен); он же адрес webhook бота
IP=$(curl -fsS https://api.ipify.org)
HOST="${IP//./-}.sslip.io"
set_env PUBLIC_URL "https://$HOST"
grep -q '^WEBHOOK_SECRET=.\{32,\}' .env || set_env WEBHOOK_SECRET "$(openssl rand -hex 24)"

GIT_SHA="$(git rev-parse HEAD)" docker compose up -d --build
git rev-parse HEAD > .deployed

# Caddy в контейнере, сертификат Let's Encrypt
docker rm -f caddy >/dev/null 2>&1 || true
docker run -d --name caddy --restart unless-stopped --network host -v caddy_data:/data \
  caddy:2 caddy reverse-proxy --from "$HOST" --to localhost:8080

cat > /etc/systemd/system/maxhackathon-update.service <<EOF
[Service]
Type=oneshot
ExecStart=/bin/bash $DIR/deploy/setup.sh update
EOF
cat > /etc/systemd/system/maxhackathon-update.timer <<EOF
[Timer]
OnBootSec=1min
OnUnitActiveSec=2min
[Install]
WantedBy=timers.target
EOF
systemctl daemon-reload
systemctl enable --now maxhackathon-update.timer

echo
echo "Готово. Бэкенд: https://$HOST  (проверка: https://$HOST/health → ok)"
echo "Порты 80 и 443 должны быть открыты в панели провайдера."
echo "Лог автообновлений: journalctl -u maxhackathon-update"
