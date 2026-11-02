package com.example.ledgerApi.adapter.messaging;

import com.example.ledgerApi.adapter.msg.PaymentSettledMessage;
import com.example.ledgerApi.application.TransactionService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentSettledConsumer {

    private final TransactionService transactionService;

    public PaymentSettledConsumer(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @RabbitListener(queues = "payment-settled-queue")
    public void consumePaymentSettled(PaymentSettledMessage message) {
        transactionService.processSettledPayment(message);
    }
}