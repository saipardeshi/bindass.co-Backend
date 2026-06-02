package com.bindass.backend.repository;

import com.bindass.backend.model.User;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

// MongoRepository<User, String> gives us free CRUD methods:
// save(), findById(), findAll(), deleteById(), count() etc.
@Repository
public interface UserRepository extends MongoRepository<User, String> {

    // Spring Data auto-generates query from method name:
    // "find User where email = ?"
    Optional<User> findByEmail(String email);

    // Check if email already exists (for registration validation)
    boolean existsByEmail(String email);

    // Find user by password reset token
    Optional<User> findByResetPasswordToken(String token);
}