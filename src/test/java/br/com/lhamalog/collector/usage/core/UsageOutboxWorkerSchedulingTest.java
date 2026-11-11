package br.com.lhamalog.collector.usage.core;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.config.UsageProperties;
import br.com.lhamalog.collector.usage.domain.UsageDomain;
import br.com.lhamalog.collector.usage.domain.UsageFact;
import br.com.lhamalog.collector.usage.outbox.UsageOutboxItem;
import br.com.lhamalog.collector.usage.outbox.UsageOutboxRepository;
import br.com.lhamalog.collector.usage.outbox.UsageOutboxState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

class UsageOutboxWorkerSchedulingTest {

	@Test
	void scheduledWorkerPollsTheOutboxWithoutBeingCalledManually() throws InterruptedException {
		AtomicInteger polls = new AtomicInteger();
		new ApplicationContextRunner()
				.withUserConfiguration(SchedulingSample.class)
				.withBean(AtomicInteger.class, () -> polls)
				.withPropertyValues("lhamalog.usage.worker-interval=150ms")
				.run(context -> {
					Thread.sleep(600);
					assertThat(polls.get()).isGreaterThan(0);
				});
	}

	@Configuration
	@EnableScheduling
	static class SchedulingSample {
		@Bean
		UsageOutboxWorker usageOutboxWorker(AtomicInteger polls) {
			UsageOutboxRepository outbox = new UsageOutboxRepository() {
				@Override
				public UsageOutboxItem insertPending(UsageFact fact, Instant now) {
					throw new UnsupportedOperationException();
				}

				@Override
				public Optional<UsageOutboxItem> findById(String usageFactId) {
					return Optional.empty();
				}

				@Override
				public List<UsageOutboxItem> findPendingBefore(Instant instant) {
					return List.of();
				}

				@Override
				public List<UsageOutboxItem> findAcceptedReady(Instant instant) {
					polls.incrementAndGet();
					return List.of();
				}

				@Override
				public boolean compareAndSet(String usageFactId, UsageOutboxState expected, UsageOutboxItem replacement) {
					return false;
				}

				@Override
				public boolean compareAndSet(UsageOutboxItem expected, UsageOutboxItem replacement) {
					return false;
				}
			};
			UsageProperties properties = new UsageProperties(true, new UsageProperties.Core("http://127.0.0.1:8081"),
					Duration.ofSeconds(30), Duration.ofMillis(150), Duration.ofMinutes(2), Duration.ofDays(7),
					Duration.ofMillis(250), Duration.ofSeconds(30));
			return new UsageOutboxWorker(outbox, fact -> 201, properties, Clock.systemUTC());
		}
	}
}
