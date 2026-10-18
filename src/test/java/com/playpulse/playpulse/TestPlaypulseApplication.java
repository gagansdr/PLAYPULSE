package com.playpulse.playpulse;

import org.springframework.boot.SpringApplication;

public class TestPlaypulseApplication {

	public static void main(String[] args) {
		SpringApplication.from(PlaypulseApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
