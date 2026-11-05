// Author: LhamaLog Desenvolvimento (IA) - LLT-182
package br.com.lhamalog.collector.ingestion.otlp.sink;

import java.time.Instant;
import java.util.List;

/**
 * Location of a batch confirmed by OpenObserve. Each document carries {@link #BATCH_ID_FIELD} and
 * {@link #RECORD_ID_FIELD}; the batch lies in {@code [minTimestampMicros, maxTimestampMicros]} of
 * {@code _timestamp}, the range the OpenObserve search API requires.
 */
public record StoredBatch(Signal signal, String organization, String stream, String batchId, List<String> recordIds,
		long minTimestampMicros, long maxTimestampMicros, String receivedAt) {

	/** Batch id written in every document of logs and traces; with the range of {@code _timestamp} it locates the batch. */
	public static final String BATCH_ID_FIELD = "lhamalog_batch_id";

	/** Record id written in every document (one per log or span); locates a single record. */
	public static final String RECORD_ID_FIELD = "lhamalog_record_id";

	public StoredBatch {
		recordIds = List.copyOf(recordIds);
	}

	/** Empty batch: nothing is written to OpenObserve and nothing is published. */
	static StoredBatch empty(Signal signal, String organization, String stream, Instant receivedAt) {
		long micros = OtlpValues.micros(receivedAt);
		return new StoredBatch(signal, organization, stream, RecordIds.batchId(signal, List.of()), List.of(), micros,
				micros, receivedAt.toString());
	}

	/** The record id when the batch has a single record, so the reference points straight at it; else null. */
	public String singleRecordId() {
		return recordIds.size() == 1 ? recordIds.getFirst() : null;
	}

}