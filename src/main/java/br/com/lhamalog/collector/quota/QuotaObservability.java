package br.com.lhamalog.collector.quota;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** Quota counters deliberately carry no tenant, product, reservation, or payload tags. */
public final class QuotaObservability {
	public static final QuotaObservability NOOP = new QuotaObservability(null);

	private final MeterRegistry meters;

	public QuotaObservability(MeterRegistry meters) {
		this.meters = meters;
	}

	public void authorized() { increment("lhamalog.quota.authorized"); }
	public void unlimited() { increment("lhamalog.quota.unlimited"); }
	public void insufficient() { increment("lhamalog.quota.insufficient"); }
	public void noPlan() { increment("lhamalog.quota.no_plan"); }
	public void coreUnavailable() { increment("lhamalog.quota.core_unavailable"); }
	public void released() { increment("lhamalog.quota.released"); }
	public void postDebitWriteFailure() { increment("lhamalog.quota.post_debit_write_failure"); }
	public void refundEnqueued() { increment("lhamalog.quota.refund_enqueued"); }
	public void refundSucceeded() { increment("lhamalog.quota.refund_succeeded"); }
	public void refundRetry() { increment("lhamalog.quota.refund_retry"); }
	public void refundSuspended() { increment("lhamalog.quota.refund_suspended"); }

	private void increment(String name) {
		if (meters != null) Counter.builder(name).register(meters).increment();
	}
}
