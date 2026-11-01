// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.http;

import br.com.lhamalog.collector.ingestion.otlp.application.ExportLogsService;
import br.com.lhamalog.collector.ingestion.otlp.application.ExportTracesService;
import br.com.lhamalog.collector.ingestion.otlp.application.OtlpPayload;
import br.com.lhamalog.collector.ingestion.otlp.application.PayloadRejectedException;
import br.com.lhamalog.collector.ingestion.otlp.auth.BearerToken;
import br.com.lhamalog.collector.ingestion.otlp.domain.IngestionDecision;
import br.com.lhamalog.collector.ingestion.otlp.http.OtlpHttpStatusMapper.HttpOutcome;
import br.com.lhamalog.collector.ingestion.otlp.http.OtlpHttpStatusMapper.Signal;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * OTLP/HTTP receiver: {@code POST /v1/logs} and {@code POST /v1/traces} (T019, T027, T034), on its own port,
 * separate from the Spring MVC port. Uses the JDK HTTP server with virtual threads.
 *
 * <p>Accepts {@code application/x-protobuf} and {@code application/json}, identity or gzip. The token is
 * checked before anything about the body: a request without a valid token gets 401 even when its content
 * type or encoding is unsupported (T044, FR-023). The body is cut at the configured limit after
 * decompression (413), and a {@code Content-Length} above the limit is refused without reading. The
 * response {@code Content-Type} repeats the request one.
 *
 * <p>Protection (T046, FR-025, {@link HttpLimits}): at most {@code maxConcurrentRequests} requests are
 * handled at once, the others get 503 with {@code Retry-After}; connection count, request and response time
 * and idle keep-alive connections are bounded through the {@code sun.net.httpserver.*} system properties.
 * Those properties are JVM wide and read once, when the first JDK HTTP server of the JVM is created; a value
 * already set (for example with {@code -D}) wins. This server is not meant to face the internet directly:
 * put a reverse proxy in front of it in production.
 */
public class OtlpHttpServer implements SmartLifecycle {

	static final String PROTOBUF = "application/x-protobuf";

	static final String JSON = "application/json";

	private static final Logger log = LoggerFactory.getLogger(OtlpHttpServer.class);

	private final String host;

	private final int port;

	private final long maxMessageBytes;

	private final ExportLogsService logs;

	private final ExportTracesService traces;

	private final HttpLimits limits;

	private final Semaphore inFlight;

	private HttpServer server;

	private ExecutorService executor;

	public OtlpHttpServer(String host, int port, long maxMessageBytes, ExportLogsService logs, ExportTracesService traces) {
		this(host, port, maxMessageBytes, logs, traces, HttpLimits.DEFAULT);
	}

	public OtlpHttpServer(String host, int port, long maxMessageBytes, ExportLogsService logs, ExportTracesService traces,
			HttpLimits limits) {
		if (maxMessageBytes <= 0) {
			throw new IllegalArgumentException("maxMessageBytes must be positive");
		}
		this.host = host;
		this.port = port;
		this.maxMessageBytes = maxMessageBytes;
		this.logs = logs;
		this.traces = traces;
		this.limits = limits;
		this.inFlight = new Semaphore(limits.maxConcurrentRequests());
	}

	/**
	 * Sets the {@code sun.net.httpserver.*} properties from the limits, keeping any value already set. Returns
	 * the effective values. Must run before the first {@code HttpServer.create} of the JVM to take effect.
	 */
	static Map<String, String> applyServerProperties(HttpLimits limits) {
		Map<String, String> wanted = Map.of(
				"sun.net.httpserver.maxReqTime", Long.toString(limits.readTimeout().toSeconds()),
				"sun.net.httpserver.maxRspTime", Long.toString(limits.writeTimeout().toSeconds()),
				"sun.net.httpserver.idleInterval", Long.toString(limits.idleTimeout().toSeconds()),
				"sun.net.httpserver.maxIdleConnections", Integer.toString(limits.maxIdleConnections()),
				"sun.net.httpserver.maxConnections", Integer.toString(limits.maxConnections()));
		TreeMap<String, String> effective = new TreeMap<>();
		wanted.forEach((key, value) -> {
			String current = System.getProperty(key);
			if (current == null) {
				System.setProperty(key, value);
				current = value;
			}
			effective.put(key, current);
		});
		return effective;
	}

	@Override
	public synchronized void start() {
		if (server != null) {
			return;
		}
		Map<String, String> effective = applyServerProperties(limits);
		try {
			server = HttpServer.create(new InetSocketAddress(host, port), 0);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("cannot bind OTLP/HTTP on " + host + ":" + port, ex);
		}
		executor = Executors.newVirtualThreadPerTaskExecutor();
		server.setExecutor(executor);
		server.createContext("/", this::handle);
		server.start();
		log.info("OTLP/HTTP receiver listening on {}:{} (max concurrent requests {}, {})", host, getPort(),
				limits.maxConcurrentRequests(), effective);
	}

	@Override
	public synchronized void stop() {
		if (server != null) {
			server.stop(1);
			executor.close();
			server = null;
			log.info("OTLP/HTTP receiver stopped");
		}
	}

	@Override
	public synchronized boolean isRunning() {
		return server != null;
	}

	/** Bound port; differs from the configured one when that is 0. */
	public synchronized int getPort() {
		return server == null ? port : server.getAddress().getPort();
	}

	private void handle(HttpExchange exchange) throws IOException {
		if (!inFlight.tryAcquire()) {
			try (exchange) {
				write(exchange, OtlpHttpStatusMapper.overloaded(), responseType(exchange));
			}
			return;
		}
		try {
			dispatch(exchange);
		}
		finally {
			inFlight.release();
		}
	}

	private static String responseType(HttpExchange exchange) {
		return JSON.equals(mediaType(exchange.getRequestHeaders().getFirst("Content-Type"))) ? JSON : PROTOBUF;
	}

	private void dispatch(HttpExchange exchange) throws IOException {
		try (exchange) {
			String contentType = mediaType(exchange.getRequestHeaders().getFirst("Content-Type"));
			String responseType = JSON.equals(contentType) ? JSON : PROTOBUF;
			String path = exchange.getRequestURI().getPath();
			Signal signal = switch (path) {
				case "/v1/logs" -> Signal.LOGS;
				case "/v1/traces" -> Signal.TRACES;
				default -> null;
			};
			if (signal == null) {
				write(exchange, OtlpHttpStatusMapper.notFound(), responseType);
				return;
			}
			if (!"POST".equals(exchange.getRequestMethod())) {
				write(exchange, OtlpHttpStatusMapper.methodNotAllowed(), responseType);
				return;
			}
			// Content type and encoding are checked inside the lazy payload, after the token (401 before 415).
			String bearer = BearerToken.extract(exchange.getRequestHeaders().getFirst("Authorization"));
			IngestionDecision decision = signal == Signal.LOGS
					? logs.export(bearer, payload(exchange, contentType, ExportLogsServiceRequest.newBuilder()))
					: traces.export(bearer, payload(exchange, contentType, ExportTraceServiceRequest.newBuilder()));
			write(exchange, OtlpHttpStatusMapper.map(decision, signal), responseType);
		}
		catch (RuntimeException ex) {
			log.error("Unexpected failure in OTLP/HTTP receiver", ex);
			throw ex;
		}
	}

	@SuppressWarnings("unchecked")
	private <T extends Message> OtlpPayload<T> payload(HttpExchange exchange, String contentType,
			Message.Builder builder) {
		return () -> {
			if (!PROTOBUF.equals(contentType) && !JSON.equals(contentType)) {
				throw new PayloadRejectedException(IngestionDecision.Reason.UNSUPPORTED_ENCODING,
						"unsupported content type; use application/x-protobuf or application/json");
			}
			String encoding = exchange.getRequestHeaders().getFirst("Content-Encoding");
			boolean gzip = encoding != null && encoding.strip().equalsIgnoreCase("gzip");
			if (encoding != null && !gzip && !encoding.isBlank() && !encoding.strip().equalsIgnoreCase("identity")) {
				throw new PayloadRejectedException(IngestionDecision.Reason.UNSUPPORTED_ENCODING,
						"unsupported content encoding; use gzip or none");
			}
			if (!gzip && declaredLength(exchange) > maxMessageBytes) {
				throw new PayloadRejectedException(IngestionDecision.Reason.TOO_LARGE,
						"body exceeds the limit of " + maxMessageBytes + " bytes");
			}
			byte[] body = readBody(exchange.getRequestBody(), gzip);
			try {
				if (JSON.equals(contentType)) {
					OtlpJson.parse(body, builder);
				}
				else {
					builder.mergeFrom(body);
				}
				return (T) builder.build();
			}
			catch (InvalidProtocolBufferException | RuntimeException ex) {
				throw new PayloadRejectedException(IngestionDecision.Reason.MALFORMED,
						"body is not a valid OTLP " + (JSON.equals(contentType) ? "JSON" : "protobuf") + " export request");
			}
		};
	}

	private static long declaredLength(HttpExchange exchange) {
		String value = exchange.getRequestHeaders().getFirst("Content-Length");
		if (value == null) {
			return -1;
		}
		try {
			return Long.parseLong(value.strip());
		}
		catch (NumberFormatException ex) {
			return -1;
		}
	}

	private byte[] readBody(InputStream raw, boolean gzip) throws PayloadRejectedException {
		try (InputStream in = gzip ? new GZIPInputStream(raw) : raw) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			long total = 0;
			int read;
			while ((read = in.read(buffer)) != -1) {
				total += read;
				if (total > maxMessageBytes) {
					throw new PayloadRejectedException(IngestionDecision.Reason.TOO_LARGE,
							"decompressed body exceeds the limit of " + maxMessageBytes + " bytes");
				}
				out.write(buffer, 0, read);
			}
			return out.toByteArray();
		}
		catch (ZipException ex) {
			throw new PayloadRejectedException(IngestionDecision.Reason.MALFORMED, "body is not valid gzip", ex);
		}
		catch (IOException ex) {
			throw new PayloadRejectedException(IngestionDecision.Reason.MALFORMED, "body could not be read", ex);
		}
	}

	private static void write(HttpExchange exchange, HttpOutcome outcome, String responseType) throws IOException {
		byte[] body = JSON.equals(responseType) ? OtlpJson.print(outcome.body()).getBytes(StandardCharsets.UTF_8)
				: outcome.body().toByteArray();
		outcome.headers().forEach(exchange.getResponseHeaders()::set);
		exchange.getResponseHeaders().set("Content-Type", responseType);
		exchange.sendResponseHeaders(outcome.status(), body.length == 0 ? -1 : body.length);
		if (body.length > 0) {
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		}
	}

	private static String mediaType(String contentType) {
		if (contentType == null) {
			return null;
		}
		int semicolon = contentType.indexOf(';');
		return (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).strip().toLowerCase(Locale.ROOT);
	}

}
