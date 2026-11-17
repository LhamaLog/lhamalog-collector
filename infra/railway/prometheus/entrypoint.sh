#!/bin/sh
# Renders the scrape target from COLLECTOR_METRICS_TARGET and listens on all interfaces (IPv4 + IPv6),
# which Railway private networking needs. No secrets involved.
set -eu
target="${COLLECTOR_METRICS_TARGET:-lhamalog-collector.railway.internal:9464}"
sed "s|__COLLECTOR_METRICS_TARGET__|${target}|" /etc/prometheus/prometheus.yml.template > /tmp/prometheus.yml
exec /bin/prometheus \
  --config.file=/tmp/prometheus.yml \
  --storage.tsdb.path=/prometheus \
  --storage.tsdb.retention.time="${PROMETHEUS_RETENTION:-7d}" \
  --storage.tsdb.retention.size=900MB \
  --web.listen-address=":${PORT:-9090}"
