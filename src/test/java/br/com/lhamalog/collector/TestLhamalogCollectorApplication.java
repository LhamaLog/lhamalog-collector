package br.com.lhamalog.collector;

import org.springframework.boot.SpringApplication;

public class TestLhamalogCollectorApplication {

	public static void main(String[] args) {
		SpringApplication.from(LhamalogCollectorApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
