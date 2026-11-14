package com.daumjari;

import org.springframework.boot.SpringApplication;

public class TestDaumjariApplication {

	public static void main(String[] args) {
		SpringApplication.from(DaumjariApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
