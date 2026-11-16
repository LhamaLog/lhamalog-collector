package br.com.lhamalog.collector.quota;

import br.com.lhamalog.collector.ingestion.otlp.application.QuotaGate;
import br.com.lhamalog.collector.ingestion.otlp.domain.Scoped;
import br.com.lhamalog.collector.usage.domain.CanonicalOtlpMeasurement;
import br.com.lhamalog.collector.usage.domain.UsageDomain;
import com.google.protobuf.MessageLite;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.time.Clock;

/** Adapts the spec005 canonical OTLP measurement to a deterministic Core reservation request. */
public final class CanonicalQuotaGate<R, T> implements QuotaGate<R, T> {
	private final QuotaReservationClient client;
	private final RefundOutboxRepository refunds;
	private final QuotaObservability observability;

	public CanonicalQuotaGate(QuotaReservationClient client) {
		this(client, null, QuotaObservability.NOOP);
	}

	public CanonicalQuotaGate(QuotaReservationClient client, RefundOutboxRepository refunds) {
		this(client, refunds, QuotaObservability.NOOP);
	}

	public CanonicalQuotaGate(QuotaReservationClient client, RefundOutboxRepository refunds,
			QuotaObservability observability) {
		this.client = client;
		this.refunds = refunds;
		this.observability = observability;
	}

	@Override
	public Reservation reserve(String tenantId, String productId, R request, List<T> accepted) {
		Measurement measurement = measurement(request, accepted);
		String reservationId = "quota:" + digest(tenantId + "\n" + productId + "\n" + measurement.domain + "\n"
				+ HexFormat.of().formatHex(measurement.message.toByteArray()));
		QuotaReservationClient.ReservationOutcome outcome = client.reserve(new QuotaReservationClient.ReservationRequest(
				reservationId, tenantId, productId, measurement.bytes, reservationId, measurement.domain));
		return new Reservation(switch (outcome) {
			case AUTHORIZED -> {
				observability.authorized();
				yield Reservation.Outcome.AUTHORIZED;
			}
			case AUTHORIZED_UNLIMITED -> {
				observability.unlimited();
				yield Reservation.Outcome.AUTHORIZED;
			}
			case INSUFFICIENT_BALANCE -> {
				observability.insufficient();
				yield Reservation.Outcome.INSUFFICIENT_BALANCE;
			}
			case NO_PLAN -> {
				observability.noPlan();
				yield Reservation.Outcome.NO_PLAN;
			}
		}, reservationId);
	}

	@Override
	public void confirm(String reservationId) {
		client.confirm(reservationId);
	}

	@Override
	public void release(String reservationId) {
		client.release(reservationId);
		observability.released();
	}

	@Override
	public void refund(String reservationId, String reason) {
		if (refunds != null) {
			refunds.enqueue(reservationId, reason, Clock.systemUTC().instant());
			observability.refundEnqueued();
		}
	}

	@Override
	public void coreUnavailable() {
		observability.coreUnavailable();
	}

	@Override
	public void postDebitWriteFailure() {
		observability.postDebitWriteFailure();
	}

	@SuppressWarnings("unchecked")
	private Measurement measurement(R request, List<T> accepted) {
		if (request instanceof ExportLogsServiceRequest logs) {
			CanonicalOtlpMeasurement value = CanonicalOtlpMeasurement.logs(logs,
					(List<Scoped<io.opentelemetry.proto.logs.v1.LogRecord>>) (List<?>) accepted);
			return new Measurement(value.domain(), value.acceptedRequest(), value.serializedBytes());
		}
		if (request instanceof ExportTraceServiceRequest traces) {
			CanonicalOtlpMeasurement value = CanonicalOtlpMeasurement.traces(traces,
					(List<Scoped<io.opentelemetry.proto.trace.v1.Span>>) (List<?>) accepted);
			return new Measurement(value.domain(), value.acceptedRequest(), value.serializedBytes());
		}
		throw new IllegalArgumentException("unsupported OTLP request for quota");
	}

	private static String digest(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private record Measurement(UsageDomain domain, MessageLite message, long bytes) {
	}
}
