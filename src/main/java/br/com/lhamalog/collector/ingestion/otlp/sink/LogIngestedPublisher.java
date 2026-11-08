// Author: LhamaLog Desenvolvimento (IA) - LLT-182
package br.com.lhamalog.collector.ingestion.otlp.sink;

import io.opentelemetry.proto.logs.v1.LogRecord;
import br.com.lhamalog.collector.messaging.MessagingProperties;
import com.google.protobuf.ByteString;
import java.time.Clock;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes {@code log.ingested} references to {@code lhamalog:stream:logs} (LLT-182). Schema 1: the body
 * points to the batch in OpenObserve instead of carrying the log. Limit override:
 * {@code LHAMALOG_MESSAGING_STREAM_LOGS_MAX_LEN}.
 */
public class LogIngestedPublisher extends TelemetryIngestedPublisher<LogRecord> {

	public static final String DOMAIN = "logs";

	public static final String EVENT = "log.ingested";

	public static final int SCHEMA_VERSION = 1;

	public LogIngestedPublisher(StringRedisTemplate redis, JsonMapper json, MessagingProperties properties,
			Clock clock) {
		super(redis, json, properties, clock);
	}

	@Override
	protected String domain() {
		return DOMAIN;
	}

	@Override
	protected String event() {
		return EVENT;
	}

	@Override
	protected int schemaVersion() {
		return SCHEMA_VERSION;
	}

	@Override
	protected int severityNumber(LogRecord record) {
		return record.getSeverityNumberValue();
	}

	@Override
	protected String severityText(LogRecord record) {
		return record.getSeverityText();
	}

	@Override
	protected ByteString traceId(LogRecord record) {
		return record.getTraceId();
	}

}
