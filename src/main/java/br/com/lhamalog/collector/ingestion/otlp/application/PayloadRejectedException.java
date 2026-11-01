// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.application;

import br.com.lhamalog.collector.ingestion.otlp.domain.IngestionDecision;

/** The body cannot become an OTLP request: malformed, unsupported encoding or too large. */
public class PayloadRejectedException extends Exception {

	private final IngestionDecision.Reason reason;

	public PayloadRejectedException(IngestionDecision.Reason reason, String message) {
		super(message);
		this.reason = reason;
	}

	public PayloadRejectedException(IngestionDecision.Reason reason, String message, Throwable cause) {
		super(message, cause);
		this.reason = reason;
	}

	public IngestionDecision.Reason reason() {
		return reason;
	}

}
