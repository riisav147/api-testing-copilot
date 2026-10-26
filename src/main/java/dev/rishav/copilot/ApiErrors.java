package dev.rishav.copilot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger log=LoggerFactory.getLogger(ApiErrors.class);
    @ExceptionHandler(ApiException.class) ResponseEntity<ProblemDetail> application(ApiException e) { return problem(e.status(),e.getMessage()); }
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> invalid(Exception e) { return problem(HttpStatus.BAD_REQUEST,"Invalid request. Check required fields, value types and execution approval."); }
    @ExceptionHandler(Exception.class) ResponseEntity<ProblemDetail> unexpected(Exception e) {
        String reference=UUID.randomUUID().toString();
        // Do not log provider messages, prompts, specifications, credentials or response payloads.
        log.error("Request failed: reference={}, exceptionType={}",reference,e.getClass().getSimpleName());
        return problem(HttpStatus.INTERNAL_SERVER_ERROR,"The operation failed. Reference: "+reference);
    }
    private ResponseEntity<ProblemDetail> problem(HttpStatus status,String message) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status,message));
    }
}
