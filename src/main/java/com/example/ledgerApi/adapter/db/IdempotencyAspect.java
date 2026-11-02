package com.example.ledgerApi.adapter.db;

import com.example.ledgerApi.config.Idempotent;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

@Aspect
@Component
public class IdempotencyAspect {

    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    public IdempotencyAspect(IdempotencyRepository idempotencyRepository, ObjectMapper objectMapper) {
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(Idempotent)")
    public Object handleIdempotency(ProceedingJoinPoint joinPoint) throws Throwable {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        String idempotencyKey = request.getHeader("Idempotency-Key");

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return joinPoint.proceed();
        }

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Idempotent annotation = signature.getMethod().getAnnotation(Idempotent.class);
        String operation = annotation.operation();

        Object[] args = joinPoint.getArgs();
        String requestBodyJson = args.length > 0 ? objectMapper.writeValueAsString(args[0]) : "";
        String requestHash = sha256(requestBodyJson);

        Optional<IdempotencyRepository.SavedResponse> existing = idempotencyRepository.findByKey(idempotencyKey);
        if (existing.isPresent()) {
            var saved = existing.get();
            Class<?> returnType = signature.getReturnType();
            Object bodyObj = objectMapper.readValue(saved.body(), returnType);
            return ResponseEntity.status(saved.status()).body(bodyObj);
        }

        Object result = joinPoint.proceed();

        if (result instanceof ResponseEntity<?> responseEntity) {
            int status = responseEntity.getStatusCode().value();
            Object body = responseEntity.getBody();
            String bodyJson = body != null ? objectMapper.writeValueAsString(body) : "{}";

            try {
                idempotencyRepository.save(idempotencyKey, operation, requestHash, status, bodyJson);
            } catch (Exception e) {
                Optional<IdempotencyRepository.SavedResponse> retryExisting = idempotencyRepository.findByKey(idempotencyKey);
                if (retryExisting.isPresent()) {
                    var saved = retryExisting.get();
                    Class<?> returnType = signature.getReturnType();
                    Object bodyObj = objectMapper.readValue(saved.body(), returnType);
                    return ResponseEntity.status(saved.status()).body(bodyObj);
                }
                throw e;
            }
        }

        return result;
    }

    private String sha256(String base) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(base.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            return "";
        }
    }
}