package br.com.lhamalog.collector.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.lhamalog.collector.messaging.StreamNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class QueueSizeMetricsRefresherTest {

	@Test
	void refreshUpdatesGaugesFromXlen() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		@SuppressWarnings("unchecked")
		StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
		when(redis.opsForStream()).thenReturn(streams);
		when(streams.size(StreamNames.stream("logs"))).thenReturn(4L);
		when(streams.size(StreamNames.dlq("logs"))).thenReturn(1L);
		when(streams.size(StreamNames.stream("traces"))).thenReturn(2L);
		when(streams.size(StreamNames.dlq("traces"))).thenReturn(0L);

		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		QueueSizeMetricsRefresher refresher = new QueueSizeMetricsRefresher(redis, meters,
				new MetricsProperties(Duration.ofSeconds(15), Duration.ofSeconds(60)));

		assertThat(meters.find("lhamalog.queue.size").tag("signal", "logs").gauge().value())
				.isNaN();
		refresher.refresh();
		assertThat(meters.find("lhamalog.queue.size").tag("signal", "logs").gauge().value()).isEqualTo(4.0d);
		assertThat(meters.find("lhamalog.dlq.size").tag("signal", "logs").gauge().value()).isEqualTo(1.0d);
		assertThat(meters.find("lhamalog.queue.size").tag("signal", "traces").gauge().value()).isEqualTo(2.0d);
	}

	@Test
	void redisFailureKeepsLastValue() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		@SuppressWarnings("unchecked")
		StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
		when(redis.opsForStream()).thenReturn(streams);
		when(streams.size(StreamNames.stream("logs"))).thenReturn(7L).thenThrow(new RuntimeException("down"));
		when(streams.size(StreamNames.dlq("logs"))).thenReturn(0L);
		when(streams.size(StreamNames.stream("traces"))).thenReturn(0L);
		when(streams.size(StreamNames.dlq("traces"))).thenReturn(0L);

		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		QueueSizeMetricsRefresher refresher = new QueueSizeMetricsRefresher(redis, meters,
				new MetricsProperties(Duration.ofSeconds(15), Duration.ofSeconds(60)));
		refresher.refresh();
		double before = meters.find("lhamalog.queue.size").tag("signal", "logs").gauge().value();
		assertThat(before).isEqualTo(7.0d);
		refresher.refresh();
		assertThat(meters.find("lhamalog.queue.size").tag("signal", "logs").gauge().value()).isEqualTo(before);
	}
}
