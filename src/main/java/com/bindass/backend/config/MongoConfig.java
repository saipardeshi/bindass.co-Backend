package com.bindass.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;

// Enables @CreatedDate and @LastModifiedDate in all models
@Configuration
@EnableMongoAuditing
public class MongoConfig {
    // No extra code needed — annotation does the work
}