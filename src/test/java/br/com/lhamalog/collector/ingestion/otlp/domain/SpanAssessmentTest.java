// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.trace.v1.Span;
import org.junit.jupiter.api.Test;

class SpanAssessmentTest {

	private static ByteString id(int size) {
		byte[] bytes = new byte[size];
		bytes[size - 1] = 1;
		return ByteString.copyFrom(bytes);
	}

	private static Span.Builder valid() {
		return Span.newBuilder().setTraceId(id(16)).setSpanId(id(8)).setName("GET /").setStartTimeUnixNano(10).setEndTimeUnixNano(20);
	}

	@Test
	void validSpanPasses() {
		assertThat(SpanAssessment.isValid(valid().build())).isTrue();
		assertThat(SpanAssessment.isValid(valid().setParentSpanId(id(8)).build())).isTrue();
		assertThat(SpanAssessment.isValid(valid().setEndTimeUnixNano(0).build())).isTrue();
	}

	@Test
	void traceIdAbsentWrongSizeOrZeroIsInvalid() {
		assertThat(SpanAssessment.isValid(valid().clearTraceId().build())).isFalse();
		assertThat(SpanAssessment.isValid(valid().setTraceId(id(8)).build())).isFalse();
		assertThat(SpanAssessment.isValid(valid().setTraceId(ByteString.copyFrom(new byte[16])).build())).isFalse();
	}

	@Test
	void spanIdAbsentWrongSizeOrZeroIsInvalid() {
		assertThat(SpanAssessment.isValid(valid().clearSpanId().build())).isFalse();
		assertThat(SpanAssessment.isValid(valid().setSpanId(id(16)).build())).isFalse();
		assertThat(SpanAssessment.isValid(valid().setSpanId(ByteString.copyFrom(new byte[8])).build())).isFalse();
	}

	@Test
	void parentSpanIdPresentMustHave8Bytes() {
		assertThat(SpanAssessment.isValid(valid().setParentSpanId(id(4)).build())).isFalse();
	}

	@Test
	void emptyNameIsInvalid() {
		assertThat(SpanAssessment.isValid(valid().setName("").build())).isFalse();
	}

	@Test
	void endBeforeStartIsInvalid() {
		assertThat(SpanAssessment.isValid(valid().setStartTimeUnixNano(30).setEndTimeUnixNano(20).build())).isFalse();
	}

}
