// Author: LhamaLog Desenvolvimento (IA) - LLT-182
package br.com.lhamalog.collector.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publisher side of the Redis Streams messaging (ADR draft, reused from the messaging POC). A subclass names
 * its domain, origin, event and schema version; {@link #publish(List)} is final so every stream gets the same
 * envelope and the same trimming: {@code XADD <stream> MAXLEN ~ <limit> * field value ...}.
 *
 * <p>All entries of one call go in a single pipeline. If Redis does not confirm every entry the call throws
 * {@link MessagePublishException}; entries already written stay, and a retry writes them again with the same
 * {@code idempotencyId}, which the consumer drops.
 */
public abstract class AbstractStreamPublisher<T> {

	private final StringRedisTemplate redis;

	private final JsonMapper json;

	private final Clock clock;

	private final String stream;

	private final long maxLen;

	protected AbstractStreamPublisher(StringRedisTemplate redis, JsonMapper json, MessagingProperties properties,
			Clock clock) {
		this.redis = redis;
		this.json = json;
		this.clock = clock;
		this.stream = StreamNames.stream(domain());
		this.maxLen = properties.maxLenFor(domain());
	}

	/** Domain of the stream, e.g. {@code logs} for {@code lhamalog:stream:logs}. */
	protected abstract String domain();

	protected abstract String origin();

	protected abstract String event();

	protected abstract int schemaVersion();

	public final String stream() {
		return stream;
	}

	public final long maxLen() {
		return maxLen;
	}

	public final List<MessageEnvelope> publish(List<OutboundMessage<T>> messages) {
		if (messages.isEmpty()) {
			return List.of();
		}
		String publishedAt = DateTimeFormatter.ISO_INSTANT.format(clock.instant());
		List<MessageEnvelope> envelopes = new ArrayList<>(messages.size());
		for (OutboundMessage<T> message : messages) {
			envelopes.add(new MessageEnvelope(message.idempotencyId(), event(), origin(), schemaVersion(), publishedAt,
					message.metadata(), toJson(message.body())));
		}
		List<Map<String, String>> entries = envelopes.stream().map(this::fields).toList();
		XAddOptions options = XAddOptions.maxlen(maxLen).approximateTrimming(true);
		List<Object> ids;
		try {
			ids = redis.executePipelined((RedisCallback<Object>) connection -> {
				byte[] key = stream.getBytes(StandardCharsets.UTF_8);
				for (Map<String, String> entry : entries) {
					Map<byte[], byte[]> raw = new LinkedHashMap<>();
					entry.forEach((field, value) -> raw.put(field.getBytes(StandardCharsets.UTF_8),
							value.getBytes(StandardCharsets.UTF_8)));
					connection.streamCommands().xAdd(StreamRecords.rawBytes(raw).withStreamKey(key), options);
				}
				return null;
			});
		}
		catch (DataAccessException ex) {
			throw new MessagePublishException("Redis did not accept the entries for " + stream, ex);
		}
		if (ids.size() != entries.size() || ids.stream().anyMatch(id -> id == null || id instanceof Throwable)) {
			throw new MessagePublishException(
					"Redis confirmed " + ids.stream().filter(id -> id != null && !(id instanceof Throwable)).count()
							+ " of " + entries.size() + " entries for " + stream,
					null);
		}
		return List.copyOf(envelopes);
	}

	private Map<String, String> fields(MessageEnvelope envelope) {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("idempotencyId", envelope.idempotencyId());
		fields.put("event", envelope.event());
		fields.put("origin", envelope.origin());
		fields.put("schemaVersion", Integer.toString(envelope.schemaVersion()));
		fields.put("publishedAt", envelope.publishedAt());
		fields.put("metadata", toJson(new LinkedHashMap<>(envelope.metadata())));
		fields.put("body", envelope.body());
		return fields;
	}

	private String toJson(Object value) {
		try {
			return json.writeValueAsString(value);
		}
		catch (JacksonException ex) {
			throw new IllegalArgumentException("message is not serializable to JSON", ex);
		}
	}

}
