# BINDASS.co — Backend

REST API for BINDASS, a premium unisex hoodie D2C brand. Built with Spring Boot + MongoDB.

## Tech Stack

- Java 17, Spring Boot 3.2.5
- MongoDB (Spring Data MongoDB)
- Spring Security + JWT
- Razorpay (payments) — HMAC-SHA256 signature verification
- Cloudinary (image hosting)

## Getting Started

1. Copy `.env.example` values into `src/main/resources/application.properties`
2. Run: `./mvnw spring-boot:run`
3. API at `http://localhost:5000`

## Known Gaps (in progress)

- No automated tests yet
- No refresh token rotation
- No rate limiting on auth endpoints
- No Docker/CI setup yet