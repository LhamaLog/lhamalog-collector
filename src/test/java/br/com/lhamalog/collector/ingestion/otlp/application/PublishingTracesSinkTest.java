// Author: LhamaLog Desenvolvimento (IA) - LLT-182
package br.com.lhamalog.collector.ingestion.otlp.application;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidationResult;
import br.com.lhamalog.collector.ingestion.otlp.domain.IngestionDecision;
import br.com.lhamalog.collector.ingestion.otlp.sink.BatchStore;
import br.com.lhamalog.collector.ingestion.otlp.sink.PublishingTracesSink;
import br.com.lhamalog.collector.ingestion.otlp.sink.Signal;
import br.com.lhamalog.collector.ingestion.otlp.sink.SinkRejectedException;
import br.com.lhamalog.collector.ingestion.otlp.sink.SinkUnavailableException;
import br.com.lhamalog.collector.ingestion.otlp.sink.StoredBatch;
import br.com.lhamalog.collector.messaging.MessagePublishException;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** LLT-182: traces follow the same order as logs, OpenObserve then queue, and Redis down is retryable. */
class PublishingTracesSinkTest {

	private static final Span VALID = Span.newBuilder()
		.setTraceId(ByteString.fromHex("5b8efff798038103d269b633813fc60c"))
		.setSpanId(ByteString.fromHex("eee19b7ec3c1b174"))
		.setName("GET /")
		.build();

	private static final Span INVALID = VALID.toBuilder().setTraceId(ByteString.copyFromUtf8("short")).build();

	private static final TokenValidationResult VALID_TOKEN = new TokenValidationResult.Valid("tenant-1", "product-a");

	private static final StoredBatch STORED = new StoredBatch(Signal.TRACES, "lhamalog", "traces", "batch-1",
			List.of("record-1"), 1L, 1L, "2026-11-01T12:00:00Z");

	private final List<String> calls = new ArrayList<>();

	private final List<Span> published = new ArrayList<>();

	private final BatchStore<Span> storing = (tenant, product, records) -> {
		calls.add("store");
		return STORED;
	};

	private final PublishingTracesSink.Publisher recording = (tenant, product, batch, records) -> {
		calls.add("publish:" + tenant + "/" + product + "/" + batch.batchId());
		records.forEach(scoped -> published.add(scoped.record()));
	};

	private static ExportTraceServiceRequest batch(Span... spans) {
		return ExportTraceServiceRequest.newBuilder()
			.addResourceSpans(ResourceSpans.newBuilder().addScopeSpans(ScopeSpans.newBuilder().addAllSpans(List.of(spans))))
			.build();
	}

	private static IngestionDecision export(TokenValidationResult auth, PublishingTracesSink sink,
			ExportTraceServiceRequest request) {
		return new ExportTracesService(token -> auth, sink).export("x", () -> request);
	}

	@Test
	void storesThenPublishesOnlyValidSpansWithTokenIds() {
		IngestionDecision decision = export(VALID_TOKEN, new PublishingTracesSink(storing, recording), batch(VALID, INVALID));
		assertThat(decision).isInstanceOf(IngestionDecision.PartiallyAccepted.class);
		assertThat(calls).containsExactly("store", "publish:tenant-1/product-a/batch-1");
		assertThat(published).containsExactly(VALID);
	}

	@Test
	void unauthorizedOrInvalidBatchNeverPublishes() {
		export(new TokenValidationResult.Unauthorized(), new PublishingTracesSink(storing, recording), batch(VALID));
		assertThat(export(VALID_TOKEN, new PublishingTracesSink(storing, recording), batch(INVALID)))
			.isInstanceOf(IngestionDecision.InvalidPayload.class);
		assertThat(calls).isEmpty();
	}

	@Test
	void openObserveFailureOrRejectionDoesNotPublish() {
		BatchStore<Span> down = (tenant, product, records) -> {
			throw new SinkUnavailableException("down", 7, null);
		};
		BatchStore<Span> refusing = (tenant, product, records) -> {
			throw new SinkRejectedException(1, 0, "refused");
		};
		assertThat(export(VALID_TOKEN, new PublishingTracesSink(down, recording), batch(VALID)))
			.isEqualTo(new IngestionDecision.DestinationUnavailable(7));
		export(VALID_TOKEN, new PublishingTracesSink(refusing, recording), batch(VALID));
		assertThat(published).isEmpty();
	}

	@Test
	void redisFailureIsRetryableDestinationUnavailable() {
		PublishingTracesSink.Publisher failing = (tenant, product, batch, records) -> {
			throw new MessagePublishException("redis down", null);
		};
		IngestionDecision decision = export(VALID_TOKEN, new PublishingTracesSink(storing, failing), batch(VALID));
		assertThat(decision).isEqualTo(
				new IngestionDecision.DestinationUnavailable(SinkUnavailableException.DEFAULT_RETRY_AFTER_SECONDS));
		assertThat(calls).containsExactly("store");
	}

}