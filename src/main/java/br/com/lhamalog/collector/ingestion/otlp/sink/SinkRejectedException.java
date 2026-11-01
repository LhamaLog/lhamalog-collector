// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.sink;

/**
 * The destination answered, but refused some or all records as invalid data (T040, FR-020): an OpenObserve
 * 4xx about the content, or per record failures in an HTTP 200 answer. Unlike
 * {@link SinkUnavailableException}, sending the same data again gets the same refusal, so this never
 * becomes a retryable answer to the OTLP sender.
 */
public class SinkRejectedException extends RuntimeException {

	private final int rejectedCount;

	private final int acceptedCount;

	/**
	 * @param rejectedCount records the destination refused
	 * @param acceptedCount records the destination stored anyway (a partial write)
	 * @param message diagnostic for the collector log; it is not sent to the OTLP client
	 */
	public SinkRejectedException(int rejectedCount, int acceptedCount, String message) {
		super(message);
		if (rejectedCount <= 0 || acceptedCount < 0) {
			throw new IllegalArgumentException("rejectedCount must be positive and acceptedCount not negative");
		}
		this.rejectedCount = rejectedCount;
		this.acceptedCount = acceptedCount;
	}

	public int rejectedCount() {
		return rejectedCount;
	}

	public int acceptedCount() {
		return acceptedCount;
	}

}
