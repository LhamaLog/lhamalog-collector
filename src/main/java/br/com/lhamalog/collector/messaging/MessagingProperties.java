// Author: LhamaLog Desenvolvimento (IA) - LLT-184
package br.com.lhamalog.collector.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Stream length limits and publish retry policy, bound from {@code lhamalog.messaging.*} (and the
 * {@code LHAMALOG_MESSAGING_*} environment variables wired in {@code application-*.properties}).
 */
@ConfigurationProperties("lhamalog.messaging")
public record MessagingProperties(@DefaultValue Streams streams, @DefaultValue Publish publish) {

	public static final long DEFAULT_MAX_LEN = 100_000L;

	public static final int DEFAULT_PUBLISH_MAX_ATTEMPTS = 3;

	public static final long DEFAULT_PUBLISH_BACKOFF_INITIAL_MS = 100L;

	public static final double DEFAULT_PUBLISH_BACKOFF_MULTIPLIER = 2.0d;

	public static final long DEFAULT_PUBLISH_BACKOFF_CEILING_MS = 2_000L;

	public MessagingProperties {
		streams.requirePositive("lhamalog.messaging.streams");
		publish.requireValid("lhamalog.messaging.publish");
	}

	/** Defaults for unit tests that do not start a Spring context. */
	public static MessagingProperties defaults() {
		return new MessagingProperties(Streams.defaults(), Publish.defaults());
	}

	public long maxLenFor(String domain) {
		return streams.maxLenFor(domain);
	}

	public int publishMaxAttempts() {
		return publish.maxAttempts();
	}

	public long publishBackoffInitialMs() {
		return publish.backoffInitialMs();
	}

	public double publishBackoffMultiplier() {
		return publish.backoffMultiplier();
	}

	public long publishBackoffCeilingMs() {
		return publish.backoffCeilingMs();
	}

	public record Streams(
			@DefaultValue("100000") long defaultMaxLen,
			@DefaultValue("100000") long logsMaxLen,
			@DefaultValue("100000") long tracesMaxLen,
			@DefaultValue("100000") long logsDlqMaxLen,
			@DefaultValue("100000") long tracesDlqMaxLen) {

		public static Streams defaults() {
			return new Streams(DEFAULT_MAX_LEN, DEFAULT_MAX_LEN, DEFAULT_MAX_LEN, DEFAULT_MAX_LEN, DEFAULT_MAX_LEN);
		}

		long maxLenFor(String domain) {
			return switch (domain) {
				case "logs" -> logsMaxLen;
				case "traces" -> tracesMaxLen;
				case "logs:dlq" -> logsDlqMaxLen;
				case "traces:dlq" -> tracesDlqMaxLen;
				default -> defaultMaxLen;
			};
		}

		void requirePositive(String prefix) {
			requirePositive(prefix + ".default-max-len", defaultMaxLen);
			requirePositive(prefix + ".logs-max-len", logsMaxLen);
			requirePositive(prefix + ".traces-max-len", tracesMaxLen);
			requirePositive(prefix + ".logs-dlq-max-len", logsDlqMaxLen);
			requirePositive(prefix + ".traces-dlq-max-len", tracesDlqMaxLen);
		}

		private static void requirePositive(String name, long value) {
			if (value <= 0) {
				throw new IllegalArgumentException(name + " must be a positive integer");
			}
		}

	}

	public record Publish(
			@DefaultValue("3") int maxAttempts,
			@DefaultValue("100") long backoffInitialMs,
			@DefaultValue("2") double backoffMultiplier,
			@DefaultValue("2000") long backoffCeilingMs) {

		public static Publish defaults() {
			return new Publish(DEFAULT_PUBLISH_MAX_ATTEMPTS, DEFAULT_PUBLISH_BACKOFF_INITIAL_MS,
					DEFAULT_PUBLISH_BACKOFF_MULTIPLIER, DEFAULT_PUBLISH_BACKOFF_CEILING_MS);
		}

		void requireValid(String prefix) {
			if (maxAttempts <= 0) {
				throw new IllegalArgumentException(prefix + ".max-attempts must be a positive integer");
			}
			if (backoffInitialMs <= 0) {
				throw new IllegalArgumentException(prefix + ".backoff-initial-ms must be a positive integer");
			}
			if (backoffMultiplier < 1.0d) {
				throw new IllegalArgumentException(prefix + ".backoff-multiplier must be >= 1");
			}
			if (backoffCeilingMs <= 0) {
				throw new IllegalArgumentException(prefix + ".backoff-ceiling-ms must be a positive integer");
			}
		}

	}

}