package com.assessment.controller;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.DisconnectedClientHelper;

@RestControllerAdvice
public class PrivateObjectErrors {
    @ExceptionHandler({IOException.class, UncheckedIOException.class})
    public ResponseEntity<Map<String, String>> integrityError(Exception error) {
        if (DisconnectedClientHelper.isClientDisconnectedException(error)) return null;
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("message", "The private document or assessment record could not be authenticated or processed"));
    }
}
