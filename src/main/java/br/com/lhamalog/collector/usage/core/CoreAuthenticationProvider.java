package br.com.lhamalog.collector.usage.core;

import java.util.Optional;

public final class CoreAuthenticationProvider {

	public static final String ENVIRONMENT_VARIABLE = "LHAMALOG_COLLECTOR_CORE_TOKEN";

	private final String token;

	public CoreAuthenticationProvider(String token) {
		this.token = Optional.ofNullable(token).filter(value -> !value.isBlank())
			.orElseThrow(() -> new IllegalArgumentException(ENVIRONMENT_VARIABLE + " must be configured"));
	}

	public static CoreAuthenticationProvider fromEnvironment() {
		return new CoreAuthenticationProvider(System.getenv(ENVIRONMENT_VARIABLE));
	}

	public String serviceToken() {
		return token;
	}
}
