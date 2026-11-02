package com.example.ledgerApi.adapter.msg;

import com.example.ledgerApi.application.TransactionService;
import com.example.ledgerApi.config.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentSettledConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentSettledConsumer.class);

    private final TransactionService transactionService;

    public PaymentSettledConsumer(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @RabbitListener(queues = RabbitMQConfig.PAYMENT_SETTLED_QUEUE)
    public void consumePaymentSettled(PaymentSettledMessage message) {
        try {
            transactionService.processSettledPayment(message);
        } catch (Exception e) {
            log.error("Rejecting payment-settled message to DLQ: {}", message, e);
            throw new AmqpRejectAndDontRequeueException("Unprocessable payment-settled message", e);
        }
    }
}
