package br.com.lhamalog.collector.usage.core;

import br.com.lhamalog.collector.usage.domain.UsageFact;
import java.io.IOException;

@FunctionalInterface
public interface UsageFactsClient {

	int send(UsageFact fact) throws IOException, InterruptedException;
}
