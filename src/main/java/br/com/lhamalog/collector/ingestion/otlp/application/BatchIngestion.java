// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.application;

import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidationResult;
import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidator;
import br.com.lhamalog.collector.ingestion.otlp.domain.BatchAssessment;
import br.com.lhamalog.collector.ingestion.otlp.domain.IngestionDecision;
import br.com.lhamalog.collector.ingestion.otlp.sink.SinkUnavailableException;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flow shared by logs and traces: authenticate, read, assess, deliver, decide. Order matters: the token is
 * checked before the body is decoded, the sink is never called for an empty or fully invalid batch, and a
 * sink failure is never turned into a (partial) success.
 */
final class BatchIngestion {

	private static final Logger log = LoggerFactory.getLogger(BatchIngestion.class);

	@FunctionalInterface
	interface Delivery<T> {

		void deliver(String tenantId, String productId, List<T> records);

	}

	private BatchIngestion() {
	}

	static <R, T> IngestionDecision run(TokenValidator tokens, String bearerToken, OtlpPayload<R> payload,
			Function<R, BatchAssessment<T>> assessment, Delivery<T> sink, String recordName, String rejectionReason) {
		TokenValidationResult auth = tokens.validate(bearerToken);
		TokenValidationResult.Valid valid;
		switch (auth) {
			case TokenValidationResult.Unauthorized u -> {
				return new IngestionDecision.Unauthorized();
			}
			case TokenValidationResult.CatalogUnavailable c -> {
				return new IngestionDecision.CatalogUnavailable();
			}
			case TokenValidationResult.Valid v -> valid = v;
		}
		R request;
		try {
			request = payload.read();
		}
		catch (PayloadRejectedException ex) {
			return new IngestionDecision.InvalidPayload(ex.reason(), ex.getMessage());
		}
		BatchAssessment<T> batch = assessment.apply(request);
		if (batch.totalCount() == 0) {
			return new IngestionDecision.EmptySuccess();
		}
		if (batch.valid().isEmpty()) {
			return new IngestionDecision.InvalidPayload(IngestionDecision.Reason.ALL_RECORDS_INVALID,
					"all " + batch.rejectedCount() + " " + recordName + " are invalid: " + rejectionReason);
		}
		try {
			sink.deliver(valid.tenantId(), valid.productId(), batch.valid());
		}
		catch (SinkUnavailableException ex) {
			log.warn("Destination did not confirm {} {} of product {}: {}", batch.valid().size(), recordName,
					valid.productId(), ex.getMessage());
			return new IngestionDecision.DestinationUnavailable(ex.retryAfterSeconds());
		}
		if (batch.rejectedCount() == 0) {
			return new IngestionDecision.Accepted(batch.valid().size());
		}
		return new IngestionDecision.PartiallyAccepted(batch.valid().size(), batch.rejectedCount(),
				batch.rejectedCount() + " " + recordName + " rejected: " + rejectionReason);
	}

}
