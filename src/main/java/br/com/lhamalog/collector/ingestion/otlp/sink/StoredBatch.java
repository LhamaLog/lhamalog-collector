// Author: LhamaLog Desenvolvimento (IA) - LLT-182
package br.com.lhamalog.collector.ingestion.otlp.sink;

import java.util.List;

/**
 * Location of a batch confirmed by OpenObserve. Each document carries {@code lhamalog_batch_id} and
 * {@code lhamalog_record_id}; the batch lies in {@code [minTimestampMicros, maxTimestampMicros]} of
 * {@code _timestamp}, the range the OpenObserve search API requires.
 */
public record StoredBatch(Signal signal, String organization, String stream, String batchId, List<String> recordIds,
		long minTimestampMicros, long maxTimestampMicros, String receivedAt) {

	public StoredBatch {
		recordIds = List.copyOf(recordIds);
	}

	/** The record id when the batch has a single record, so the reference points straight at it; else null. */
	public String singleRecordId() {
		return recordIds.size() == 1 ? recordIds.getFirst() : null;
	}

}
