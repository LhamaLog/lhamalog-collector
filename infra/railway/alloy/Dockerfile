# Grafana Alloy for Railway (LLT-187). Build context: repository root.
FROM grafana/alloy:v1.10.2
COPY infra/railway/alloy/config.alloy /etc/alloy/config.alloy
CMD ["run", "/etc/alloy/config.alloy", "--server.http.listen-addr=[::]:12345", "--storage.path=/var/lib/alloy/data"]
