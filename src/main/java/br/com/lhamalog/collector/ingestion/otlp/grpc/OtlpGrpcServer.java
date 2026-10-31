// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.grpc;

import br.com.lhamalog.collector.ingestion.otlp.application.ExportLogsService;
import br.com.lhamalog.collector.ingestion.otlp.application.ExportTracesService;
import br.com.lhamalog.collector.ingestion.otlp.domain.IngestionDecision;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsPartialSuccess;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import io.opentelemetry.proto.collector.logs.v1.LogsServiceGrpc;
import io.opentelemetry.proto.collector.trace.v1.ExportTracePartialSuccess;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * OTLP/gRPC receiver with {@code LogsService/Export} and {@code TraceService/Export} (T020, T028, T035).
 * {@code MetricsService} is deliberately not registered, so metrics get {@code UNIMPLEMENTED}.
 *
 * <p>Status: OK (with {@code partial_success} when records were rejected), UNAUTHENTICATED,
 * INVALID_ARGUMENT, RESOURCE_EXHAUSTED without {@code RetryInfo} (message above the limit, enforced by
 * Netty), and UNAVAILABLE when the catalog or the destination did not confirm.
 */
public class OtlpGrpcServer implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(OtlpGrpcServer.class);

	private final String host;

	private final int port;

	private final int maxMessageBytes;

	private final ExportLogsService logs;

	private final ExportTracesService traces;

	private Server server;

	public OtlpGrpcServer(String host, int port, int maxMessageBytes, ExportLogsService logs, ExportTracesService traces) {
		this.host = host;
		this.port = port;
		this.maxMessageBytes = maxMessageBytes;
		this.logs = logs;
		this.traces = traces;
	}

	@Override
	public synchronized void start() {
		if (server != null) {
			return;
		}
		BearerTokenInterceptor auth = new BearerTokenInterceptor();
		try {
			server = NettyServerBuilder.forAddress(new InetSocketAddress(host, port))
				.maxInboundMessageSize(maxMessageBytes)
				.addService(ServerInterceptors.intercept(new LogsEndpoint(), auth))
				.addService(ServerInterceptors.intercept(new TracesEndpoint(), auth))
				.build()
				.start();
		}
		catch (IOException ex) {
			server = null;
			throw new UncheckedIOException("cannot bind OTLP/gRPC on " + host + ":" + port, ex);
		}
		log.info("OTLP/gRPC receiver listening on {}:{}", host, getPort());
	}

	@Override
	public synchronized void stop() {
		if (server != null) {
			server.shutdown();
			try {
				if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
					server.shutdownNow();
				}
			}
			catch (InterruptedException ex) {
				server.shutdownNow();
				Thread.currentThread().interrupt();
			}
			server = null;
			log.info("OTLP/gRPC receiver stopped");
		}
	}

	@Override
	public synchronized boolean isRunning() {
		return server != null;
	}

	public synchronized int getPort() {
		return server == null ? port : server.getPort();
	}

	/** Same decision classes as HTTP, in gRPC status. {@code null} means OK. */
	static Status failureStatus(IngestionDecision decision) {
		return switch (decision) {
			case IngestionDecision.Unauthorized u -> Status.UNAUTHENTICATED.withDescription("missing or invalid bearer token");
			case IngestionDecision.CatalogUnavailable c -> Status.UNAVAILABLE.withDescription("token catalog unavailable, retry later");
			case IngestionDecision.DestinationUnavailable d -> Status.UNAVAILABLE.withDescription("destination unavailable, retry later");
			case IngestionDecision.InvalidPayload p -> p.reason() == IngestionDecision.Reason.TOO_LARGE
					? Status.RESOURCE_EXHAUSTED.withDescription(p.message()) : Status.INVALID_ARGUMENT.withDescription(p.message());
			case IngestionDecision.EmptySuccess e -> null;
			case IngestionDecision.Accepted a -> null;
			case IngestionDecision.PartiallyAccepted p -> null;
		};
	}

	private static <A> void reply(IngestionDecision decision, StreamObserver<A> observer, A ok,
			Function<IngestionDecision.PartiallyAccepted, A> partial) {
		Status failure = failureStatus(decision);
		if (failure != null) {
			observer.onError(failure.asRuntimeException());
			return;
		}
		observer.onNext(decision instanceof IngestionDecision.PartiallyAccepted p ? partial.apply(p) : ok);
		observer.onCompleted();
	}

	private final class LogsEndpoint extends LogsServiceGrpc.LogsServiceImplBase {

		@Override
		public void export(ExportLogsServiceRequest request, StreamObserver<ExportLogsServiceResponse> observer) {
			IngestionDecision decision = logs.export(BearerTokenInterceptor.BEARER_TOKEN.get(), () -> request);
			reply(decision, observer, ExportLogsServiceResponse.getDefaultInstance(),
					p -> ExportLogsServiceResponse.newBuilder()
						.setPartialSuccess(ExportLogsPartialSuccess.newBuilder()
							.setRejectedLogRecords(p.rejectedCount())
							.setErrorMessage(p.errorMessage()))
						.build());
		}

	}

	private final class TracesEndpoint extends TraceServiceGrpc.TraceServiceImplBase {

		@Override
		public void export(ExportTraceServiceRequest request, StreamObserver<ExportTraceServiceResponse> observer) {
			IngestionDecision decision = traces.export(BearerTokenInterceptor.BEARER_TOKEN.get(), () -> request);
			reply(decision, observer, ExportTraceServiceResponse.getDefaultInstance(),
					p -> ExportTraceServiceResponse.newBuilder()
						.setPartialSuccess(ExportTracePartialSuccess.newBuilder()
							.setRejectedSpans(p.rejectedCount())
							.setErrorMessage(p.errorMessage()))
						.build());
		}

	}

}
