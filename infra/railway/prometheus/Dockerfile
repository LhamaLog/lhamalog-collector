# Prometheus for Railway (LLT-187). Build context: repository root.
FROM prom/prometheus:v3.5.0
COPY infra/railway/prometheus/prometheus.yml.template /etc/prometheus/prometheus.yml.template
COPY infra/railway/prometheus/entrypoint.sh /etc/prometheus/entrypoint.sh
ENTRYPOINT ["/bin/sh", "/etc/prometheus/entrypoint.sh"]
