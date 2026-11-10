package br.com.lhamalog.collector.ingestion.otlp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.lhamalog.collector.ingestion.otlp.application.ExportLogsService;
import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidationResult;
import br.com.lhamalog.collector.ingestion.otlp.domain.Scoped;
import br.com.lhamalog.collector.ingestion.otlp.domain.IngestionDecision;
import br.com.lhamalog.collector.ingestion.otlp.sink.BatchStore;
import br.com.lhamalog.collector.ingestion.otlp.sink.LogIngestedPublisher;
import br.com.lhamalog.collector.ingestion.otlp.sink.PublishingLogsSink;
import br.com.lhamalog.collector.ingestion.otlp.sink.Signal;
import br.com.lhamalog.collector.ingestion.otlp.sink.StoredBatch;
import br.com.lhamalog.collector.messaging.MessagePublishException;
import br.com.lhamalog.collector.messaging.MessagingProperties;
import br.com.lhamalog.collector.usage.domain.UsageDomain;
import br.com.lhamalog.collector.usage.domain.UsageFact;
import br.com.lhamalog.collector.usage.domain.UsageFactFactory;
import br.com.lhamalog.collector.usage.outbox.UsageAcceptanceEvidence;
import br.com.lhamalog.collector.usage.outbox.UsageAcceptanceReconciler;
import br.com.lhamalog.collector.usage.outbox.UsageFactRecorder;
import br.com.lhamalog.collector.usage.outbox.UsageOutboxItem;
import br.com.lhamalog.collector.usage.outbox.UsageOutboxRepository;
import br.com.lhamalog.collector.usage.outbox.UsageOutboxState;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import tools.jackson.databind.json.JsonMapper;

/**
 * Compact crash matrix for the Redis evidence/Mongo acceptance boundary.
 * Transport equivalence is covered separately by UsageMeasurementTransportAcceptanceTest.
 */
class UsageOutboxFailureAcceptanceTest {

	private static final Instant NOW = Instant.parse("2026-11-09T00:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	private static final LogRecord LOG = LogRecord.newBuilder().setSeverityNumberValue(9)
		.setBody(io.opentelemetry.proto.common.v1.AnyValue.newBuilder().setStringValue("usage")).build();

	@Test
	void xaddFailureLeavesClaimedWithoutAStreamEntry() {
		Outbox outbox = new Outbox();
		ConcurrentHashMap<String, String> evidence = new ConcurrentHashMap<>();
		AtomicInteger streamEntries = new AtomicInteger();
		IngestionDecision decision = export(outbox, (tenant, product, batch, records, key) -> {
			evidence.put(key, "CLAIMED:dispatcher");
			throw new MessagePublishException("XADD failed", null);
		});

		assertThat(decision).isInstanceOf(IngestionDecision.DestinationUnavailable.class);
		assertThat(outbox.only().state()).isEqualTo(UsageOutboxState.PENDING);
		assertThat(streamEntries).hasValue(0);
		assertThat(evidence.get(outbox.only().acceptedEvidenceKey())).startsWith("CLAIMED:");
	}

	@Test
	void failureAfterXaddLeavesClaimedAndReconcilesAsAmbiguous() {
		Outbox outbox = new Outbox();
		ConcurrentHashMap<String, String> evidence = new ConcurrentHashMap<>();
		AtomicInteger streamEntries = new AtomicInteger();
		IngestionDecision decision = export(outbox, (tenant, product, batch, records, key) -> {
			evidence.put(key, "CLAIMED:dispatcher");
			streamEntries.incrementAndGet();
			throw new MessagePublishException("SET DONE failed after XADD", null);
		});

		assertThat(decision).isInstanceOf(IngestionDecision.DestinationUnavailable.class);
		assertThat(streamEntries).hasValue(1);
		new UsageAcceptanceReconciler(outbox, redis(evidence), CLOCK).reconcilePendingBefore(NOW);
		assertThat(evidence.get(outbox.only().acceptedEvidenceKey())).startsWith("CLAIMED:");
		assertThat(outbox.only().state()).isEqualTo(UsageOutboxState.AMBIGUOUS);
	}

	@Test
	void doneBeforeMongoCasReturnsSuccessAndReconcilerAcceptsTheCandidate() {
		Outbox outbox = new Outbox();
		outbox.rejectNextPendingCas.set(true);
		ConcurrentHashMap<String, String> evidence = new ConcurrentHashMap<>();

		assertThat(export(outbox, (tenant, product, batch, records, key) -> {
			evidence.put(key, "DONE:logs:1-0");
			return "DONE:logs:1-0";
		})).isInstanceOf(IngestionDecision.Accepted.class);
		assertThat(outbox.only().state()).isEqualTo(UsageOutboxState.PENDING);

		new UsageAcceptanceReconciler(outbox, redis(evidence), CLOCK).reconcilePendingBefore(NOW);
		assertThat(outbox.only().state()).isEqualTo(UsageOutboxState.ACCEPTED);
	}

	@Test
	void reconcilerAbandonsBeforeSlowDispatcherAndFencesItsLaterXadd() {
		Outbox outbox = new Outbox();
		UsageFact fact = fact("slow");
		UsageOutboxItem item = outbox.insertPending(fact, NOW.minusSeconds(1));
		ConcurrentHashMap<String, String> evidence = new ConcurrentHashMap<>();
		new UsageAcceptanceReconciler(outbox, redis(evidence), CLOCK).reconcilePendingBefore(NOW);
		AtomicInteger xadds = new AtomicInteger();

		String result = publishOnce(evidence, item.acceptedEvidenceKey(), xadds, "logs");

		assertThat(result).isEqualTo("ABANDONED");
		assertThat(xadds).hasValue(0);
		assertThat(outbox.only().state()).isEqualTo(UsageOutboxState.ABANDONED);
	}

	@Test
	void concurrentRetryUsesOneXaddAndBothSeeTheSameDoneEvidence() throws Exception {
		ConcurrentHashMap<String, String> evidence = new ConcurrentHashMap<>();
		AtomicInteger xadds = new AtomicInteger();
		String key = "usage:fact:concurrent";
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			var first = executor.submit(() -> { start.await(); return publishOnce(evidence, key, xadds, "logs"); });
			var second = executor.submit(() -> { start.await(); return publishOnce(evidence, key, xadds, "logs"); });
			start.countDown();
			assertThat(first.get(5, TimeUnit.SECONDS)).startsWith("DONE:");
			assertThat(second.get(5, TimeUnit.SECONDS)).startsWith("DONE:");
		}
		finally {
			executor.shutdownNow();
		}
		assertThat(xadds).hasValue(1);
	}

	@Test
	void dlqEvidenceIsSufficientButAnHttpSuccessWithoutDoneIsNot() {
		Outbox dlqOutbox = new Outbox();
		AtomicInteger mainFailures = new AtomicInteger();
		AtomicInteger evidenceWrites = new AtomicInteger();
		assertThat(export(dlqOutbox, (tenant, product, batch, records, key) -> {
			mainFailures.incrementAndGet();
			evidenceWrites.incrementAndGet();
			return "DONE:logs-dlq:1-0";
		}))
			.isInstanceOf(IngestionDecision.Accepted.class);
		assertThat(mainFailures).hasValue(1);
		assertThat(evidenceWrites).hasValue(1);
		assertThat(dlqOutbox.only().state()).isEqualTo(UsageOutboxState.ACCEPTED);

		Outbox noEvidenceOutbox = new Outbox();
		assertThat(export(noEvidenceOutbox, (tenant, product, batch, records, key) -> "CLAIMED:dlq-http-200"))
			.isInstanceOf(IngestionDecision.DestinationUnavailable.class);
		assertThat(noEvidenceOutbox.only().state()).isEqualTo(UsageOutboxState.PENDING);
	}

	@Test
	void mainFailureFallsBackToDlqAndWritesOneEvidence() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		when(redis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
			.thenThrow(new DataAccessException("main unavailable") { })
			.thenReturn("DONE:logs-dlq:2-0");
		LogIngestedPublisher publisher = new LogIngestedPublisher(redis, JsonMapper.builder().build(),
				MessagingProperties.defaults(), CLOCK);

		String evidence = publisher.publishBatchWithUsageEvidence("tenant", "product", store().store("tenant", "product",
				List.of(Scoped.of(LOG))), List.of(Scoped.of(LOG)), "usage:fact:dlq", java.time.Duration.ofDays(7), "logs");

		assertThat(evidence).isEqualTo("DONE:logs-dlq:2-0");
		org.mockito.Mockito.verify(redis, org.mockito.Mockito.times(2))
			.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));
	}

	@Test
	void mainAndDlqFailureReturnsUnavailable() {
		Outbox outbox = new Outbox();

		assertThat(export(outbox, (tenant, product, batch, records, key) -> {
			throw new MessagePublishException("main and DLQ failed", null);
		})).isInstanceOf(IngestionDecision.DestinationUnavailable.class);
		assertThat(outbox.only().state()).isEqualTo(UsageOutboxState.PENDING);
	}

	@Test
	void identicalUsageFactIdWithDivergentContentIsRejectedWithoutOverwrite() {
		Outbox outbox = new Outbox();
		UsageFact original = fact("immutable");
		outbox.insertPending(original, NOW);
		UsageFact divergent = new UsageFact(original.usageFactId(), original.batchId(), original.tenantId(),
				original.productId(), UsageDomain.LOGS, original.acceptedRecordIds(), original.acceptedRecordCount(),
				original.acceptedBytes() + 1, original.acceptedAt(), original.schemaVersion());

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> outbox.insertPending(divergent, NOW))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("immutable");
		assertThat(outbox.only().fact()).isEqualTo(original);
	}

	private static IngestionDecision export(Outbox outbox, PublishingLogsSink.UsagePublisher usagePublisher) {
		PublishingLogsSink sink = new PublishingLogsSink(store(), (tenant, product, batch, records) -> { },
				new UsageFactFactory(CLOCK), new UsageFactRecorder(outbox, CLOCK), usagePublisher);
		return new ExportLogsService(token -> new TokenValidationResult.Valid("tenant", "product"), sink)
			.export("token", UsageOutboxFailureAcceptanceTest::request);
	}

	private static BatchStore<LogRecord> store() {
		return (tenant, product, records) -> new StoredBatch(Signal.LOGS, "lhamalog", "logs", "batch",
				List.of("record"), 1L, 1L, NOW.toString());
	}

	private static ExportLogsServiceRequest request() {
		return ExportLogsServiceRequest.newBuilder()
			.addResourceLogs(ResourceLogs.newBuilder().addScopeLogs(ScopeLogs.newBuilder().addLogRecords(LOG))).build();
	}

	private static UsageFact fact(String record) {
		return UsageFact.create("tenant", "product", UsageDomain.LOGS, List.of(record), 1, NOW);
	}

	private static String publishOnce(ConcurrentHashMap<String, String> evidence, String key, AtomicInteger xadds,
			String route) {
		String result = evidence.compute(key, (ignored, current) -> {
			if (current != null) return current;
			xadds.incrementAndGet();
			return "DONE:" + route + ":1-0";
		});
		return result;
	}

	private static UsageAcceptanceEvidence redis(ConcurrentHashMap<String, String> evidence) {
		return new UsageAcceptanceEvidence() {
			@Override public String read(String key) { return evidence.get(key); }
			@Override public String resolvePending(String key) {
				return evidence.computeIfAbsent(key, ignored -> "ABANDONED");
			}
		};
	}

	private static final class Outbox implements UsageOutboxRepository {
		private UsageOutboxItem item;
		private final AtomicBoolean rejectNextPendingCas = new AtomicBoolean();

		@Override
		public synchronized UsageOutboxItem insertPending(UsageFact fact, Instant now) {
			if (item == null) {
				item = UsageOutboxItem.pending(fact, now);
			}
			else if (!item.fact().equals(fact)) {
				throw new IllegalStateException("usage fact id conflicts with immutable content");
			}
			return item;
		}

		@Override public synchronized Optional<UsageOutboxItem> findById(String id) {
			return item != null && item.id().equals(id) ? Optional.of(item) : Optional.empty();
		}
		@Override public synchronized List<UsageOutboxItem> findPendingBefore(Instant deadline) {
			return item != null && item.state() == UsageOutboxState.PENDING && !item.createdAt().isAfter(deadline)
					? List.of(item) : List.of();
		}
		@Override public List<UsageOutboxItem> findAcceptedReady(Instant instant) { return List.of(); }
		@Override
		public synchronized boolean compareAndSet(String id, UsageOutboxState expected, UsageOutboxItem replacement) {
			if (rejectNextPendingCas.compareAndSet(true, false)) return false;
			if (item == null || !item.id().equals(id) || item.state() != expected) return false;
			item = replacement;
			return true;
		}
		synchronized UsageOutboxItem only() { return item; }
	}
}
