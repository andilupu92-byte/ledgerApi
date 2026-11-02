package com.example.ledgerApi.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE = "ledger.exchange";
    public static final String QUEUE = "payment-settled-queue";
    public static final String DLQ = "payment-settled-dlq";

    @Bean
    public DirectExchange ledgerExchange() {
        return new DirectExchange(EXCHANGE);
    }

    @Bean
    public Queue paymentQueue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", DLQ)
                .build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    public Binding binding() {
        return BindingBuilder.bind(paymentQueue()).to(ledgerExchange()).with("payment.settled");
    }
}