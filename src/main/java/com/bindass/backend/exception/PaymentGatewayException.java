package com.bindass.backend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The payment provider could not be reached / rejected the request (HTTP 502). */
@ResponseStatus(HttpStatus.BAD_GATEWAY)
public class PaymentGatewayException extends RuntimeException {
    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
