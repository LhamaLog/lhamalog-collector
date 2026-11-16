// Author: LhamaLog Desenvolvimento (IA) - LLT-183 logging standard
package br.com.lhamalog.collector.ingestion.otlp.grpc;

import br.com.lhamalog.collector.logging.CorrelationId;
import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

/**
 * Resolves {@code x-correlation-id} from inbound metadata, puts it in MDC for each listener
 * callback, and echoes it on response headers/trailers.
 */
public final class CorrelationIdServerInterceptor implements ServerInterceptor {

	static final Metadata.Key<String> CORRELATION_ID_KEY = Metadata.Key.of(CorrelationId.GRPC_METADATA_KEY,
			Metadata.ASCII_STRING_MARSHALLER);

	@Override
	public <Q, A> ServerCall.Listener<Q> interceptCall(ServerCall<Q, A> call, Metadata headers,
			ServerCallHandler<Q, A> next) {
		String correlationId = CorrelationId.resolve(headers.get(CORRELATION_ID_KEY));
		ServerCall<Q, A> forwardingCall = new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
			@Override
			public void sendHeaders(Metadata responseHeaders) {
				responseHeaders.put(CORRELATION_ID_KEY, correlationId);
				super.sendHeaders(responseHeaders);
			}

			@Override
			public void close(Status status, Metadata trailers) {
				trailers.put(CORRELATION_ID_KEY, correlationId);
				super.close(status, trailers);
			}
		};
		ServerCall.Listener<Q> listener = next.startCall(forwardingCall, headers);
		return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(listener) {
			@Override
			public void onMessage(Q message) {
				withMdc(() -> super.onMessage(message));
			}

			@Override
			public void onHalfClose() {
				withMdc(super::onHalfClose);
			}

			@Override
			public void onCancel() {
				withMdc(super::onCancel);
			}

			@Override
			public void onComplete() {
				withMdc(super::onComplete);
			}

			@Override
			public void onReady() {
				withMdc(super::onReady);
			}

			private void withMdc(Runnable action) {
				CorrelationId.put(correlationId);
				try {
					action.run();
				}
				finally {
					CorrelationId.clear();
				}
			}
		};
	}

}
