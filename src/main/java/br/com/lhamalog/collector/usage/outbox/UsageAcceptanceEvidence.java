package br.com.lhamalog.collector.usage.outbox;

@FunctionalInterface
public interface UsageAcceptanceEvidence {

	/**
	 * @return null when the Redis key is absent, otherwise CLAIMED, DONE or
	 * ABANDONED evidence. Values are deliberately opaque after their prefix.
	 */
	String read(String key);

	/**
	 * Fences a delayed dispatcher: absence becomes ABANDONED atomically before
	 * the reconciler changes Mongo. Implementations backed by Redis must
	 * override this method; the default keeps simple test doubles read-only.
	 */
	default String resolvePending(String key) {
		String value = read(key);
		return value == null ? "ABANDONED" : value;
	}
}
