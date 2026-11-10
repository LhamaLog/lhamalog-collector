package br.com.lhamalog.collector.usage.domain;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.ingestion.otlp.domain.Scoped;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import java.util.List;
import org.junit.jupiter.api.Test;

class CanonicalTracesMeasurementTest {

	@Test
	void preservesRepeatedGroupsAndMeasuresTheDeterministicRebuiltEnvelope() {
		Span first = Span.newBuilder().setName("kept-first")
			.setTraceId(ByteString.copyFromUtf8("0123456789abcdef")).setSpanId(ByteString.copyFromUtf8("12345678")).build();
		Span rejected = Span.newBuilder().setName("rejected")
			.setTraceId(ByteString.copyFromUtf8("0123456789abcdef")).setSpanId(ByteString.copyFromUtf8("23456789")).build();
		Span last = Span.newBuilder().setName("kept-last")
			.setTraceId(ByteString.copyFromUtf8("0123456789abcdef")).setSpanId(ByteString.copyFromUtf8("34567890")).build();
		ResourceSpans resource = ResourceSpans.newBuilder()
			.addScopeSpans(ScopeSpans.newBuilder().addSpans(first))
			.addScopeSpans(ScopeSpans.newBuilder().addSpans(rejected))
			.addScopeSpans(ScopeSpans.newBuilder().addSpans(last)).build();
		ExportTraceServiceRequest request = ExportTraceServiceRequest.newBuilder().addResourceSpans(resource).build();

		CanonicalOtlpMeasurement firstMeasurement = CanonicalOtlpMeasurement.traces(request,
				List.of(new Scoped<>(resource.getResource(), resource.getScopeSpans(0).getScope(), first),
						new Scoped<>(resource.getResource(), resource.getScopeSpans(2).getScope(), last)));
		CanonicalOtlpMeasurement secondMeasurement = CanonicalOtlpMeasurement.traces(request,
				List.of(new Scoped<>(resource.getResource(), resource.getScopeSpans(0).getScope(), first),
						new Scoped<>(resource.getResource(), resource.getScopeSpans(2).getScope(), last)));

		ExportTraceServiceRequest rebuilt = (ExportTraceServiceRequest) firstMeasurement.acceptedRequest();
		assertThat(rebuilt.getResourceSpans(0).getScopeSpansList())
			.extracting(ScopeSpans::getSpansList)
			.containsExactly(List.of(first), List.of(last));
		assertThat(firstMeasurement.serializedBytes()).isEqualTo(secondMeasurement.serializedBytes()).isPositive();
	}
}
