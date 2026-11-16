// Railway Infrastructure as Code (LLT-187): observability module for the Collector.
// Config as Code (railway.json / railway.toml) is deprecated and stops being read on 2026-12-01, so this
// uses .railway/railway.ts. Plan with `railway config plan`; never apply without Michel's OK.
// Secrets are never written here: GF_SECURITY_ADMIN_PASSWORD uses preserve() and must be set in Railway.
import { defineRailway, github, group, preserve, project, service, volume } from "railway/iac";

/** Partial: this file owns only the observability services, not the Collector/Core services. */
export const partial = "observability";

const REPO = "LhamaLog/lhamalog-collector";

export default defineRailway((ctx) => {
  const branch = ctx.environment === "production" ? "main" : "Development";
  const source = github(REPO, { branch });
  const restart = { restartPolicyType: "ON_FAILURE" as const, restartPolicyMaxRetries: 10 };

  const prometheusData = volume("prometheus-data", { sizeMB: 1024 });
  const lokiData = volume("loki-data", { sizeMB: 2048 });
  const grafanaData = volume("grafana-data", { sizeMB: 512 });

  // Private only: no domains. Scrapes the Collector management port over private networking.
  const prometheus = service("prometheus", {
    source,
    build: {
      builder: "DOCKERFILE",
      dockerfilePath: "infra/railway/prometheus/Dockerfile",
      watchPatterns: ["infra/railway/prometheus/**"],
    },
    deploy: { healthcheckPath: "/-/ready", healthcheckTimeout: 120, ...restart },
    volumeMounts: { "/prometheus": prometheusData },
    env: {
      PORT: "9090",
      COLLECTOR_METRICS_TARGET: "lhamalog-collector.railway.internal:9464",
      PROMETHEUS_RETENTION: "7d",
      RAILWAY_RUN_UID: "0",
    },
  });

  // Private only: no domains. auth_enabled=false is acceptable only because nothing outside the
  // project's private network can reach it.
  const loki = service("loki", {
    source,
    build: {
      builder: "DOCKERFILE",
      dockerfilePath: "infra/railway/loki/Dockerfile",
      watchPatterns: ["infra/railway/loki/**"],
    },
    deploy: { healthcheckPath: "/ready", healthcheckTimeout: 300, ...restart },
    volumeMounts: { "/loki": lokiData },
    env: { PORT: "3100", RAILWAY_RUN_UID: "0" },
  });

  // Private only: receives OTLP/HTTP logs from the Collector and forwards them to Loki.
  const alloy = service("alloy", {
    source,
    build: {
      builder: "DOCKERFILE",
      dockerfilePath: "infra/railway/alloy/Dockerfile",
      watchPatterns: ["infra/railway/alloy/**"],
    },
    deploy: { healthcheckPath: "/-/ready", healthcheckTimeout: 120, ...restart },
    env: {
      PORT: "12345",
      LOKI_PUSH_URL: "http://loki.railway.internal:3100/loki/api/v1/push",
    },
  });

  // The only service that may get a public domain (generated in the dashboard, never committed).
  const grafana = service("grafana", {
    source,
    build: {
      builder: "DOCKERFILE",
      dockerfilePath: "infra/railway/grafana/Dockerfile",
      watchPatterns: ["infra/railway/grafana/**", "infra/local/observability/grafana/dashboards/**"],
    },
    deploy: { healthcheckPath: "/api/health", healthcheckTimeout: 120, ...restart },
    volumeMounts: { "/var/lib/grafana": grafanaData },
    env: {
      PORT: "3000",
      GF_SERVER_HTTP_PORT: "3000",
      GF_SECURITY_ADMIN_USER: "admin",
      GF_SECURITY_ADMIN_PASSWORD: preserve(),
      GF_AUTH_ANONYMOUS_ENABLED: "false",
      GF_AUTH_DISABLE_LOGIN_FORM: "false",
      GF_USERS_ALLOW_SIGN_UP: "false",
      GF_SECURITY_COOKIE_SECURE: "true",
      PROMETHEUS_URL: "http://prometheus.railway.internal:9090",
      LOKI_URL: "http://loki.railway.internal:3100",
      RAILWAY_RUN_UID: "0",
    },
  });

  return project("lhamalog", {
    resources: [
      group("Observability", [prometheus, loki, alloy, grafana, prometheusData, lokiData, grafanaData]),
    ],
  });
});
