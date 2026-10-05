#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
env_file="${1:-.env}"
if [[ ! -f "$env_file" ]]; then
  echo "Missing $env_file. Copy .env.example and fill the credentials." >&2; exit 1
fi
set -a
source "$env_file"
set +a
[[ "${DB_NAME:-}" =~ ^[A-Za-z][A-Za-z0-9_]*$ ]] || { echo 'Invalid DB_NAME' >&2; exit 1; }
: "${DB_HOST:?}" "${DB_PORT:?}" "${DB_USER:?}" "${DB_PASSWORD:?}"
export ENV_FILE="$env_file"
: "${NOTES_SERVICE_TOKEN:?}" "${BRIDGE_SERVICE_TOKEN:?}" "${VECTOR_SERVICE_TOKEN:?}" "${KB_SHARED_SECRET:?}" "${MINIO_ACCESS_KEY:?}" "${MINIO_SECRET_KEY:?}" "${LLM_API_KEY:?}"
docker info >/dev/null
# External MySQL is not part of this application's Compose stack.
sed -e "s/^CREATE DATABASE IF NOT EXISTS [a-z_]* /CREATE DATABASE IF NOT EXISTS $DB_NAME /" -e "s/^USE [a-z_]*;/USE $DB_NAME;/" db/schema.sql |
  docker run --rm -i --add-host=host.docker.internal:host-gateway --env MYSQL_PWD="$DB_PASSWORD" mysql:8.4 \
    mysql --default-character-set=utf8mb4 --host="$DB_HOST" --port="$DB_PORT" --user="$DB_USER"
if ! docker compose --env-file "$env_file" up -d --build --remove-orphans --wait --wait-timeout 300; then
  docker compose --env-file "$env_file" logs --tail=80; exit 1
fi
printf 'Ready: HTTP port %s\n' "$SERVER_PORT"
