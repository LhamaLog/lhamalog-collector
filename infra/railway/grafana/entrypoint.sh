#!/bin/sh
# Grafana entrypoint for Railway (LLT-187 review R1). Refuses to start without an admin password, so Grafana
# never falls back to admin/admin and never persists that default in the grafana-data volume.
# Accepts GF_SECURITY_ADMIN_PASSWORD (Railway variable) or GRAFANA_ADMIN_PASSWORD (same name as local).
set -eu

check_admin_password() {
  password="${GF_SECURITY_ADMIN_PASSWORD:-${GRAFANA_ADMIN_PASSWORD:-}}"
  trimmed="$(printf '%s' "$password" | tr -d '[:space:]')"
  if [ -z "$trimmed" ] || [ "$password" = "admin" ]; then
    printf '%s ERROR [grafana-entrypoint] [cid=startup] entrypoint.sh.check_admin_password - event=grafana.admin_password.missing | GF_SECURITY_ADMIN_PASSWORD (or GRAFANA_ADMIN_PASSWORD) is missing, empty or "admin"; set it as a sealed Railway variable. Grafana will not start.\n' \
      "$(date '+%Y-%m-%dT%H:%M:%S%z')" >&2
    exit 1
  fi
  export GF_SECURITY_ADMIN_PASSWORD="$password"
  unset GRAFANA_ADMIN_PASSWORD 2>/dev/null || true
}

check_admin_password
exec /run.sh "$@"