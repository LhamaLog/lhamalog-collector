// Author: LhamaLog Desenvolvimento (IA) - LLT-182
package br.com.lhamalog.collector.messaging;

/** Keys of the envelope {@code metadata} map (ADR draft on Redis Streams messaging). */
public final class MetadataKeys {

	/** Owner of the message. It lives only in the metadata, never in the body. */
	public static final String TENANT_ID = "tenantId";

	public static final String PRODUCT_ID = "productId";

	public static final String CORRELATION_ID = "correlationId";

	public static final String TRACE_ID = "traceId";

	private MetadataKeys() {
	}

}
