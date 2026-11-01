// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.domain;

import java.util.List;

/**
 * Records of one batch split into valid and rejected. Rejection is counted per record, never per resource
 * or scope.
 *
 * @param valid records that may go to the sink, in batch order
 * @param rejectedCount records refused by the assessment
 */
public record BatchAssessment<T>(List<T> valid, int rejectedCount) {

	public BatchAssessment {
		valid = List.copyOf(valid);
		if (rejectedCount < 0) {
			throw new IllegalArgumentException("rejectedCount must not be negative");
		}
	}

	public int totalCount() {
		return valid.size() + rejectedCount;
	}

}
