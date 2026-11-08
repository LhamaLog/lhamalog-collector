// Author: LhamaLog Desenvolvimento (IA) - LLT-182
package br.com.lhamalog.collector.ingestion.otlp.sink;

import br.com.lhamalog.collector.ingestion.otlp.domain.Scoped;
import com.google.protobuf.MessageLite;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic ids written to OpenObserve and to the stream. The record id is a name based UUID (version 3)
 * over tenantId, productId, the serialized resource, scope and record, plus the occurrence number of an
 * identical record in the batch: a client retry yields the same ids and two identical records stay distinct.
 * The batch id is a version 3 UUID over the signal and the record ids, so a retry also keeps the batch id.
 */
final class RecordIds {

	private RecordIds() {
	}

	static List<String> of(String tenantId, String productId, List<? extends Scoped<? extends MessageLite>> records) {
		List<String> ids = new ArrayList<>(records.size());
		Map<UUID, Integer> occurrences = new HashMap<>();
		for (Scoped<? extends MessageLite> scoped : records) {
			UUID content = contentId(tenantId, productId, scoped);
			int occurrence = occurrences.merge(content, 1, Integer::sum);
			ids.add(UUID.nameUUIDFromBytes((content + "#" + occurrence).getBytes(StandardCharsets.UTF_8)).toString());
		}
		return ids;
	}

	static String batchId(Signal signal, List<String> recordIds) {
		return UUID.nameUUIDFromBytes((signal + ":" + String.join(",", recordIds)).getBytes(StandardCharsets.UTF_8))
			.toString();
	}

	private static UUID contentId(String tenantId, String productId, Scoped<? extends MessageLite> scoped) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		for (byte[] part : List.of(tenantId.getBytes(StandardCharsets.UTF_8),
				productId.getBytes(StandardCharsets.UTF_8), scoped.resource().toByteArray(),
				scoped.scope().toByteArray(), scoped.record().toByteArray())) {
			bytes.writeBytes(Integer.toString(part.length).getBytes(StandardCharsets.US_ASCII));
			bytes.write(':');
			bytes.writeBytes(part);
		}
		return UUID.nameUUIDFromBytes(bytes.toByteArray());
	}

}
