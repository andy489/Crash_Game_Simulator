package com.crash;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class CrashSimulator {
    public static void main(String[] args) {
        SpringApplication.run(CrashSimulator.class, args);
    }
}
