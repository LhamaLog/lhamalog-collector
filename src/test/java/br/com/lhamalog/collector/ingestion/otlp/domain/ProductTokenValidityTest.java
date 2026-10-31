// Author: LhamaLog Desenvolvimento (IA) - LLT-181
package br.com.lhamalog.collector.ingestion.otlp.domain;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.lhamalog.collector.ingestion.otlp.domain.ProductToken.Status;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductTokenValidityTest {

	private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");

	private static ProductToken token(Status status, Instant revokedAt, Instant expiresAt) {
		return new ProductToken("t1", "product-a", "hash", status, NOW.minusSeconds(3600), revokedAt, expiresAt);
	}

	@Test
	void activeTokenWithoutExpiryIsUsable() {
		assertThat(ProductTokenValidity.usableToken(List.of(token(Status.ACTIVE, null, null)), NOW)).isPresent();
	}

	@Test
	void tokenExpiringInTheFutureIsUsable() {
		assertThat(ProductTokenValidity.usableToken(List.of(token(Status.ACTIVE, null, NOW.plusSeconds(1))), NOW)).isPresent();
	}

	@Test
	void expiredTokenIsRefused() {
		assertThat(ProductTokenValidity.usableToken(List.of(token(Status.ACTIVE, null, NOW)), NOW)).isEmpty();
		assertThat(ProductTokenValidity.usableToken(List.of(token(Status.ACTIVE, null, NOW.minusSeconds(1))), NOW)).isEmpty();
	}

	@Test
	void revokedTokenIsRefused() {
		assertThat(ProductTokenValidity.usableToken(List.of(token(Status.REVOKED, NOW.minusSeconds(10), null)), NOW)).isEmpty();
	}

	@Test
	void activeTokenWithRevokedAtBreaksInvariantsAndIsRefused() {
		assertThat(ProductTokenValidity.usableToken(List.of(token(Status.ACTIVE, NOW.minusSeconds(10), null)), NOW)).isEmpty();
	}

	@Test
	void unknownOrDuplicatedHashIsRefused() {
		ProductToken ok = token(Status.ACTIVE, null, null);
		assertThat(ProductTokenValidity.usableToken(List.of(), NOW)).isEmpty();
		assertThat(ProductTokenValidity.usableToken(List.of(ok, ok), NOW)).isEmpty();
	}

	@Test
	void productMustExistWithTenant() {
		assertThat(ProductTokenValidity.tenantOf(true, "tenant-1")).contains("tenant-1");
		assertThat(ProductTokenValidity.tenantOf(false, "tenant-1")).isEmpty();
		assertThat(ProductTokenValidity.tenantOf(true, null)).isEmpty();
		assertThat(ProductTokenValidity.tenantOf(true, "  ")).isEmpty();
	}

}
