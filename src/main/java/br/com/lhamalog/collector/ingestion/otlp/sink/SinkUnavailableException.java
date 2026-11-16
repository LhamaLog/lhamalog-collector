// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.sink;

/**
 * Destination unavailable or that did not confirm receipt (T013).
 *
 * <p>Carries the {@code Retry-After} value, in seconds, returned on HTTP 503 (and aligned with gRPC
 * {@code RetryInfo} when the refusal path attaches it).
 */
public class SinkUnavailableException extends RuntimeException {

	/** Default {@code Retry-After} when the destination does not advertise another value. */
	public static final long DEFAULT_RETRY_AFTER_SECONDS = 5;

	private final long retryAfterSeconds;

	private final boolean publishedBeforeFailure;

	public SinkUnavailableException(String message) {
		this(message, DEFAULT_RETRY_AFTER_SECONDS, null);
	}

	public SinkUnavailableException(String message, Throwable cause) {
		this(message, DEFAULT_RETRY_AFTER_SECONDS, cause);
	}

	public SinkUnavailableException(String message, long retryAfterSeconds, Throwable cause) {
		super(message, cause);
		if (retryAfterSeconds < 0) {
			throw new IllegalArgumentException("retryAfterSeconds must not be negative");
		}
		this.retryAfterSeconds = retryAfterSeconds;
		this.publishedBeforeFailure = false;
	}

	private SinkUnavailableException(String message, Throwable cause, boolean publishedBeforeFailure) {
		super(message, cause);
		this.retryAfterSeconds = DEFAULT_RETRY_AFTER_SECONDS;
		this.publishedBeforeFailure = publishedBeforeFailure;
	}

	/**
	 * Failure raised after the records were already durably published to the read stream. The quota debit
	 * must stay: a published record is never left undebited (LLT-186 review round 2).
	 */
	public static SinkUnavailableException afterPublish(String message, Throwable cause) {
		return new SinkUnavailableException(message, cause, true);
	}

	/** Whether the records reached the read stream before this failure. */
	public boolean publishedBeforeFailure() {
		return publishedBeforeFailure;
	}

	public long retryAfterSeconds() {
		return retryAfterSeconds;
	}

}
