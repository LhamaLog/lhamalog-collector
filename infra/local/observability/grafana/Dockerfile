# Grafana for the local observability stack (LLT-187). Build context: repository root.
# Adds the official OpenObserve datasource plugin (unsigned, frontend only), pinned by SHA-256.
FROM grafana/grafana:12.2.0
USER root
ARG OO_PLUGIN_URL=https://zincsearch-releases.s3.us-west-2.amazonaws.com/zo_gp/zo_gp.tar.gz
ARG OO_PLUGIN_SHA256=85e7542749e17a437b84547215b63e73286570ad917b0b472de47e5398dee5d1
RUN mkdir -p /var/lib/grafana-plugins \
 && wget -qO /tmp/zo_gp.tar.gz "$OO_PLUGIN_URL" \
 && echo "$OO_PLUGIN_SHA256  /tmp/zo_gp.tar.gz" | sha256sum -c - \
 && tar -xzf /tmp/zo_gp.tar.gz -C /var/lib/grafana-plugins 2>/dev/null \
 && find /var/lib/grafana-plugins -name '._*' -delete \
 && rm /tmp/zo_gp.tar.gz \
 && chown -R 472:0 /var/lib/grafana-plugins
USER 472
# Plugins live outside /var/lib/grafana so a data volume never hides them.
ENV GF_PATHS_PLUGINS=/var/lib/grafana-plugins \
    GF_PLUGINS_ALLOW_LOADING_UNSIGNED_PLUGINS=openobserve