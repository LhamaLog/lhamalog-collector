MVNW := ./mvnw

.PHONY: run build test verify

run:
	$(MVNW) spring-boot:run

build:
	$(MVNW) clean package

test:
	$(MVNW) test

verify:
	$(MVNW) clean verify
