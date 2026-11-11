package br.com.lhamalog.collector.usage.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CoreAuthenticationProviderTest {

	@Test
	void trimsWhitespaceCrlfAndSurroundingQuotes() {
		assertThat(new CoreAuthenticationProvider("  secret\r\n ").serviceToken()).isEqualTo("secret");
		assertThat(new CoreAuthenticationProvider("\"secret\"").serviceToken()).isEqualTo("secret");
		assertThat(new CoreAuthenticationProvider("'secret'").serviceToken()).isEqualTo("secret");
		assertThatThrownBy(() -> new CoreAuthenticationProvider("  \r\n  "))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void fingerprintExposesOnlyLengthAndHash() {
		CoreAuthenticationProvider provider = new CoreAuthenticationProvider("secret-value");
		assertThat(CoreAuthenticationProvider.sha256Hex("secret-value")).hasSize(64);
		assertThat(CoreAuthenticationProvider.sha256Hex(provider.serviceToken()))
				.isEqualTo(CoreAuthenticationProvider.sha256Hex("secret-value"));
		assertThat(provider.serviceToken()).isEqualTo("secret-value");
	}
}
