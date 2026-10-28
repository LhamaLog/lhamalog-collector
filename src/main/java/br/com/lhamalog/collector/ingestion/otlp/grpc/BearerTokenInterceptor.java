package br.com.lhamalog.collector.ingestion.otlp.grpc;

import br.com.lhamalog.collector.ingestion.otlp.auth.AuthenticatedProduct;
import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidationResult;
import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidator;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

/** Autentica cada chamada gRPC pelo metadata {@code authorization: Bearer <token>}. */
public class BearerTokenInterceptor implements ServerInterceptor {

    static final Context.Key<AuthenticatedProduct> PRODUCT = Context.key("lhamalog-product");
    static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final TokenValidator tokenValidator;

    public BearerTokenInterceptor(TokenValidator tokenValidator) {
        this.tokenValidator = tokenValidator;
    }

    @Override
    public <Q, A> ServerCall.Listener<Q> interceptCall(ServerCall<Q, A> call, Metadata headers,
            ServerCallHandler<Q, A> next) {
        TokenValidationResult result = tokenValidator.validate(TokenValidator.extractBearer(headers.get(AUTHORIZATION)));
        if (result instanceof TokenValidationResult.Valid valid) {
            return Contexts.interceptCall(Context.current().withValue(PRODUCT, valid.product()), call, headers, next);
        }
        String message = result instanceof TokenValidationResult.Revoked ? "token revogado" : "token invalido ou ausente";
        call.close(Status.UNAUTHENTICATED.withDescription(message), new Metadata());
        return new ServerCall.Listener<>() {
        };
    }
}
