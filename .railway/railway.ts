// Railway Infrastructure as Code (LLT-187): observability module for the Collector.
// Config as Code (railway.json / railway.toml) is deprecated and stops being read on 2026-12-01, so this
// uses .railway/railway.ts. Plan with `railway config plan`; never apply without Michel's OK.
// Secrets are never written here: every credential uses preserve() and must be set in Railway as a sealed
// variable (GF_SECURITY_ADMIN_PASSWORD, ZO_ROOT_USER_EMAIL, ZO_ROOT_USER_PASSWORD, OPENOBSERVE_AUTH_BASIC, OPENOBSERVE_USERNAME,
// OPENOBSERVE_PASSWORD).
import { defineRailway, github, group, preserve, project, service, volume } from "railway/iac";

/** Partial: this file owns only the observability services, not the Collector/Core services. */
export const partial = "observability-stack";

const REPO = "LhamaLog/lhamalog-collector";

/** Private OpenObserve URL. Platform logs: org "default", stream "lhamalog_platform" (see infra/railway/alloy). */
const OPENOBSERVE_URL = "http://openobserve.railway.internal:5080";

export default defineRailway((ctx) => {
  const branch = ctx.environment === "production" ? "main" : "Development";
  const source = github(REPO, { branch });
  const restart = { restartPolicyType: "ON_FAILURE" as const, restartPolicyMaxRetries: 10 };

  const prometheusData = volume("prometheus-data", { sizeMB: 1024 });
  const openobserveData = volume("openobserve-data", { sizeMB: 2048 });
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

  // Private only: no domains (the UI stays behind the OpenObserve login, reachable only from the private
  // network). Platform logs live in org "default", stream "lhamalog_platform"; tenant data never goes there.
  const openobserve = service("openobserve", {
    source,
    build: {
      builder: "DOCKERFILE",
      dockerfilePath: "infra/railway/openobserve/Dockerfile",
      watchPatterns: ["infra/railway/openobserve/**"],
    },
    deploy: { healthcheckPath: "/healthz", healthcheckTimeout: 120, ...restart },
    volumeMounts: { "/data": openobserveData },
    env: {
      PORT: "5080",
      ZO_ROOT_USER_EMAIL: preserve(),
      ZO_ROOT_USER_PASSWORD: preserve(),
      RAILWAY_RUN_UID: "0",
    },
  });

  // Private only: receives OTLP/HTTP logs from the modules and sends them to OpenObserve (default/lhamalog_platform).
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
      OPENOBSERVE_URL,
      OPENOBSERVE_PLATFORM_ORG: "default",
      OPENOBSERVE_PLATFORM_STREAM: "lhamalog_platform",
      OPENOBSERVE_AUTH_BASIC: preserve(),
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
      OPENOBSERVE_URL,
      // Search login for the datasource (the ingestion passcode in OPENOBSERVE_AUTH_BASIC cannot search).
      OPENOBSERVE_USERNAME: preserve(),
      OPENOBSERVE_PASSWORD: preserve(),
      RAILWAY_RUN_UID: "0",
    },
  });

  return project("lhamalog", {
    resources: [
      group("Observability", [prometheus, openobserve, alloy, grafana, prometheusData, openobserveData, grafanaData]),
    ],
  });
});