// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.http;

import java.time.Duration;

/**
 * Protection limits of the JDK HTTP server used by the OTLP/HTTP receiver (T046, FR-025).
 *
 * <p>The JDK server ({@code com.sun.net.httpserver}) is not an edge server: in production it must sit behind
 * a reverse proxy or load balancer that does TLS, slow client protection and rate limiting. These limits only
 * keep one collector instance from being exhausted.
 *
 * @param maxConcurrentRequests requests handled at the same time; above it the answer is 503 with
 * {@code Retry-After}, without reading the body
 * @param maxConnections open connections ({@code sun.net.httpserver.maxConnections})
 * @param readTimeout time to receive a whole request ({@code sun.net.httpserver.maxReqTime})
 * @param writeTimeout time to send a whole response ({@code sun.net.httpserver.maxRspTime})
 * @param idleTimeout time an idle keep-alive connection stays open ({@code sun.net.httpserver.idleInterval})
 * @param maxIdleConnections idle keep-alive connections kept ({@code sun.net.httpserver.maxIdleConnections})
 */
public record HttpLimits(int maxConcurrentRequests, int maxConnections, Duration readTimeout, Duration writeTimeout,
		Duration idleTimeout, int maxIdleConnections) {

	public static final HttpLimits DEFAULT = new HttpLimits(200, 1000, Duration.ofSeconds(30), Duration.ofSeconds(30),
			Duration.ofSeconds(30), 200);

	public HttpLimits {
		if (maxConcurrentRequests <= 0 || maxConnections <= 0 || maxIdleConnections < 0) {
			throw new IllegalArgumentException(
					"OTLP/HTTP limits: max-concurrent-requests and max-connections must be positive, max-idle-connections not negative");
		}
		requireSeconds(readTimeout, "read-timeout");
		requireSeconds(writeTimeout, "write-timeout");
		requireSeconds(idleTimeout, "idle-timeout");
	}

	private static void requireSeconds(Duration value, String name) {
		if (value == null || value.toSeconds() < 1) {
			throw new IllegalArgumentException("OTLP/HTTP limits: " + name + " must be at least 1 second");
		}
	}

}
