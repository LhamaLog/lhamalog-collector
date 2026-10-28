package br.com.lhamalog.collector.config;

import br.com.lhamalog.collector.ingestion.otlp.application.LoggingBatchSink;
import br.com.lhamalog.collector.ingestion.otlp.application.LogsBatchSink;
import br.com.lhamalog.collector.ingestion.otlp.application.OtlpIngestionService;
import br.com.lhamalog.collector.ingestion.otlp.application.TracesBatchSink;
import br.com.lhamalog.collector.ingestion.otlp.auth.PropertiesTokenValidator;
import br.com.lhamalog.collector.ingestion.otlp.auth.TokenValidator;
import br.com.lhamalog.collector.ingestion.otlp.grpc.OtlpGrpcServer;
import br.com.lhamalog.collector.ingestion.otlp.http.OtlpHttpServer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Liga os receptores OTLP (LLT-181). Os servidores HTTP (4318) e gRPC (4317) sao independentes do Tomcat
 * da aplicacao (8080) e do Spring Security: a autenticacao OTLP e pelo token Bearer do produto.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OtlpReceiverProperties.class)
public class OtlpReceiverConfiguration {

    @Bean
    @ConditionalOnMissingBean
    TokenValidator tokenValidator(OtlpReceiverProperties properties) {
        return new PropertiesTokenValidator(properties.auth().validTokens(), properties.auth().revokedTokens());
    }

    @Bean
    LoggingBatchSink loggingBatchSink() {
        return new LoggingBatchSink();
    }

    @Bean
    OtlpIngestionService otlpIngestionService(LogsBatchSink logsSink, TracesBatchSink tracesSink) {
        return new OtlpIngestionService(logsSink, tracesSink);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    @ConditionalOnProperty(name = "lhamalog.otlp.http.enabled", havingValue = "true", matchIfMissing = true)
    OtlpHttpServer otlpHttpServer(OtlpReceiverProperties properties, TokenValidator tokenValidator,
            OtlpIngestionService ingestion) {
        OtlpReceiverProperties.Http http = properties.http();
        return new OtlpHttpServer(http.host(), http.port(), http.maxRequestSize().toBytes(), tokenValidator, ingestion);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    @ConditionalOnProperty(name = "lhamalog.otlp.grpc.enabled", havingValue = "true", matchIfMissing = true)
    OtlpGrpcServer otlpGrpcServer(OtlpReceiverProperties properties, TokenValidator tokenValidator,
            OtlpIngestionService ingestion) {
        OtlpReceiverProperties.Grpc grpc = properties.grpc();
        return new OtlpGrpcServer(grpc.host(), grpc.port(), (int) grpc.maxMessageSize().toBytes(), tokenValidator,
                ingestion);
    }
}
