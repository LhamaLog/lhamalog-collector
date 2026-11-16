package br.com.lhamalog.collector.quota;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Test-only fault switches. They are disabled unless explicitly enabled in an E2E profile. */
@ConfigurationProperties("lhamalog.quota.e2e-fault-injection")
public record QuotaE2eProperties(@DefaultValue("false") boolean enabled) {
}
