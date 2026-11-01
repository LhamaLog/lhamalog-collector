// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.sink;

import br.com.lhamalog.collector.ingestion.otlp.domain.Scoped;
import io.opentelemetry.proto.logs.v1.LogRecord;
import java.util.List;

/**
 * Porta de destino dos logs aceitos (T011, T041).
 *
 * <p>Recebe so registros ja classificados como validos, cada um com o resource e o scope de origem.
 * {@code tenantId} vem do produto do token e {@code productId} do token; nenhum valor do lote os substitui.
 * A implementacao grava os dois em cada registro.
 */
public interface LogsSink {

	/**
	 * Entrega os logs e so retorna depois que o destino confirmou todos eles.
	 *
	 * @throws SinkUnavailableException se o destino estiver fora ou nao confirmar o recebimento
	 * @throws SinkRejectedException se o destino recusar registros como dado invalido
	 */
	void deliver(String tenantId, String productId, List<Scoped<LogRecord>> acceptedRecords);

}
