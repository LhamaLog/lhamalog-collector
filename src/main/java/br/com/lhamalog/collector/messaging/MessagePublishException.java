// Author: LhamaLog Desenvolvimento (IA) - LLT-182
package br.com.lhamalog.collector.messaging;

/** Redis did not confirm every XADD of a publish call. Retrying the same input is safe. */
public class MessagePublishException extends RuntimeException {

	public MessagePublishException(String message, Throwable cause) {
		super(message, cause);
	}

}
