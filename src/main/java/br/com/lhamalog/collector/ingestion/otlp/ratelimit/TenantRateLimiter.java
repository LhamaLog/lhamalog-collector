package br.com.lhamalog.collector.ingestion.otlp.ratelimit;

/**
 * Per-tenant OTLP request rate check (LLT-183). Invoked after authentication and before the body is
 * read or sized.
 */
@FunctionalInterface
public interface TenantRateLimiter {

	enum Verdict {
		/** Under the configured limit. */
		ALLOWED,
		/** Over the configured limit for the current one-second window (Retry-After = rate retry-after). */
		LIMITED,
		/** Tenant blocked after repeated violations (Retry-After = block-retry-after). */
		BLOCKED
	}

	/**
	 * Records one OTLP request for {@code tenantId} and returns whether it may proceed.
	 */
	Verdict check(String tenantId);

	/** Always allows; used when rate limiting is disabled or in unit tests without Redis. */
	static TenantRateLimiter allowAll() {
		return tenantId -> Verdict.ALLOWED;
	}
}
