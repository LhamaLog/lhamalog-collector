// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.grpc;

import br.com.lhamalog.collector.ingestion.otlp.auth.BearerToken;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

/**
 * Copies the Bearer token of the {@code authorization} metadata into the gRPC {@link Context} (T020). It does
 * not decide anything: validation happens in the use case, before the batch is assessed.
 */
public class BearerTokenInterceptor implements ServerInterceptor {

	static final Context.Key<String> BEARER_TOKEN = Context.key("lhamalog-bearer-token");

	private static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of("authorization",
			Metadata.ASCII_STRING_MARSHALLER);

	@Override
	public <Q, A> ServerCall.Listener<Q> interceptCall(ServerCall<Q, A> call, Metadata headers,
			ServerCallHandler<Q, A> next) {
		Context context = Context.current().withValue(BEARER_TOKEN, BearerToken.extract(headers.get(AUTHORIZATION)));
		return Contexts.interceptCall(context, call, headers, next);
	}

}
