// Author: LhamaLog Desenvolvimento (IA) - LLT-184
package br.com.lhamalog.collector.messaging;

/** Wait between publish retries; tests inject a no-op or recording sleeper. */
@FunctionalInterface
public interface PublishSleeper {

	void sleep(long millis) throws InterruptedException;

}