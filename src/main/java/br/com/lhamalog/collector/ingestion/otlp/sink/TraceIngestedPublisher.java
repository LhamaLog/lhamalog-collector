// Author: LhamaLog Desenvolvimento (IA) - LLT-182
package br.com.lhamalog.collector.ingestion.otlp.sink;

import br.com.lhamalog.collector.messaging.MessagingProperties;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.trace.v1.Span;
import java.time.Clock;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes {@code trace.ingested} references to {@code lhamalog:stream:traces} (LLT-182). Spans carry no
 * severity, so criticality is UNSPECIFIED and priority LOW. Limit override:
 * {@code LHAMALOG_MESSAGING_STREAM_TRACES_MAX_LEN}.
 */
public class TraceIngestedPublisher extends TelemetryIngestedPublisher<Span> {

	public static final String DOMAIN = "traces";

	public static final String EVENT = "trace.ingested";

	public static final int SCHEMA_VERSION = 1;

	public TraceIngestedPublisher(StringRedisTemplate redis, JsonMapper json, MessagingProperties properties,
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
	protected int severityNumber(Span record) {
		return 0;
	}

	@Override
	protected String severityText(Span record) {
		return "";
	}

	@Override
	protected ByteString traceId(Span record) {
		return record.getTraceId();
	}

}
