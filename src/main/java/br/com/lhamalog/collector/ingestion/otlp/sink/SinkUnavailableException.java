// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.sink;

/**
 * Destino indisponivel ou que nao confirmou o recebimento (T013).
 *
 * <p>Carrega o {@code Retry-After}, em segundos, que o canal HTTP devolve no 503.
 */
public class SinkUnavailableException extends RuntimeException {

	/** Valor padrao de {@code Retry-After} quando o destino nao informa outro. */
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
