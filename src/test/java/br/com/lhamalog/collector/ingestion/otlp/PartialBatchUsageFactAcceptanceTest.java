package br.com.lhamalog.collector.ingestion.otlp;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.ingestion.otlp.application.ExportLogsService;
import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidationResult;
import br.com.lhamalog.collector.ingestion.otlp.domain.Scoped;
import br.com.lhamalog.collector.ingestion.otlp.sink.LogsSink;
import br.com.lhamalog.collector.usage.domain.UsageFact;
import br.com.lhamalog.collector.usage.domain.UsageFactFactory;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

class PartialBatchUsageFactAcceptanceTest {

	@Test
	void partialBatchCreatesAUsageFactOnlyForValidRecordsAndTokenIdentity() {
		LogRecord valid = LogRecord.newBuilder().setSeverityNumberValue(9).build();
		LogRecord invalid = valid.toBuilder().setTraceId(ByteString.copyFromUtf8("short")).build();
		ExportLogsServiceRequest request = ExportLogsServiceRequest.newBuilder()
			.addResourceLogs(ResourceLogs.newBuilder().addScopeLogs(ScopeLogs.newBuilder().addLogRecords(valid).addLogRecords(invalid)))
			.build();
		CapturingSink sink = new CapturingSink();

		new ExportLogsService(token -> new TokenValidationResult.Valid("token-tenant", "token-product"), sink)
			.export("token", () -> request);

		assertThat(sink.fact.tenantId()).isEqualTo("token-tenant");
		assertThat(sink.fact.productId()).isEqualTo("token-product");
		assertThat(sink.fact.domain().name()).isEqualTo("LOGS");
		assertThat(sink.fact.acceptedRecordIds()).hasSize(1);
		assertThat(sink.fact.acceptedRecordCount()).isOne();
	}

	private static final class CapturingSink implements LogsSink {
		private UsageFact fact;

		@Override public void deliver(String tenantId, String productId, List<Scoped<LogRecord>> accepted) { }

		@Override
		public void deliver(String tenantId, String productId, ExportLogsServiceRequest request,
				List<Scoped<LogRecord>> accepted) {
			fact = new UsageFactFactory(Clock.systemUTC()).logs(tenantId, productId, request, accepted);
		}
	}
}
