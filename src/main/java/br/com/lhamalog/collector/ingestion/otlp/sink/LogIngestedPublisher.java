// Author: LhamaLog Desenvolvimento (IA) - LLT-182
package br.com.lhamalog.collector.ingestion.otlp.sink;

import br.com.lhamalog.collector.ingestion.otlp.domain.Scoped;
import br.com.lhamalog.collector.messaging.AbstractStreamPublisher;
import br.com.lhamalog.collector.messaging.MessageEnvelope;
import br.com.lhamalog.collector.messaging.MessagingProperties;
import br.com.lhamalog.collector.messaging.MetadataKeys;
import br.com.lhamalog.collector.messaging.OutboundMessage;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes accepted logs as {@code log.ingested} events to {@code lhamalog:stream:logs} for the Varredura
 * (LLT-182). Limit override: {@code LHAMALOG_MESSAGING_STREAM_LOGS_MAX_LEN}.
 *
 * <p>The {@code idempotencyId} is a name based UUID (version 3) over tenantId, productId, the serialized
 * resource, scope and log record, and the occurrence number of an identical record within the batch. A client
 * retry of the same batch therefore yields the same ids, while two identical logs in one batch stay distinct.
 */
public class LogIngestedPublisher extends AbstractStreamPublisher<LogIngestedBody> {

	public static final String DOMAIN = "logs";

	public static final String EVENT = "log.ingested";

	public static final String ORIGIN = "collector";

	public static final int SCHEMA_VERSION = 1;

	private final Clock clock;

	public LogIngestedPublisher(StringRedisTemplate redis, JsonMapper json, MessagingProperties properties,
			Clock clock) {
		super(redis, json, properties, clock);
		this.clock = clock;
	}

	@Override
	protected String domain() {
		return DOMAIN;
	}

	@Override
	protected String origin() {
		return ORIGIN;
	}

	@Override
	protected String event() {
		return EVENT;
	}

	@Override
	protected int schemaVersion() {
		return SCHEMA_VERSION;
	}

	public List<MessageEnvelope> publishLogs(String tenantId, String productId, List<Scoped<LogRecord>> records) {
		return publish(messages(tenantId, productId, records, UUID.randomUUID().toString(), clock.instant().toString()));
	}

	static List<OutboundMessage<LogIngestedBody>> messages(String tenantId, String productId,
			List<Scoped<LogRecord>> records, String correlationId, String receivedAt) {
		List<OutboundMessage<LogIngestedBody>> messages = new ArrayList<>(records.size());
		Map<UUID, Integer> occurrences = new HashMap<>();
		for (Scoped<LogRecord> scoped : records) {
			UUID content = contentId(tenantId, productId, scoped);
			int occurrence = occurrences.merge(content, 1, Integer::sum);
			String idempotencyId = UUID
				.nameUUIDFromBytes((content + "#" + occurrence).getBytes(StandardCharsets.UTF_8))
				.toString();
			Map<String, String> metadata = new LinkedHashMap<>();
			metadata.put(MetadataKeys.TENANT_ID, tenantId);
			metadata.put(MetadataKeys.PRODUCT_ID, productId);
			metadata.put(MetadataKeys.CORRELATION_ID, correlationId);
			if (!scoped.record().getTraceId().isEmpty()) {
				metadata.put(MetadataKeys.TRACE_ID, OtlpValues.hex(scoped.record().getTraceId()));
			}
			messages.add(new OutboundMessage<>(idempotencyId, body(scoped, receivedAt), metadata));
		}
		return messages;
	}

	private static UUID contentId(String tenantId, String productId, Scoped<LogRecord> scoped) {
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

	private static LogIngestedBody body(Scoped<LogRecord> scoped, String receivedAt) {
		LogRecord log = scoped.record();
		String serviceName = null;
		for (KeyValue attribute : scoped.resource().getAttributesList()) {
			if (OtlpValues.SERVICE_NAME.equals(attribute.getKey())
					&& attribute.getValue().getValueCase() == AnyValue.ValueCase.STRING_VALUE) {
				serviceName = attribute.getValue().getStringValue();
			}
		}
		return new LogIngestedBody(receivedAt, nanos(log.getTimeUnixNano()), nanos(log.getObservedTimeUnixNano()),
				log.getSeverityNumberValue(), emptyToNull(log.getSeverityText()),
				log.hasBody() ? OtlpValues.text(log.getBody()) : null, OtlpValues.toMap(log.getAttributesList()),
				hexOrNull(log.getTraceId()), hexOrNull(log.getSpanId()), Integer.toUnsignedLong(log.getFlags()),
				new LogIngestedBody.Resource(serviceName, OtlpValues.toMap(scoped.resource().getAttributesList())),
				new LogIngestedBody.Scope(emptyToNull(scoped.scope().getName()),
						emptyToNull(scoped.scope().getVersion()),
						OtlpValues.toMap(scoped.scope().getAttributesList())));
	}

	private static String nanos(long value) {
		return value == 0 ? null : Long.toUnsignedString(value);
	}

	private static String hexOrNull(ByteString bytes) {
		return bytes.isEmpty() ? null : OtlpValues.hex(bytes);
	}

	private static String emptyToNull(String value) {
		return value.isEmpty() ? null : value;
	}

}
