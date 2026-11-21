package com.bindass.backend.model;

import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "newsletter_subscribers")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NewsletterSubscriber {

    @Id
    private String id;

    @Indexed(unique = true)
    private String email; // always stored lower-case

    @CreatedDate
    private LocalDateTime createdAt;
}
