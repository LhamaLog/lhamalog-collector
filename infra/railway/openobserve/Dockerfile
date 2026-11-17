# OpenObserve for Railway (LLT-187). Build context: repository root. Same version as infra/local/compose.yaml.
# Private network only (no public domain). Root credentials only from sealed Railway variables
# (ZO_ROOT_USER_EMAIL / ZO_ROOT_USER_PASSWORD); data on the openobserve-data volume.
FROM public.ecr.aws/zinclabs/openobserve:v1.1.2
ENV ZO_DATA_DIR=/data \
    ZO_HTTP_PORT=5080 \
    ZO_HTTP_IPV6_ENABLED=true \
    ZO_TELEMETRY=false