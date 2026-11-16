# Grafana for Railway (LLT-187). Build context: repository root.
# Admin password only from the GF_SECURITY_ADMIN_PASSWORD Railway variable; anonymous access disabled.
FROM grafana/grafana:12.2.0
COPY infra/railway/grafana/provisioning /etc/grafana/provisioning
COPY infra/local/observability/grafana/dashboards /var/lib/grafana-dashboards
ENV GF_AUTH_ANONYMOUS_ENABLED=false \
    GF_USERS_ALLOW_SIGN_UP=false
