// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.domain;

import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.resource.v1.Resource;
import java.util.Objects;

/**
 * One OTLP record (log or span) together with the resource and the instrumentation scope it was sent under
 * (T041). The sinks need both to keep {@code service.name}, the resource attributes and the scope name and
 * version in the stored document.
 *
 * @param resource resource of the {@code ResourceLogs}/{@code ResourceSpans} entry, never {@code null}
 * @param scope scope of the {@code ScopeLogs}/{@code ScopeSpans} entry, never {@code null}
 * @param record the log record or span itself
 */
public record Scoped<T>(Resource resource, InstrumentationScope scope, T record) {

	public Scoped {
		Objects.requireNonNull(resource, "resource");
		Objects.requireNonNull(scope, "scope");
		Objects.requireNonNull(record, "record");
	}

	/** A record without resource nor scope, as sent by a minimal exporter. */
	public static <T> Scoped<T> of(T record) {
		return new Scoped<>(Resource.getDefaultInstance(), InstrumentationScope.getDefaultInstance(), record);
	}

}
