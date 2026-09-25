#!/usr/bin/env bash
# dev/update.sh text "/start" | dev/update.sh callback "status" | dev/update.sh file <url> <name>
# Шлёт событие MAX от лица DEV_MAX_USER_ID (см. .env.example) на TARGET
# (по умолчанию локальный бэкенд; TARGET=https://<сервер> — на боевой, только
# для сквозных проверок после мержа, не для отладки).
# Ответ бота уходит настоящим вызовом MAX Bot API — придёт человеку в MAX.
set -euo pipefail

TARGET="${TARGET:-http://localhost:8080}"
USER_ID="${DEV_MAX_USER_ID:?Задайте DEV_MAX_USER_ID (user_id в MAX, от чьего имени слать событие)}"
SECRET="${WEBHOOK_SECRET:?Задайте WEBHOOK_SECRET (тот же, что в backend/.env)}"
KIND="${1:?Использование: dev/update.sh text|callback|file ...}"
NOW_MS=$(($(date +%s%N) / 1000000))
MID="dev-$NOW_MS"

case "$KIND" in
  text)
    TEXT="${2:?dev/update.sh text \"<сообщение>\"}"
    BODY=$(printf '{"update_type":"message_created","timestamp":%d,"message":{"sender":{"user_id":%s},"recipient":{"user_id":%s},"timestamp":%d,"body":{"mid":"%s","text":%s}}}' \
      "$NOW_MS" "$USER_ID" "$USER_ID" "$NOW_MS" "$MID" "$(printf '%s' "$TEXT" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))')")
    ;;
  callback)
    PAYLOAD="${2:?dev/update.sh callback \"<payload>\"}"
    BODY=$(printf '{"update_type":"message_callback","timestamp":%d,"callback":{"callback_id":"%s","payload":%s,"user":{"user_id":%s}},"message":{"sender":{"user_id":%s},"recipient":{"user_id":%s},"timestamp":%d,"body":{"mid":"%s"}}}' \
      "$NOW_MS" "$MID" "$(printf '%s' "$PAYLOAD" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))')" "$USER_ID" "$USER_ID" "$USER_ID" "$NOW_MS" "$MID")
    ;;
  file)
    URL="${2:?dev/update.sh file <url> <имя файла>}"
    NAME="${3:?dev/update.sh file <url> <имя файла>}"
    BODY=$(printf '{"update_type":"message_created","timestamp":%d,"message":{"sender":{"user_id":%s},"recipient":{"user_id":%s},"timestamp":%d,"body":{"mid":"%s","attachments":[{"type":"file","payload":{"url":%s},"filename":%s}]}}}' \
      "$NOW_MS" "$USER_ID" "$USER_ID" "$NOW_MS" "$MID" \
      "$(printf '%s' "$URL" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))')" \
      "$(printf '%s' "$NAME" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))')")
    ;;
  *)
    echo "Неизвестный тип события: $KIND (ожидается text|callback|file)" >&2
    exit 1
    ;;
esac

curl -sS -X POST "$TARGET/webhook/max/$SECRET" -H 'Content-Type: application/json' -d "$BODY"
echo
