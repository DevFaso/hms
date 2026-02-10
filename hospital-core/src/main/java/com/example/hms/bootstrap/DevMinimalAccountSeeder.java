package com.example.hms.bootstrap;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile({"dev", "local"})
@Component
@Slf4j
public class DevMinimalAccountSeeder implements ApplicationRunner {

	@Override
	public void run(ApplicationArguments args) {
		log.debug("Dev minimal account seeder is disabled.");
	}
}
