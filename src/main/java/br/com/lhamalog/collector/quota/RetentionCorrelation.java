package br.com.lhamalog.collector.quota;

/**
 * Request-scoped retention days from a successful Core reservation (LLT-185). Set after confirm and cleared
 * before the OTLP handler returns so sinks never observe a stale value.
 */
public final class RetentionCorrelation {
	private static final ThreadLocal<Integer> CURRENT = new ThreadLocal<>();

	private RetentionCorrelation() {
	}

	/** Stores a known retention (&gt; 0); null or non-positive clears. */
	public static void set(Integer retentionDays) {
		if (retentionDays == null || retentionDays <= 0) {
			CURRENT.remove();
		}
		else {
			CURRENT.set(retentionDays);
		}
	}

	/** Known retention days, or {@code null} when unknown. */
	public static Integer current() {
		return CURRENT.get();
	}

	public static void clear() {
		CURRENT.remove();
	}
}
