// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.auth;

/**
 * Porta de validacao do token Bearer do produto (T007).
 *
 * <p>Recebe o valor ja extraido do header {@code Authorization} ou da metadata gRPC
 * {@code authorization}. A implementacao nao pode registrar esse valor em log.
 *
 * <p>Esta porta nao depende de {@code ProductToken} (T006) nem de {@code IngestionDecision}
 * (T005), para compilar antes das tasks do Michel. A regra de validade fica em
 * {@code ProductTokenValidity} (T008a), aplicada pelo adaptador MongoDB (T008).
 */
public interface TokenValidator {

	/**
	 * @param bearerToken valor do token sem o prefixo {@code Bearer }; pode ser nulo ou vazio
	 * @return produto e tenant do token, nao autorizado, ou catalogo indisponivel
	 */
	TokenValidationResult validate(String bearerToken);

}
