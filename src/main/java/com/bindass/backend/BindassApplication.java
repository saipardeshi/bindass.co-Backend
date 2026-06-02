package com.bindass.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Entry point — @SpringBootApplication enables:
// auto-configuration, component scanning, and configuration properties
@SpringBootApplication
public class BindassApplication {
    public static void main(String[] args) {
        SpringApplication.run(BindassApplication.class, args);
        System.out.println("\n🖤 BINDASS Backend started successfully!\n");
    }
}