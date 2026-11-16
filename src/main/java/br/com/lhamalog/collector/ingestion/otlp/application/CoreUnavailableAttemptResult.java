package br.com.lhamalog.collector.ingestion.otlp.application;

/**
 * Outcome of recording one Core-unavailable refusal for a deterministic batch key (LLT-183 D4).
 */
public sealed interface CoreUnavailableAttemptResult {

	/** Attempts 1..max → retryable 503 with Retry-After. */
	record WithinBudget(long attempt, long retryAfterSeconds) implements CoreUnavailableAttemptResult {
		public WithinBudget {
			if (attempt < 1 || retryAfterSeconds < 0) {
				throw new IllegalArgumentException("invalid within-budget attempt");
			}
		}
	}

	/** Attempt above max while Core is still unavailable → non-retryable drop. */
	record Exhausted(long attempt) implements CoreUnavailableAttemptResult {
		public Exhausted {
			if (attempt < 1) {
				throw new IllegalArgumentException("attempt must be at least 1");
			}
		}
	}

	/**
	 * Redis (or store) error while counting: plain 503 with Retry-After, no budget enforcement.
	 */
	record StoreError() implements CoreUnavailableAttemptResult {
	}
}
