// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.domain;

import java.util.Objects;

/**
 * Outcome of one OTLP export, before it becomes an HTTP or gRPC response (T005).
 *
 * <p>See {@code specs/001-otlp-receiver/data-model.md}, section IngestionDecision. A failure of the
 * destination in the middle of a valid batch always ends in {@link DestinationUnavailable}, even when
 * the assessment already rejected other records.
 */
public sealed interface IngestionDecision {

	/** Missing, empty, unknown, revoked or expired token, or token of a missing product. Sink not called. */
	record Unauthorized() implements IngestionDecision {
	}

	/** Reading {@code product_tokens} or {@code products} failed. Sink not called. */
	record CatalogUnavailable() implements IngestionDecision {
	}

	/** Unreadable body, unsupported encoding, body above the limit, or every record invalid. Sink not called. */
	record InvalidPayload(Reason reason, String message) implements IngestionDecision {

		public InvalidPayload {
			Objects.requireNonNull(reason, "reason");
			Objects.requireNonNull(message, "message");
		}

	}

	/** Why a payload was refused as a whole. */
	enum Reason {

		/** Body that cannot be decoded as the expected OTLP message. */
		MALFORMED,

		/** Content type or content encoding not supported by the receiver. */
		UNSUPPORTED_ENCODING,

		/** Decompressed body or gRPC message above the configured limit. */
		TOO_LARGE,

		/** Well formed batch in which every record is invalid. */
		ALL_RECORDS_INVALID

	}

	/** Well formed batch with zero records. Sink not called. */
	record EmptySuccess() implements IngestionDecision {
	}

	/** One or more valid records, no rejection, and the sink confirmed all of them. */
	record Accepted(int acceptedCount) implements IngestionDecision {
	}

	/** Some records rejected; the sink confirmed the valid ones. */
	record PartiallyAccepted(int acceptedCount, int rejectedCount, String errorMessage) implements IngestionDecision {

		public PartiallyAccepted {
			if (rejectedCount <= 0 || acceptedCount <= 0) {
				throw new IllegalArgumentException("partial acceptance needs accepted and rejected records");
			}
			Objects.requireNonNull(errorMessage, "errorMessage");
		}

	}

	/** There were valid records and the sink did not confirm them. Nothing is confirmed to the sender. */
	record DestinationUnavailable(long retryAfterSeconds) implements IngestionDecision {
	}

}
