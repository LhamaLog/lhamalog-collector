package br.com.lhamalog.collector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LhamalogCollectorApplication {

	public static void main(String[] args) {
		SpringApplication.run(LhamalogCollectorApplication.class, args);
	}

}
