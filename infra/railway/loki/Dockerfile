# Loki for Railway (LLT-187). Build context: repository root.
FROM grafana/loki:3.5.3
COPY infra/railway/loki/loki-config.yaml /etc/loki/railway-config.yaml
CMD ["-config.file=/etc/loki/railway-config.yaml"]
