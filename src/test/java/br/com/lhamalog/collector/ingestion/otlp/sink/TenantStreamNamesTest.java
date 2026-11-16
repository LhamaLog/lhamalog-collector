package br.com.lhamalog.collector.ingestion.otlp.sink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class TenantStreamNamesTest {

	@Test
	void plainLowercaseTenantKeepsSimpleName() {
		assertThat(TenantStreamNames.resolve("logs", "acme")).isEqualTo("logs_acme");
		assertThat(TenantStreamNames.resolve("traces", "acme")).isEqualTo("traces_acme");
	}

	@Test
	void sanitizationAddsHashSoCollidingIdsDiffer() {
		String orgA = TenantStreamNames.resolve("logs", "Org-A");
		String orgUnderscore = TenantStreamNames.resolve("logs", "org_a");
		assertThat(orgA).startsWith("logs_org_a_");
		assertThat(orgA).hasSize("logs_org_a_".length() + 8);
		assertThat(orgUnderscore).isEqualTo("logs_org_a");
		assertThat(orgA).isNotEqualTo(orgUnderscore);
		assertThat(orgA).endsWith(hash8("Org-A"));
	}

	@Test
	void truncationAddsHash() {
		String longId = "a".repeat(50);
		String name = TenantStreamNames.resolve("logs", longId);
		assertThat(name).isEqualTo("logs_" + "a".repeat(40) + "_" + hash8(longId));
	}

	@Test
	void rejectsBlank() {
		assertThatThrownBy(() -> TenantStreamNames.resolve("logs", " "))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> TenantStreamNames.resolve(" ", "t"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private static String hash8(String tenantId) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(tenantId.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest, 0, 4);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}
}
