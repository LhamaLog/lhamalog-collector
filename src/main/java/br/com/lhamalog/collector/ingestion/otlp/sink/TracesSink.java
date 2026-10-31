// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.sink;

import io.opentelemetry.proto.trace.v1.Span;
import java.util.List;

/**
 * Porta de destino dos spans aceitos (T012).
 *
 * <p>Recebe so spans ja classificados como validos. {@code tenantId} vem do produto do token e
 * {@code productId} do token; nenhum valor do lote os substitui. A implementacao grava os dois em
 * cada span.
 */
public interface TracesSink {

	/**
	 * Entrega os spans e so retorna depois que o destino confirmou todos eles.
	 *
	 * @throws SinkUnavailableException se o destino estiver fora ou nao confirmar o recebimento
	 */
	void deliver(String tenantId, String productId, List<Span> acceptedSpans);

}
