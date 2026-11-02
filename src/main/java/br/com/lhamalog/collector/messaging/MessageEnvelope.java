// Author: LhamaLog Desenvolvimento (IA) - LLT-182
package br.com.lhamalog.collector.messaging;

import java.util.Map;

/**
 * Envelope written to a Redis Stream entry. Each field becomes one stream field; {@code metadata} and
 * {@code body} are stored as JSON text.
 *
 * @param idempotencyId deterministic id the consumer uses to drop duplicates
 * @param event event name, e.g. {@code log.ingested}
 * @param origin service that published the message
 * @param schemaVersion version of the body schema
 * @param publishedAt ISO-8601 UTC instant
 * @param metadata routing data; {@code tenantId} is mandatory and lives only here
 * @param body JSON body of the event
 */
public record MessageEnvelope(String idempotencyId, String event, String origin, int schemaVersion,
		String publishedAt, Map<String, String> metadata, String body) {

	public MessageEnvelope {
		metadata = Map.copyOf(metadata);
	}

}
