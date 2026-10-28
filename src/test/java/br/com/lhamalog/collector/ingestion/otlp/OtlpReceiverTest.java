package br.com.lhamalog.collector.ingestion.otlp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.lhamalog.collector.ingestion.otlp.application.LogsBatchSink;
import br.com.lhamalog.collector.ingestion.otlp.application.OtlpIngestionService;
import br.com.lhamalog.collector.ingestion.otlp.application.SinkUnavailableException;
import br.com.lhamalog.collector.ingestion.otlp.application.TracesBatchSink;
import br.com.lhamalog.collector.ingestion.otlp.auth.PropertiesTokenValidator;
import br.com.lhamalog.collector.ingestion.otlp.grpc.OtlpGrpcServer;
import br.com.lhamalog.collector.ingestion.otlp.http.OtlpHttpServer;
import com.google.protobuf.ByteString;
import com.google.rpc.RetryInfo;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.MetadataUtils;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import io.opentelemetry.proto.collector.logs.v1.LogsServiceGrpc;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** Testes de ponta a ponta dos receptores OTLP (HTTP e gRPC), sem Docker. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OtlpReceiverTest {

    static final String VALID = "token-valido";
    static final String REVOKED = "token-revogado";
    static final String TRACE_HEX = "5b8efff798038103d269b633813fc60c";
    static final String SPAN_HEX = "eee19b7ec3c1b174";

    final List<Integer> acceptedLogs = new CopyOnWriteArrayList<>();
    final List<Integer> acceptedSpans = new CopyOnWriteArrayList<>();
    final AtomicBoolean backpressure = new AtomicBoolean();

    OtlpHttpServer httpServer;
    OtlpGrpcServer grpcServer;
    ManagedChannel channel;
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    void start() throws Exception {
        LogsBatchSink logsSink = (product, batch, count) -> {
            if (backpressure.get()) {
                throw new SinkUnavailableException("fila cheia");
            }
            acceptedLogs.add(count);
        };
        TracesBatchSink tracesSink = (product, batch, count) -> acceptedSpans.add(count);
        var validator = new PropertiesTokenValidator(List.of(VALID, REVOKED), List.of(REVOKED));
        var ingestion = new OtlpIngestionService(logsSink, tracesSink);
        httpServer = new OtlpHttpServer("127.0.0.1", 0, 1024 * 1024, validator, ingestion);
        grpcServer = new OtlpGrpcServer("127.0.0.1", 0, 1024 * 1024, validator, ingestion);
        httpServer.start();
        grpcServer.start();
        channel = NettyChannelBuilder.forAddress("127.0.0.1", grpcServer.port()).usePlaintext().build();
    }

    @AfterAll
    void stop() {
        channel.shutdownNow();
        httpServer.stop();
        grpcServer.stop();
    }

    @BeforeEach
    void reset() {
        acceptedLogs.clear();
        acceptedSpans.clear();
        backpressure.set(false);
    }

    // ---------- payloads ----------

    static LogRecord validLog(String body) {
        return LogRecord.newBuilder().setTimeUnixNano(1_700_000_000_000_000_000L)
                .setSeverityNumber(SeverityNumber.SEVERITY_NUMBER_INFO).setSeverityText("INFO")
                .setBody(AnyValue.newBuilder().setStringValue(body))
                .setTraceId(ByteString.fromHex(TRACE_HEX)).setSpanId(ByteString.fromHex(SPAN_HEX)).build();
    }

    static ExportLogsServiceRequest logs(LogRecord... records) {
        return ExportLogsServiceRequest.newBuilder().addResourceLogs(ResourceLogs.newBuilder()
                .addScopeLogs(ScopeLogs.newBuilder().addAllLogRecords(List.of(records)))).build();
    }

    static Span validSpan(String name) {
        return Span.newBuilder().setTraceId(ByteString.fromHex(TRACE_HEX)).setSpanId(ByteString.fromHex(SPAN_HEX))
                .setName(name).setKind(Span.SpanKind.SPAN_KIND_SERVER)
                .setStartTimeUnixNano(1_000).setEndTimeUnixNano(2_000).build();
    }

    static ExportTraceServiceRequest traces(Span... spans) {
        return ExportTraceServiceRequest.newBuilder().addResourceSpans(ResourceSpans.newBuilder()
                .addScopeSpans(ScopeSpans.newBuilder().addAllSpans(List.of(spans)))).build();
    }

    static final String LOGS_JSON = """
            {"resourceLogs":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"app"}}]},
             "scopeLogs":[{"scope":{"name":"teste"},"logRecords":[
               {"timeUnixNano":"1700000000000000000","severityNumber":9,"severityText":"INFO",
                "body":{"stringValue":"ok"},"traceId":"%s","spanId":"%s"},
               {"timeUnixNano":"1700000000000000000","severityNumber":9,"body":{"stringValue":"id curto"},
                "traceId":"%s"}
             ]}]}]}""";

    static final String TRACES_JSON = """
            {"resourceSpans":[{"scopeSpans":[{"spans":[
              {"traceId":"%s","spanId":"%s","name":"GET /","kind":2,
               "startTimeUnixNano":"1000","endTimeUnixNano":"2000"},
              {"traceId":"%s","spanId":"%s","name":"","kind":2}
            ]}]}]}""";

    // ---------- helpers HTTP ----------

    HttpResponse<byte[]> post(String path, String contentType, byte[] body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpServer.port() + path))
                .header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Nested
    class HttpProtobuf {

        @Test
        void aceitaLogsEResponde200ComRespostaVazia() throws Exception {
            var resp = post("/v1/logs", "application/x-protobuf", logs(validLog("a"), validLog("b")).toByteArray(), VALID);
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.headers().firstValue("Content-Type")).hasValue("application/x-protobuf");
            assertThat(ExportLogsServiceResponse.parseFrom(resp.body()).hasPartialSuccess()).isFalse();
            assertThat(acceptedLogs).containsExactly(2);
        }

        @Test
        void partialSuccessDeLogs() throws Exception {
            LogRecord bad = validLog("x").toBuilder().setTraceId(ByteString.fromHex("abcd")).build();
            var resp = post("/v1/logs", "application/x-protobuf", logs(validLog("a"), bad).toByteArray(), VALID);
            assertThat(resp.statusCode()).isEqualTo(200);
            var parsed = ExportLogsServiceResponse.parseFrom(resp.body());
            assertThat(parsed.getPartialSuccess().getRejectedLogRecords()).isEqualTo(1);
            assertThat(parsed.getPartialSuccess().getErrorMessage()).contains("trace_id");
            assertThat(acceptedLogs).containsExactly(1);
        }

        @Test
        void aceitaTracesEPartialSuccess() throws Exception {
            Span bad = validSpan("").toBuilder().build();
            var resp = post("/v1/traces", "application/x-protobuf", traces(validSpan("GET /"), bad).toByteArray(), VALID);
            assertThat(resp.statusCode()).isEqualTo(200);
            var parsed = ExportTraceServiceResponse.parseFrom(resp.body());
            assertThat(parsed.getPartialSuccess().getRejectedSpans()).isEqualTo(1);
            assertThat(parsed.getPartialSuccess().getErrorMessage()).contains("name");
            assertThat(acceptedSpans).containsExactly(1);
        }

        @Test
        void corpoInvalidoResponde400() throws Exception {
            var resp = post("/v1/logs", "application/x-protobuf", new byte[] {(byte) 0xff, 0x01, 0x02}, VALID);
            assertThat(resp.statusCode()).isEqualTo(400);
            assertThat(com.google.rpc.Status.parseFrom(resp.body()).getCode()).isEqualTo(3);
        }

        @Test
        void contentTypeNaoSuportadoResponde415() throws Exception {
            var resp = post("/v1/logs", "text/plain", "oi".getBytes(), VALID);
            assertThat(resp.statusCode()).isEqualTo(415);
        }

        @Test
        void backPressureResponde503ComRetryAfterDe2Minutos() throws Exception {
            backpressure.set(true);
            var resp = post("/v1/logs", "application/x-protobuf", logs(validLog("a")).toByteArray(), VALID);
            assertThat(resp.statusCode()).isEqualTo(503);
            assertThat(resp.headers().firstValue("Retry-After")).hasValue("120");
        }
    }

    @Nested
    class HttpJson {

        @Test
        void aceitaLogsJsonComIdsHexEEnumInteiro() throws Exception {
            String json = """
                    {"resourceLogs":[{"scopeLogs":[{"logRecords":[{"severityNumber":17,"body":{"stringValue":"erro"},
                     "traceId":"%s","spanId":"%s","campoDesconhecido":1}]}]}]}""".formatted(TRACE_HEX, SPAN_HEX);
            var resp = post("/v1/logs", "application/json", json.getBytes(), VALID);
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.headers().firstValue("Content-Type")).hasValue("application/json");
            assertThat(new String(resp.body())).isEqualTo("{}");
            assertThat(acceptedLogs).containsExactly(1);
        }

        @Test
        void partialSuccessDeLogsEmJson() throws Exception {
            String json = LOGS_JSON.formatted(TRACE_HEX, SPAN_HEX, "abcd");
            var resp = post("/v1/logs", "application/json; charset=utf-8", json.getBytes(), VALID);
            assertThat(resp.statusCode()).isEqualTo(200);
            String body = new String(resp.body());
            assertThat(body).contains("\"partialSuccess\"").contains("\"rejectedLogRecords\":\"1\"")
                    .contains("\"errorMessage\"");
            assertThat(acceptedLogs).containsExactly(1);
        }

        @Test
        void partialSuccessDeTracesEmJson() throws Exception {
            String json = TRACES_JSON.formatted(TRACE_HEX, SPAN_HEX, TRACE_HEX, SPAN_HEX);
            var resp = post("/v1/traces", "application/json", json.getBytes(), VALID);
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(new String(resp.body())).contains("\"rejectedSpans\":\"1\"");
            assertThat(acceptedSpans).containsExactly(1);
        }

        @Test
        void jsonInvalidoResponde400EmJson() throws Exception {
            var resp = post("/v1/logs", "application/json", "{nao e json".getBytes(), VALID);
            assertThat(resp.statusCode()).isEqualTo(400);
            assertThat(new String(resp.body())).contains("\"code\":3");
        }

        @Test
        void idNaoHexResponde400() throws Exception {
            String json = """
                    {"resourceSpans":[{"scopeSpans":[{"spans":[{"traceId":"zz","spanId":"%s","name":"a"}]}]}]}"""
                    .formatted(SPAN_HEX);
            var resp = post("/v1/traces", "application/json", json.getBytes(), VALID);
            assertThat(resp.statusCode()).isEqualTo(400);
        }
    }

    @Nested
    class HttpAuth {

        @Test
        void tokenInvalidoResponde401SemProcessar() throws Exception {
            var resp = post("/v1/logs", "application/json", "{}".getBytes(), "desconhecido");
            assertThat(resp.statusCode()).isEqualTo(401);
            assertThat(resp.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");
            assertThat(new String(resp.body())).contains("\"code\":16");
            assertThat(acceptedLogs).isEmpty();
        }

        @Test
        void tokenRevogadoResponde401SemProcessar() throws Exception {
            var resp = post("/v1/traces", "application/x-protobuf", traces(validSpan("a")).toByteArray(), REVOKED);
            assertThat(resp.statusCode()).isEqualTo(401);
            assertThat(com.google.rpc.Status.parseFrom(resp.body()).getMessage()).contains("revogado");
            assertThat(acceptedSpans).isEmpty();
        }

        @Test
        void semTokenResponde401() throws Exception {
            var resp = post("/v1/logs", "application/x-protobuf", logs(validLog("a")).toByteArray(), null);
            assertThat(resp.statusCode()).isEqualTo(401);
            assertThat(acceptedLogs).isEmpty();
        }
    }

    // ---------- gRPC ----------

    LogsServiceGrpc.LogsServiceBlockingStub logsStub(String token) {
        Metadata md = new Metadata();
        md.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
        return LogsServiceGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(md));
    }

    TraceServiceGrpc.TraceServiceBlockingStub traceStub(String token) {
        Metadata md = new Metadata();
        md.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
        return TraceServiceGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(md));
    }

    @Nested
    class Grpc {

        @Test
        void exportaLogs() {
            var resp = logsStub(VALID).export(logs(validLog("a")));
            assertThat(resp.hasPartialSuccess()).isFalse();
            assertThat(acceptedLogs).containsExactly(1);
        }

        @Test
        void exportaLogsComPartialSuccess() {
            LogRecord bad = validLog("x").toBuilder().setSpanId(ByteString.fromHex("ab")).build();
            var resp = logsStub(VALID).export(logs(validLog("a"), bad, bad));
            assertThat(resp.getPartialSuccess().getRejectedLogRecords()).isEqualTo(2);
            assertThat(resp.getPartialSuccess().getErrorMessage()).contains("span_id");
            assertThat(acceptedLogs).containsExactly(1);
        }

        @Test
        void exportaTracesComPartialSuccess() {
            Span bad = validSpan("a").toBuilder().setTraceId(ByteString.copyFrom(new byte[16])).build();
            var resp = traceStub(VALID).export(traces(validSpan("a"), bad));
            assertThat(resp.getPartialSuccess().getRejectedSpans()).isEqualTo(1);
            assertThat(acceptedSpans).containsExactly(1);
        }

        @Test
        void tokenInvalidoRetornaUnauthenticated() {
            assertThatThrownBy(() -> logsStub("desconhecido").export(logs(validLog("a"))))
                    .isInstanceOfSatisfying(StatusRuntimeException.class,
                            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
            assertThat(acceptedLogs).isEmpty();
        }

        @Test
        void tokenRevogadoRetornaUnauthenticated() {
            assertThatThrownBy(() -> traceStub(REVOKED).export(traces(validSpan("a"))))
                    .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
                        assertThat(e.getStatus().getDescription()).contains("revogado");
                    });
            assertThat(acceptedSpans).isEmpty();
        }

        @Test
        void semTokenRetornaUnauthenticated() {
            assertThatThrownBy(() -> LogsServiceGrpc.newBlockingStub(channel).export(logs(validLog("a"))))
                    .isInstanceOfSatisfying(StatusRuntimeException.class,
                            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
        }

        @Test
        void backPressureRetornaUnavailableComRetryInfo() throws Exception {
            backpressure.set(true);
            assertThatThrownBy(() -> logsStub(VALID).export(logs(validLog("a"))))
                    .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
                        var status = StatusProto.fromThrowable(e);
                        try {
                            assertThat(status.getDetails(0).unpack(RetryInfo.class).getRetryDelay().getSeconds())
                                    .isEqualTo(120);
                        } catch (Exception ex) {
                            throw new AssertionError(ex);
                        }
                    });
        }
    }
}
