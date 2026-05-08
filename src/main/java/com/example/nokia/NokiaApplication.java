package com.example.nokia;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NokiaApplication {

    public static void main(String[] args) {
        SpringApplication.run(NokiaApplication.class, args);
    }
}
