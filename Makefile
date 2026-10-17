ifeq ($(OS),Windows_NT)
	MVNW := mvnw.cmd
else
	MVNW := ./mvnw
endif

run:
	$(MVNW) spring-boot:run

build:
	$(MVNW) clean package

test:
	$(MVNW) test

verify:
	$(MVNW) clean verify