package br.com.lhamalog.collector.ingestion.otlp.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.RetryInfo;
import com.google.rpc.Status;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;

/** Assertions for {@link RetryInfo} in {@code grpc-status-details-bin}. */
final class GrpcRetryInfo {

	static final Metadata.Key<String> LEGACY_RETRY_AFTER = Metadata.Key.of("retry-after",
			Metadata.ASCII_STRING_MARSHALLER);

	private static final Metadata.Key<byte[]> STATUS_DETAILS = Metadata.Key.of("grpc-status-details-bin",
			Metadata.BINARY_BYTE_MARSHALLER);

	private GrpcRetryInfo() {
	}

	static long retryDelaySeconds(StatusRuntimeException ex) {
		assertThat(ex.getTrailers()).isNotNull();
		byte[] raw = ex.getTrailers().get(STATUS_DETAILS);
		assertThat(raw).as("grpc-status-details-bin").isNotNull();
		Status status;
		try {
			status = Status.parseFrom(raw);
		}
		catch (InvalidProtocolBufferException parseFailure) {
			throw new AssertionError("grpc-status-details-bin is not a google.rpc.Status", parseFailure);
		}
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
		throw new AssertionError("no RetryInfo in grpc-status-details-bin");
	}

	static void assertNoRetryInfo(StatusRuntimeException ex) {
		if (ex.getTrailers() != null && ex.getTrailers().get(STATUS_DETAILS) != null) {
			try {
				Status status = Status.parseFrom(ex.getTrailers().get(STATUS_DETAILS));
				for (int i = 0; i < status.getDetailsCount(); i++) {
					assertThat(status.getDetails(i).is(RetryInfo.class))
							.as("unexpected RetryInfo in status details")
							.isFalse();
				}
			}
			catch (InvalidProtocolBufferException parseFailure) {
				throw new AssertionError("grpc-status-details-bin is not a google.rpc.Status", parseFailure);
			}
		}
		assertNoLegacyRetryAfterTrailer(ex);
	}

	static void assertNoLegacyRetryAfterTrailer(StatusRuntimeException ex) {
		assertThat(ex.getTrailers() == null ? null : ex.getTrailers().get(LEGACY_RETRY_AFTER)).isNull();
	}

}
