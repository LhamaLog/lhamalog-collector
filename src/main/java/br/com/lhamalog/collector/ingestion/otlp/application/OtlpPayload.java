// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.application;

/**
 * Lazy reader of an export body. The services call it only after the token is accepted, so an
 * unauthenticated sender never gets its batch decoded.
 */
@FunctionalInterface
public interface OtlpPayload<T> {

	T read() throws PayloadRejectedException;

}
