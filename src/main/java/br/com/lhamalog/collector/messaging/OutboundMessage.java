// Author: LhamaLog Desenvolvimento (IA) - LLT-182
package br.com.lhamalog.collector.messaging;

import java.util.Map;

/**
 * One message a publisher is asked to send. The caller supplies the idempotency id so that a retry of
 * the same input produces the same id.
 */
public record OutboundMessage<T>(String idempotencyId, T body, Map<String, String> metadata) {

	public OutboundMessage {
		if (idempotencyId == null || idempotencyId.isBlank()) {
			throw new IllegalArgumentException("idempotencyId must not be blank");
		}
		if (body == null) {
			throw new IllegalArgumentException("body must not be null");
		}
		metadata = Map.copyOf(metadata);
		String tenantId = metadata.get(MetadataKeys.TENANT_ID);
		if (tenantId == null || tenantId.isBlank()) {
			throw new IllegalArgumentException("metadata must carry a tenantId");
		}
	}

}
