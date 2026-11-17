package br.com.lhamalog.collector.config;

import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

/**
 * Management scrape endpoints are public; everything else stays authenticated (Boot default
 * behavior once a {@link SecurityFilterChain} bean exists).
 */
@Configuration
public class ActuatorSecurityConfiguration {

	@Bean
	@Order(0)
	SecurityFilterChain managementEndpointsSecurityFilterChain(HttpSecurity http) throws Exception {
		PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
		http.securityMatcher(new OrRequestMatcher(
						EndpointRequest.to("health", "info", "prometheus"),
						paths.matcher("/actuator/health"),
						paths.matcher("/actuator/health/**"),
						paths.matcher("/actuator/info"),
						paths.matcher("/actuator/prometheus")))
				.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
				.csrf(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable);
		return http.build();
	}

	@Bean
	@Order(1)
	SecurityFilterChain applicationSecurityFilterChain(HttpSecurity http) throws Exception {
		// Mirrors Spring Boot's default chain (form login + HTTP Basic, CSRF kept on), which is no longer
		// auto-configured once any SecurityFilterChain bean is declared.
		http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
				.formLogin(Customizer.withDefaults())
				.httpBasic(Customizer.withDefaults());
		return http.build();
	}
}
