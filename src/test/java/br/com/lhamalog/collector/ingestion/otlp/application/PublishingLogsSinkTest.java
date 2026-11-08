// Author: LhamaLog Desenvolvimento (IA) - LLT-184
package br.com.lhamalog.collector.ingestion.otlp.application;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidationResult;
import br.com.lhamalog.collector.ingestion.otlp.domain.IngestionDecision;
import br.com.lhamalog.collector.ingestion.otlp.sink.BatchStore;
import br.com.lhamalog.collector.ingestion.otlp.sink.Signal;
import br.com.lhamalog.collector.ingestion.otlp.sink.StoredBatch;
import br.com.lhamalog.collector.ingestion.otlp.sink.PublishingLogsSink;
import br.com.lhamalog.collector.ingestion.otlp.sink.SinkRejectedException;
import br.com.lhamalog.collector.ingestion.otlp.sink.SinkUnavailableException;
import br.com.lhamalog.collector.messaging.MessagePublishException;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** LLT-182/184: order OpenObserve then queue; MessagePublishException (after retries+DLQ) is DestinationUnavailable. */
class PublishingLogsSinkTest {

	private static final LogRecord VALID = LogRecord.newBuilder().setSeverityNumberValue(9).build();

	private static final LogRecord INVALID = LogRecord.newBuilder().setTraceId(ByteString.copyFromUtf8("short")).build();

	private static final TokenValidationResult VALID_TOKEN = new TokenValidationResult.Valid("tenant-1", "product-a");

	private final List<String> calls = new ArrayList<>();

	private final List<LogRecord> published = new ArrayList<>();

	private static final StoredBatch STORED = new StoredBatch(Signal.LOGS, "lhamalog", "logs", "batch-1",
			List.of("record-1"), 1L, 1L, "2026-11-01T12:00:00Z");

	private final BatchStore<LogRecord> storing = (tenant, product, records) -> {
		calls.add("store");
		return STORED;
	};

	private final PublishingLogsSink.Publisher recording = (tenant, product, batch, records) -> {
		calls.add("publish:" + tenant + "/" + product + "/" + batch.batchId());
		records.forEach(scoped -> published.add(scoped.record()));
	};

	private static ExportLogsServiceRequest batch(LogRecord... records) {
		return ExportLogsServiceRequest.newBuilder()
			.addResourceLogs(ResourceLogs.newBuilder().addScopeLogs(ScopeLogs.newBuilder().addAllLogRecords(List.of(records))))
			.build();
	}

	private static IngestionDecision export(TokenValidationResult auth, PublishingLogsSink sink, ExportLogsServiceRequest request) {
		return new ExportLogsService(token -> auth, sink).export("x", () -> request);
	}

	@Test
	void storesThenPublishesOnlyValidRecordsWithTokenIds() {
		IngestionDecision decision = export(VALID_TOKEN, new PublishingLogsSink(storing, recording), batch(VALID, INVALID));
		assertThat(decision).isInstanceOf(IngestionDecision.PartiallyAccepted.class);
		assertThat(calls).containsExactly("store", "publish:tenant-1/product-a/batch-1");
		assertThat(published).containsExactly(VALID);
	}

	@Test
	void unauthorizedOrUnavailableCatalogNeverPublishes() {
		export(new TokenValidationResult.Unauthorized(), new PublishingLogsSink(storing, recording), batch(VALID));
		export(new TokenValidationResult.CatalogUnavailable(new RuntimeException()),
				new PublishingLogsSink(storing, recording), batch(VALID));
		assertThat(calls).isEmpty();
	}

	@Test
	void invalidBatchNeverPublishes() {
		IngestionDecision decision = export(VALID_TOKEN, new PublishingLogsSink(storing, recording), batch(INVALID, INVALID));
		assertThat(decision).isInstanceOf(IngestionDecision.InvalidPayload.class);
		assertThat(calls).isEmpty();
	}

	@Test
	void openObserveFailureOrRejectionDoesNotPublish() {
		BatchStore<LogRecord> down = (tenant, product, records) -> {
			throw new SinkUnavailableException("down", 7, null);
		};
		BatchStore<LogRecord> refusing = (tenant, product, records) -> {
			throw new SinkRejectedException(1, 0, "refused");
		};
		assertThat(export(VALID_TOKEN, new PublishingLogsSink(down, recording), batch(VALID)))
			.isEqualTo(new IngestionDecision.DestinationUnavailable(7));
		export(VALID_TOKEN, new PublishingLogsSink(refusing, recording), batch(VALID));
		assertThat(published).isEmpty();
	}

	@Test
	void ultimatePublishFailureIsRetryableDestinationUnavailable() {
		PublishingLogsSink.Publisher failing = (tenant, product, batch, records) -> {
			throw new MessagePublishException("redis down", null);
		};
		IngestionDecision decision = export(VALID_TOKEN, new PublishingLogsSink(storing, failing), batch(VALID));
		assertThat(decision).isEqualTo(
				new IngestionDecision.DestinationUnavailable(SinkUnavailableException.DEFAULT_RETRY_AFTER_SECONDS));
		assertThat(calls).containsExactly("store");
	}

	@Test
	void successfulPublishAfterStoreIsAccepted() {
		IngestionDecision decision = export(VALID_TOKEN, new PublishingLogsSink(storing, recording), batch(VALID));
		assertThat(decision).isInstanceOf(IngestionDecision.Accepted.class);
		assertThat(calls).containsExactly("store", "publish:tenant-1/product-a/batch-1");
	}

}