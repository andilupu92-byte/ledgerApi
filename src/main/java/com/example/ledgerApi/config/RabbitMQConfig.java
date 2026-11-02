package com.example.ledgerApi.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE = "ledger.exchange";
    public static final String PAYMENT_SETTLED_QUEUE = "payment-settled-queue";
    public static final String PAYMENT_SETTLED_DLQ = "payment-settled-dlq";
    public static final String PAYMENT_SETTLED_ROUTING_KEY = "payment.settled";
    public static final String TX_RECORDED_QUEUE = "transaction-recorded-queue";
    public static final String TX_RECORDED_ROUTING_KEY = "transaction.recorded";

    @Bean
    public DirectExchange ledgerExchange() {
        return new DirectExchange(EXCHANGE);
    }

    @Bean
    public Queue paymentQueue() {
        return QueueBuilder.durable(PAYMENT_SETTLED_QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", PAYMENT_SETTLED_DLQ)
                .build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(PAYMENT_SETTLED_DLQ).build();
    }

    @Bean
    public Queue transactionRecordedQueue() {
        return QueueBuilder.durable(TX_RECORDED_QUEUE).build();
    }

    @Bean
    public Binding paymentBinding() {
        return BindingBuilder.bind(paymentQueue()).to(ledgerExchange()).with(PAYMENT_SETTLED_ROUTING_KEY);
    }

    @Bean
    public Binding transactionRecordedBinding() {
        return BindingBuilder.bind(transactionRecordedQueue()).to(ledgerExchange()).with(TX_RECORDED_ROUTING_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter(JsonMapper jsonMapper) {
        return new JacksonJsonMessageConverter(jsonMapper);
    }
}
