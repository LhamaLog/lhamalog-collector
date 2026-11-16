package br.com.lhamalog.collector.ingestion.otlp.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.RetryInfo;
import com.google.rpc.Status;

/** Assertions for {@link RetryInfo} packed into OTLP/HTTP {@code google.rpc.Status} bodies. */
final class HttpRetryInfo {

	private HttpRetryInfo() {
	}

	static long retryDelaySeconds(Status status) {
		assertThat(status.getDetailsCount()).isGreaterThan(0);
		for (int i = 0; i < status.getDetailsCount(); i++) {
			try {
				if (status.getDetails(i).is(RetryInfo.class)) {
					return status.getDetails(i).unpack(RetryInfo.class).getRetryDelay().getSeconds();
				}
			}
			catch (InvalidProtocolBufferException unpackFailure) {
				throw new AssertionError("RetryInfo detail could not be unpacked", unpackFailure);
			}
		}
		throw new AssertionError("no RetryInfo in Status details");
	}

	static void assertNoRetryInfo(Status status) {
		for (int i = 0; i < status.getDetailsCount(); i++) {
			assertThat(status.getDetails(i).is(RetryInfo.class)).isFalse();
		}
	}

}
