package br.com.lhamalog.collector.ingestion.otlp.grpc;

import br.com.lhamalog.collector.ingestion.otlp.application.OtlpIngestionService;
import br.com.lhamalog.collector.ingestion.otlp.application.SinkUnavailableException;
import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidator;
import com.google.protobuf.Any;
import com.google.protobuf.Duration;
import com.google.rpc.Code;
import com.google.rpc.RetryInfo;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import io.opentelemetry.proto.collector.logs.v1.LogsServiceGrpc;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Receptor OTLP/gRPC (porta padrao 4317): LogsService/Export e TraceService/Export. */
public class OtlpGrpcServer {

    private static final Logger log = LoggerFactory.getLogger(OtlpGrpcServer.class);

    private final String host;
    private final int configuredPort;
    private final int maxInboundMessageBytes;
    private final TokenValidator tokenValidator;
    private final OtlpIngestionService ingestion;
    private Server server;

    public OtlpGrpcServer(String host, int port, int maxInboundMessageBytes, TokenValidator tokenValidator,
            OtlpIngestionService ingestion) {
        this.host = host;
        this.configuredPort = port;
        this.maxInboundMessageBytes = maxInboundMessageBytes;
        this.tokenValidator = tokenValidator;
        this.ingestion = ingestion;
    }

    public synchronized void start() throws IOException {
        BearerTokenInterceptor auth = new BearerTokenInterceptor(tokenValidator);
        server = NettyServerBuilder
                .forAddress(new InetSocketAddress(host, configuredPort), InsecureServerCredentials.create())
                .maxInboundMessageSize(maxInboundMessageBytes)
                .addService(ServerInterceptors.intercept(new LogsService(), auth))
                .addService(ServerInterceptors.intercept(new TraceService(), auth))
                .build()
                .start();
        log.info("[OTLP] receptor gRPC escutando em {}:{} (LogsService/Export e TraceService/Export)", host, port());
    }

    public synchronized void stop() {
        if (server != null) {
            server.shutdown();
            try {
                if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                    server.shutdownNow();
                }
            } catch (InterruptedException e) {
                server.shutdownNow();
                Thread.currentThread().interrupt();
            }
            server = null;
        }
    }

    public int port() {
        return server.getPort();
    }

    private final class LogsService extends LogsServiceGrpc.LogsServiceImplBase {
        @Override
        public void export(ExportLogsServiceRequest request, StreamObserver<ExportLogsServiceResponse> observer) {
            respond(observer, () -> ingestion.exportLogs(BearerTokenInterceptor.PRODUCT.get(), request));
        }
    }

    private final class TraceService extends TraceServiceGrpc.TraceServiceImplBase {
        @Override
        public void export(ExportTraceServiceRequest request, StreamObserver<ExportTraceServiceResponse> observer) {
            respond(observer, () -> ingestion.exportTraces(BearerTokenInterceptor.PRODUCT.get(), request));
        }
    }

    private static <T> void respond(StreamObserver<T> observer, Supplier<T> action) {
        try {
            observer.onNext(action.get());
            observer.onCompleted();
        } catch (SinkUnavailableException e) {
            com.google.rpc.Status status = com.google.rpc.Status.newBuilder()
                    .setCode(Code.UNAVAILABLE.getNumber())
                    .setMessage(e.getMessage())
                    .addDetails(Any.pack(RetryInfo.newBuilder()
                            .setRetryDelay(Duration.newBuilder().setSeconds(e.retryAfter().toSeconds()))
                            .build()))
                    .build();
            observer.onError(StatusProto.toStatusRuntimeException(status));
        } catch (RuntimeException e) {
            log.error("[OTLP] erro inesperado no receptor gRPC", e);
            observer.onError(Status.INTERNAL.withDescription("erro interno").asRuntimeException());
        }
    }
}
