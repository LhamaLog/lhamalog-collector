package br.com.lhamalog.collector.ingestion.otlp.application;

/**
 * Per-batch Core-unavailable retry budget (LLT-183 D4). Keyed by the deterministic reservation id.
 */
public interface CoreUnavailableAttemptTracker {

	CoreUnavailableAttemptResult record(String batchKey);

	void clear(String batchKey);

	/**
	 * No Redis / tests: every refusal stays within budget (never exhausts).
	 */
	static CoreUnavailableAttemptTracker alwaysRetryable(long retryAfterSeconds) {
		return new CoreUnavailableAttemptTracker() {
			@Override
			public CoreUnavailableAttemptResult record(String batchKey) {
				return new CoreUnavailableAttemptResult.WithinBudget(1, retryAfterSeconds);
			}

			@Override
			public void clear(String batchKey) {
			}
		};
	}
}
