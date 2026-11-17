# Grafana for Railway (LLT-187). Build context: repository root.
# Admin password only from the GF_SECURITY_ADMIN_PASSWORD Railway variable; anonymous access disabled.
# The entrypoint aborts when the password is missing or empty (review R1): no admin/admin fallback.
FROM grafana/grafana:12.2.0
COPY infra/railway/grafana/provisioning /etc/grafana/provisioning
COPY infra/local/observability/grafana/dashboards /var/lib/grafana-dashboards
COPY infra/railway/grafana/entrypoint.sh /usr/local/bin/lhamalog-grafana-entrypoint.sh
ENV GF_AUTH_ANONYMOUS_ENABLED=false \
    GF_USERS_ALLOW_SIGN_UP=false
ENTRYPOINT ["/bin/sh", "/usr/local/bin/lhamalog-grafana-entrypoint.sh"]
