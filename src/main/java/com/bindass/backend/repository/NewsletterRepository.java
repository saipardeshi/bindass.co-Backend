package com.bindass.backend.repository;

import com.bindass.backend.model.NewsletterSubscriber;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NewsletterRepository extends MongoRepository<NewsletterSubscriber, String> {
    boolean existsByEmail(String email);
}
