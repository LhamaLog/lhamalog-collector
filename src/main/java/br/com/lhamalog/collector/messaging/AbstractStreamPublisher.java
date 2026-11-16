// Author: LhamaLog Desenvolvimento (IA) - LLT-184
package br.com.lhamalog.collector.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publisher side of the Redis Streams messaging (ADR draft). A subclass names its domain, origin, event and
 * schema version; {@link #publish(List)} is final so every stream gets the same envelope, trimming, internal
 * retry and DLQ policy (LLT-184).
 *
 * <p>All entries of one call go in a single pipeline. Transient Redis failures are retried with backoff. When
 * attempts are exhausted the envelopes are written to the domain DLQ; a confirmed DLQ write counts as publish
 * success for the caller. Only when the DLQ also fails does this throw {@link MessagePublishException}.
 */
public abstract class AbstractStreamPublisher<T> {

	private static final Logger log = LoggerFactory.getLogger(AbstractStreamPublisher.class);

	protected final StringRedisTemplate redis;

	private final JsonMapper json;

	private final Clock clock;

	private final MessagingProperties properties;

	private final PublishSleeper sleeper;

	protected final String stream;

	protected final long maxLen;

	protected final String dlqStream;

	protected final long dlqMaxLen;

	protected AbstractStreamPublisher(StringRedisTemplate redis, JsonMapper json, MessagingProperties properties,
			Clock clock) {
		this(redis, json, properties, clock, Thread::sleep);
	}

	protected AbstractStreamPublisher(StringRedisTemplate redis, JsonMapper json, MessagingProperties properties,
			Clock clock, PublishSleeper sleeper) {
		this.redis = redis;
		this.json = json;
		this.clock = clock;
		this.properties = properties;
		this.sleeper = sleeper;
		this.stream = StreamNames.stream(domain());
		this.maxLen = properties.maxLenFor(domain());
		this.dlqStream = StreamNames.dlq(domain());
		this.dlqMaxLen = properties.maxLenFor(domain() + ":dlq");
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

	public final String dlqStream() {
		return dlqStream;
	}

	public final long dlqMaxLen() {
		return dlqMaxLen;
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
		int maxAttempts = properties.publishMaxAttempts();
		long backoffMs = properties.publishBackoffInitialMs();
		double multiplier = properties.publishBackoffMultiplier();
		long ceilingMs = properties.publishBackoffCeilingMs();
		long waitedMs = 0L;
		MessagePublishException lastFailure = null;
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				xadd(stream, maxLen, entries);
				if (attempt > 1) {
					log.info("Published to {} after {} attempts", stream, attempt);
				}
				return List.copyOf(envelopes);
			}
			catch (MessagePublishException ex) {
				lastFailure = ex;
				log.warn("Publish attempt {}/{} failed for {}: {}", attempt, maxAttempts, stream, formatError(ex));
				if (attempt == maxAttempts) {
					break;
				}
				long sleepMs = Math.min(backoffMs, Math.max(0L, ceilingMs - waitedMs));
				if (sleepMs > 0L) {
					try {
						sleeper.sleep(sleepMs);
					}
					catch (InterruptedException interrupted) {
						Thread.currentThread().interrupt();
						throw new MessagePublishException("Interrupted while backing off before retry for " + stream,
								interrupted);
					}
					waitedMs += sleepMs;
				}
				long next = (long) Math.ceil(backoffMs * multiplier);
				backoffMs = Math.max(1L, next);
			}
		}
		try {
			writeDlq(envelopes, maxAttempts, lastFailure);
			log.error("Moved {} message(s) to DLQ {} after {} failed attempts to {}", envelopes.size(), dlqStream,
					maxAttempts, stream);
			return List.copyOf(envelopes);
		}
		catch (MessagePublishException dlqFailure) {
			log.error("DLQ write failed for {} after {} attempts to {}: {}", dlqStream, maxAttempts, stream,
					formatError(dlqFailure));
			throw new MessagePublishException(
					"Redis did not accept the entries for " + stream + " and DLQ " + dlqStream, dlqFailure);
		}
	}

	/**
	 * Appends one message and records its immutable acceptance evidence in the
	 * same Redis Lua invocation. This is intentionally separate from
	 * {@link #publish(List)}, which retains the legacy retry/DLQ behaviour for
	 * messages that do not participate in the usage outbox protocol.
	 *
	 * @return {@code DONE:*}, {@code CLAIMED:*}, or {@code ABANDONED}
	 */
	protected final String publishWithUsageEvidence(OutboundMessage<T> message, String evidenceKey, String route,
			long evidenceTtlSeconds) {
		return publishWithUsageEvidence(message, evidenceKey, route, evidenceTtlSeconds, stream, maxLen);
	}

	protected final String publishWithUsageEvidence(OutboundMessage<T> message, String evidenceKey, String route,
			long evidenceTtlSeconds, String targetStream, long targetMaxLen) {
		if (evidenceTtlSeconds <= 0) {
			throw new IllegalArgumentException("evidenceTtlSeconds must be positive");
		}
		MessageEnvelope envelope = new MessageEnvelope(message.idempotencyId(), event(), origin(), schemaVersion(),
				DateTimeFormatter.ISO_INSTANT.format(clock.instant()), message.metadata(), toJson(message.body()));
		Map<String, String> fields = fields(envelope);
		StringBuilder script = new StringBuilder("""
				local current = redis.call('GET', KEYS[2])
				if current then
				  return current
				end
				redis.call('SET', KEYS[2], 'CLAIMED:' .. ARGV[1], 'EX', ARGV[2])
				local id = redis.call('XADD', KEYS[1], 'MAXLEN', '~', ARGV[3], '*'
				""");
		List<String> arguments = new ArrayList<>();
		arguments.add(java.util.UUID.randomUUID().toString());
		arguments.add(Long.toString(evidenceTtlSeconds));
		arguments.add(Long.toString(targetMaxLen));
		fields.forEach((field, value) -> {
			script.append(", ARGV[").append(arguments.size() + 1).append("], ARGV[")
				.append(arguments.size() + 2).append("]");
			arguments.add(field);
			arguments.add(value);
		});
		script.append(")\nredis.call('SET', KEYS[2], 'DONE:' .. ARGV[")
			.append(arguments.size() + 1).append("] .. ':' .. id, 'EX', ARGV[2])\n")
			.append("return redis.call('GET', KEYS[2])");
		arguments.add(route);
		DefaultRedisScript<String> lua = new DefaultRedisScript<>(script.toString(), String.class);
		return redis.execute(lua, List.of(targetStream, evidenceKey), arguments.toArray(String[]::new));
	}

	private void writeDlq(List<MessageEnvelope> envelopes, int attempts, MessagePublishException lastFailure) {
		String failedAt = DateTimeFormatter.ISO_INSTANT.format(clock.instant());
		String lastError = formatError(lastFailure);
		List<Map<String, String>> entries = new ArrayList<>(envelopes.size());
		for (MessageEnvelope envelope : envelopes) {
			Map<String, String> entry = new LinkedHashMap<>(fields(envelope));
			entry.put("targetStream", stream);
			entry.put("attempts", Integer.toString(attempts));
			entry.put("lastError", lastError);
			entry.put("failedAt", failedAt);
			entries.add(entry);
		}
		xadd(dlqStream, dlqMaxLen, entries);
	}

	private void xadd(String target, long maxlen, List<Map<String, String>> entries) {
		XAddOptions options = XAddOptions.maxlen(maxlen).approximateTrimming(true);
		List<Object> ids;
		try {
			ids = redis.executePipelined((RedisCallback<Object>) connection -> {
				byte[] key = target.getBytes(StandardCharsets.UTF_8);
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
			throw new MessagePublishException("Redis did not accept the entries for " + target, ex);
		}
		if (ids == null || ids.size() != entries.size()
				|| ids.stream().anyMatch(id -> id == null || id instanceof Throwable)) {
			long confirmed = ids == null ? 0L
					: ids.stream().filter(id -> id != null && !(id instanceof Throwable)).count();
			throw new MessagePublishException(
					"Redis confirmed " + confirmed + " of " + entries.size() + " entries for " + target, null);
		}
	}

	protected Map<String, String> fields(MessageEnvelope envelope) {
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

	static String formatError(Throwable error) {
		if (error == null) {
			return "unknown";
		}
		String name = error.getClass().getSimpleName();
		String message = error.getMessage();
		if (message == null || message.isBlank()) {
			return name;
		}
		String sanitized = message
			.replaceAll("(?i)(\\b[a-z][a-z0-9+.-]*://[^\\s/@:]+:)[^@\\s]+@", "$1***@")
			.replaceAll(
					"(?i)(\\b(?:password|passwd|senha|secret|token|apikey|api_key|authorization)\\b[\"']?\\s*(?:=|:)\\s*)(?:\"(?:\\\\.|[^\"])*\"|'(?:\\\\.|[^'])*'|Bearer\\s+[^\\s,;]+|[^\\s,;}&]+)",
					"$1***")
			.replaceAll("(?i)\\bBearer\\s+[^\\s,;]+", "Bearer ***");
		return name + ": " + sanitized;
	}

	protected String toJson(Object value) {
		try {
			return json.writeValueAsString(value);
		}
		catch (JacksonException ex) {
			throw new IllegalArgumentException("message is not serializable to JSON", ex);
		}
	}

}