package br.com.lhamalog.collector.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Log padrão do LhamaLog informando que o serviço iniciou (ADR_149).
 * Formato: [LhamaLog] NOME iniciado | versao=X | porta=Y | perfis=Z | java=N
 */
@Component
public class StartupLogger {

    private static final Logger log = LoggerFactory.getLogger(StartupLogger.class);

    private final Environment environment;
    private final ObjectProvider<BuildProperties> buildProperties;

    public StartupLogger(Environment environment, ObjectProvider<BuildProperties> buildProperties) {
        this.environment = environment;
        this.buildProperties = buildProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        String name = environment.getProperty("spring.application.name", "lhamalog-collector");
        BuildProperties build = buildProperties.getIfAvailable();
        String version = build != null ? build.getVersion() : "dev";
        String port = environment.getProperty("local.server.port", "-");
        // Sem perfil ativo explicito, o Spring usa spring.profiles.default (local, ADR_151).
        String[] active = environment.getActiveProfiles();
        if (active.length == 0) {
            active = environment.getDefaultProfiles();
        }
        String profiles = String.join(",", active);
        String java = String.valueOf(Runtime.version().feature());
        boolean usageEnabled = environment.getProperty("lhamalog.usage.enabled", Boolean.class, Boolean.FALSE);
        log.info("[LhamaLog] {} iniciado | versao={} | porta={} | perfis={} | java={} | usage.enabled={}", name, version, port, profiles, java, usageEnabled);
    }
}
