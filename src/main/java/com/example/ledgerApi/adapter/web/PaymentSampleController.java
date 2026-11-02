package com.example.ledgerApi.adapter.web;

import com.example.ledgerApi.adapter.msg.PaymentSettledMessage;
import com.example.ledgerApi.adapter.web.dto.LedgerDtos.PaymentSettledSample;
import com.example.ledgerApi.config.RabbitMQConfig;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/debug")
public class PaymentSampleController {

    private final RabbitTemplate rabbitTemplate;

    public PaymentSampleController(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @PostMapping("/payment-settled")
    ResponseEntity<Map<String, String>> publish(@RequestBody @Valid PaymentSettledSample request) {
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EXCHANGE,
                RabbitMQConfig.PAYMENT_SETTLED_ROUTING_KEY,
                new PaymentSettledMessage(
                        request.eventId(),
                        request.paymentId(),
                        request.merchantAccountId(),
                        request.amount(),
                        request.currency()
                )
        );
        return ResponseEntity.accepted().body(Map.of(
                "status", "published",
                "queue", RabbitMQConfig.PAYMENT_SETTLED_QUEUE
        ));
    }
}
