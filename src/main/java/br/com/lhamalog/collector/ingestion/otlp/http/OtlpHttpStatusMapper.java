// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.http;

import br.com.lhamalog.collector.ingestion.otlp.domain.IngestionDecision;
import br.com.lhamalog.collector.ingestion.otlp.sink.SinkUnavailableException;
import com.google.protobuf.Message;
import com.google.rpc.Code;
import com.google.rpc.Status;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsPartialSuccess;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.ExportTracePartialSuccess;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import java.util.Map;

/**
 * Decision to HTTP status map (T027a), per {@code contracts/otlp-export.md}: Unauthorized 401 with
 * {@code WWW-Authenticate: Bearer}; InvalidPayload 400, or 413 above the size limit, or 415 for an
 * unsupported encoding (only after the token was accepted: 401 comes first, T044); a batch refused by the
 * destination as invalid data is also 400, never 503 (T040); EmptySuccess and Accepted 200 without {@code partial_success}; PartiallyAccepted 200
 * with {@code rejected_log_records} or {@code rejected_spans} and {@code error_message}; CatalogUnavailable
 * and DestinationUnavailable 503 with {@code Retry-After}. Messages are in English.
 */
public final class OtlpHttpStatusMapper {

	public enum Signal {

		LOGS, TRACES

	}

	/** Status, extra headers and body (a {@code google.rpc.Status} or an {@code Export*ServiceResponse}). */
	public record HttpOutcome(int status, Map<String, String> headers, Message body) {
	}

	private OtlpHttpStatusMapper() {
	}

	public static HttpOutcome map(IngestionDecision decision, Signal signal) {
		return switch (decision) {
			case IngestionDecision.Unauthorized u ->
				new HttpOutcome(401, Map.of("WWW-Authenticate", "Bearer"), status(Code.UNAUTHENTICATED, "missing or invalid bearer token"));
			case IngestionDecision.CatalogUnavailable c -> unavailable(SinkUnavailableException.DEFAULT_RETRY_AFTER_SECONDS,
					"token catalog unavailable, retry later");
			case IngestionDecision.DestinationUnavailable d -> unavailable(d.retryAfterSeconds(), "destination unavailable, retry later");
			case IngestionDecision.QuotaExceeded q -> new HttpOutcome(429, Map.of("Retry-After", "120"),
					status(Code.RESOURCE_EXHAUSTED, "ingestion quota exhausted, retry later"));
			case IngestionDecision.QuotaUnavailable q -> unavailable(120, "ingestion quota unavailable, retry later");
			case IngestionDecision.InvalidPayload p -> switch (p.reason()) {
				case TOO_LARGE -> new HttpOutcome(413, Map.of(), status(Code.RESOURCE_EXHAUSTED, p.message()));
				case UNSUPPORTED_ENCODING -> new HttpOutcome(415, Map.of(), status(Code.INVALID_ARGUMENT, p.message()));
				case MALFORMED, ALL_RECORDS_INVALID, REJECTED_BY_DESTINATION -> new HttpOutcome(400, Map.of(), status(Code.INVALID_ARGUMENT, p.message()));
			};
			case IngestionDecision.EmptySuccess e -> new HttpOutcome(200, Map.of(), success(signal));
			case IngestionDecision.Accepted a -> new HttpOutcome(200, Map.of(), success(signal));
			case IngestionDecision.PartiallyAccepted p -> new HttpOutcome(200, Map.of(), partial(signal, p));
		};
	}

	public static HttpOutcome notFound() {
		return new HttpOutcome(404, Map.of(), status(Code.NOT_FOUND, "not found; supported paths are /v1/logs and /v1/traces"));
	}

	public static HttpOutcome methodNotAllowed() {
		return new HttpOutcome(405, Map.of("Allow", "POST"), status(Code.UNIMPLEMENTED, "method not allowed; use POST"));
	}

	/** Too many requests in progress on this instance (T046); the sender should retry. */
	public static HttpOutcome overloaded() {
		return unavailable(1, "receiver busy, retry later");
	}

	public static HttpOutcome rejected(IngestionDecision.Reason reason, String message) {
		return map(new IngestionDecision.InvalidPayload(reason, message), Signal.LOGS);
	}

	private static HttpOutcome unavailable(long retryAfterSeconds, String message) {
		return new HttpOutcome(503, Map.of("Retry-After", Long.toString(retryAfterSeconds)), status(Code.UNAVAILABLE, message));
	}

	private static Status status(Code code, String message) {
		return Status.newBuilder().setCode(code.getNumber()).setMessage(message).build();
	}

	private static Message success(Signal signal) {
		return signal == Signal.LOGS ? ExportLogsServiceResponse.getDefaultInstance() : ExportTraceServiceResponse.getDefaultInstance();
	}

	private static Message partial(Signal signal, IngestionDecision.PartiallyAccepted p) {
		return signal == Signal.LOGS
				? ExportLogsServiceResponse.newBuilder()
					.setPartialSuccess(ExportLogsPartialSuccess.newBuilder()
						.setRejectedLogRecords(p.rejectedCount())
						.setErrorMessage(p.errorMessage()))
					.build()
				: ExportTraceServiceResponse.newBuilder()
					.setPartialSuccess(ExportTracePartialSuccess.newBuilder()
						.setRejectedSpans(p.rejectedCount())
						.setErrorMessage(p.errorMessage()))
					.build();
	}

}
