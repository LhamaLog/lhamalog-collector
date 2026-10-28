package br.com.lhamalog.collector.ingestion.otlp.http;

import br.com.lhamalog.collector.ingestion.otlp.application.OtlpIngestionService;
import br.com.lhamalog.collector.ingestion.otlp.application.SinkUnavailableException;
import br.com.lhamalog.collector.ingestion.otlp.auth.AuthenticatedProduct;
import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidationResult;
import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidator;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.rpc.Code;
import com.google.rpc.Status;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Receptor OTLP/HTTP (porta padrao 4318): {@code POST /v1/logs} e {@code POST /v1/traces},
 * com corpo {@code application/x-protobuf} ou {@code application/json} (opcionalmente gzip).
 *
 * <p>Roda num servidor HTTP proprio, separado do Tomcat da aplicacao (8080): nao passa pela cadeia do
 * Spring Security; a autenticacao e feita aqui pelo token Bearer do produto.
 */
public class OtlpHttpServer {

    public static final String LOGS_PATH = "/v1/logs";
    public static final String TRACES_PATH = "/v1/traces";
    static final String PROTOBUF = "application/x-protobuf";
    static final String JSON = "application/json";

    private static final Logger log = LoggerFactory.getLogger(OtlpHttpServer.class);

    private final String host;
    private final int configuredPort;
    private final long maxRequestBytes;
    private final TokenValidator tokenValidator;
    private final OtlpIngestionService ingestion;
    private HttpServer server;
    private ExecutorService executor;

    public OtlpHttpServer(String host, int port, long maxRequestBytes, TokenValidator tokenValidator,
            OtlpIngestionService ingestion) {
        this.host = host;
        this.configuredPort = port;
        this.maxRequestBytes = maxRequestBytes;
        this.tokenValidator = tokenValidator;
        this.ingestion = ingestion;
    }

    public synchronized void start() throws IOException {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server = HttpServer.create(new InetSocketAddress(host, configuredPort), 0);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
        log.info("[OTLP] receptor HTTP escutando em {}:{} ({} e {})", host, port(), LOGS_PATH, TRACES_PATH);
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(1);
            executor.close();
            server = null;
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            boolean logs = LOGS_PATH.equals(path);
            if (!logs && !TRACES_PATH.equals(path)) {
                sendStatus(exchange, 404, false, Code.NOT_FOUND, "caminho nao encontrado: " + path);
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                sendStatus(exchange, 405, false, Code.UNIMPLEMENTED, "metodo nao permitido; use POST");
                return;
            }
            String contentType = mediaType(exchange.getRequestHeaders().getFirst("Content-Type"));
            boolean json = JSON.equals(contentType);
            if (!json && !PROTOBUF.equals(contentType)) {
                sendStatus(exchange, 415, false, Code.INVALID_ARGUMENT,
                        "Content-Type nao suportado; use application/x-protobuf ou application/json");
                return;
            }
            // Autentica antes de ler/processar o lote.
            AuthenticatedProduct product = authenticate(exchange, json);
            if (product == null) {
                return;
            }
            byte[] body = readBody(exchange, json);
            if (body == null) {
                return;
            }
            Message response;
            try {
                response = logs ? ingestion.exportLogs(product, parseLogs(body, json))
                        : ingestion.exportTraces(product, parseTraces(body, json));
            } catch (InvalidProtocolBufferException e) {
                sendStatus(exchange, 400, json, Code.INVALID_ARGUMENT, "corpo OTLP invalido: " + e.getMessage());
                return;
            } catch (SinkUnavailableException e) {
                exchange.getResponseHeaders().set("Retry-After", String.valueOf(e.retryAfter().toSeconds()));
                sendStatus(exchange, 503, json, Code.UNAVAILABLE, e.getMessage());
                return;
            }
            send(exchange, 200, json, response);
        } catch (Exception e) {
            log.error("[OTLP] erro inesperado no receptor HTTP", e);
            try {
                sendStatus(exchange, 500, false, Code.INTERNAL, "erro interno");
            } catch (IOException ignored) {
                // resposta ja iniciada
            }
        }
    }

    private AuthenticatedProduct authenticate(HttpExchange exchange, boolean json) throws IOException {
        String token = TokenValidator.extractBearer(exchange.getRequestHeaders().getFirst("Authorization"));
        TokenValidationResult result = tokenValidator.validate(token);
        if (result instanceof TokenValidationResult.Valid valid) {
            return valid.product();
        }
        String message = result instanceof TokenValidationResult.Revoked ? "token revogado" : "token invalido ou ausente";
        exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
        sendStatus(exchange, 401, json, Code.UNAUTHENTICATED, message);
        return null;
    }

    private byte[] readBody(HttpExchange exchange, boolean json) throws IOException {
        String encoding = exchange.getRequestHeaders().getFirst("Content-Encoding");
        InputStream in = exchange.getRequestBody();
        if (encoding != null && !encoding.isBlank() && !"identity".equalsIgnoreCase(encoding.trim())) {
            if (!"gzip".equalsIgnoreCase(encoding.trim())) {
                sendStatus(exchange, 415, json, Code.INVALID_ARGUMENT, "Content-Encoding nao suportado: " + encoding);
                return null;
            }
            try {
                in = new GZIPInputStream(in);
            } catch (IOException e) {
                sendStatus(exchange, 400, json, Code.INVALID_ARGUMENT, "corpo gzip invalido");
                return null;
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        try {
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxRequestBytes) {
                    sendStatus(exchange, 413, json, Code.RESOURCE_EXHAUSTED,
                            "corpo excede o limite de " + maxRequestBytes + " bytes");
                    return null;
                }
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            sendStatus(exchange, 400, json, Code.INVALID_ARGUMENT, "falha ao ler o corpo: " + e.getMessage());
            return null;
        }
        return out.toByteArray();
    }

    private static ExportLogsServiceRequest parseLogs(byte[] body, boolean json) throws InvalidProtocolBufferException {
        if (!json) {
            return ExportLogsServiceRequest.parseFrom(body);
        }
        ExportLogsServiceRequest.Builder builder = ExportLogsServiceRequest.newBuilder();
        OtlpJson.merge(new String(body, StandardCharsets.UTF_8), builder);
        return builder.build();
    }

    private static ExportTraceServiceRequest parseTraces(byte[] body, boolean json) throws InvalidProtocolBufferException {
        if (!json) {
            return ExportTraceServiceRequest.parseFrom(body);
        }
        ExportTraceServiceRequest.Builder builder = ExportTraceServiceRequest.newBuilder();
        OtlpJson.merge(new String(body, StandardCharsets.UTF_8), builder);
        return builder.build();
    }

    private static void sendStatus(HttpExchange exchange, int httpStatus, boolean json, Code code, String message)
            throws IOException {
        send(exchange, httpStatus, json, Status.newBuilder().setCode(code.getNumber()).setMessage(message).build());
    }

    private static void send(HttpExchange exchange, int httpStatus, boolean json, Message message) throws IOException {
        byte[] bytes = json ? OtlpJson.print(message).getBytes(StandardCharsets.UTF_8) : message.toByteArray();
        exchange.getResponseHeaders().set("Content-Type", json ? JSON : PROTOBUF);
        exchange.sendResponseHeaders(httpStatus, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    static String mediaType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semicolon = contentType.indexOf(';');
        String type = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
        return type.trim().toLowerCase(Locale.ROOT);
    }
}
