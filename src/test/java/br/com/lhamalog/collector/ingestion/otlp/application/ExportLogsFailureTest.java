// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.application;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidationResult;
import br.com.lhamalog.collector.ingestion.otlp.domain.IngestionDecision;
import br.com.lhamalog.collector.ingestion.otlp.sink.LogsSink;
import br.com.lhamalog.collector.ingestion.otlp.sink.SinkUnavailableException;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ExportLogsFailureTest {

	private static final LogRecord VALID = LogRecord.newBuilder().setSeverityNumberValue(9).build();

	private static final LogRecord INVALID = LogRecord.newBuilder().setTraceId(ByteString.copyFromUtf8("short")).build();

	private final List<LogRecord> delivered = new ArrayList<>();

	private boolean sinkCalled;

	private final LogsSink recordingSink = (tenant, product, records) -> {
		sinkCalled = true;
		delivered.addAll(records);
	};

	private static ExportLogsServiceRequest batch(LogRecord... records) {
		return ExportLogsServiceRequest.newBuilder()
			.addResourceLogs(ResourceLogs.newBuilder().addScopeLogs(ScopeLogs.newBuilder().addAllLogRecords(List.of(records))))
			.build();
	}

	private static ExportLogsService service(TokenValidationResult auth, LogsSink sink) {
		return new ExportLogsService(token -> auth, sink);
	}

	private static final TokenValidationResult VALID_TOKEN = new TokenValidationResult.Valid("tenant-1", "product-a");

	@Test
	void unauthorizedNeitherReadsTheBodyNorCallsTheSink() {
		AtomicBoolean bodyRead = new AtomicBoolean();
		IngestionDecision decision = service(new TokenValidationResult.Unauthorized(), recordingSink).export("x", () -> {
			bodyRead.set(true);
			return batch(VALID);
		});
		assertThat(decision).isInstanceOf(IngestionDecision.Unauthorized.class);
		assertThat(bodyRead).isFalse();
		assertThat(sinkCalled).isFalse();
	}

	@Test
	void catalogFailureIsCatalogUnavailable() {
		IngestionDecision decision = service(new TokenValidationResult.CatalogUnavailable(new RuntimeException()), recordingSink)
			.export("x", () -> batch(VALID));
		assertThat(decision).isInstanceOf(IngestionDecision.CatalogUnavailable.class);
		assertThat(sinkCalled).isFalse();
	}

	@Test
	void mixedBatchDeliversOnlyValidRecords() {
		IngestionDecision decision = service(VALID_TOKEN, recordingSink).export("x", () -> batch(VALID, INVALID, VALID));
		assertThat(decision).isInstanceOfSatisfying(IngestionDecision.PartiallyAccepted.class, p -> {
			assertThat(p.acceptedCount()).isEqualTo(2);
			assertThat(p.rejectedCount()).isEqualTo(1);
			assertThat(p.errorMessage()).contains("1 log records rejected");
		});
		assertThat(delivered).containsExactly(VALID, VALID);
	}

	@Test
	void allInvalidIsInvalidPayloadNotPartialSuccess() {
		IngestionDecision decision = service(VALID_TOKEN, recordingSink).export("x", () -> batch(INVALID, INVALID));
		assertThat(decision).isInstanceOfSatisfying(IngestionDecision.InvalidPayload.class,
				p -> assertThat(p.reason()).isEqualTo(IngestionDecision.Reason.ALL_RECORDS_INVALID));
		assertThat(sinkCalled).isFalse();
	}

	@Test
	void unreadableBodyIsInvalidPayload() {
		IngestionDecision decision = service(VALID_TOKEN, recordingSink).export("x", () -> {
			throw new PayloadRejectedException(IngestionDecision.Reason.MALFORMED, "bad body");
		});
		assertThat(decision).isEqualTo(new IngestionDecision.InvalidPayload(IngestionDecision.Reason.MALFORMED, "bad body"));
		assertThat(sinkCalled).isFalse();
	}

	@Test
	void destinationFailureIsDestinationUnavailableEvenWithRejectedRecords() {
		LogsSink failing = (tenant, product, records) -> {
			throw new SinkUnavailableException("down", 7, null);
		};
		IngestionDecision decision = service(VALID_TOKEN, failing).export("x", () -> batch(VALID, INVALID));
		assertThat(decision).isEqualTo(new IngestionDecision.DestinationUnavailable(7));
	}

	@Test
	void emptyBatchSucceedsWithoutSink() {
		IngestionDecision decision = service(VALID_TOKEN, recordingSink).export("x", ExportLogsServiceRequest::getDefaultInstance);
		assertThat(decision).isInstanceOf(IngestionDecision.EmptySuccess.class);
		assertThat(sinkCalled).isFalse();
	}

	@Test
	void sinkReceivesTenantAndProductOfTheToken() {
		List<String> ids = new ArrayList<>();
		LogsSink sink = (tenant, product, records) -> {
			ids.add(tenant);
			ids.add(product);
		};
		IngestionDecision decision = service(VALID_TOKEN, sink).export("x", () -> batch(VALID));
		assertThat(decision).isEqualTo(new IngestionDecision.Accepted(1));
		assertThat(ids).containsExactly("tenant-1", "product-a");
	}

}
