package com.example.ledgerApi.application;

import com.example.ledgerApi.adapter.db.OutboxRepository;
import com.example.ledgerApi.config.RabbitMQConfig;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public OutboxRelay(OutboxRepository outboxRepository, RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 500)
    @Transactional
    public void publishPendingEvents() {
        var pending = outboxRepository.findUnpublishedForUpdate();
        for (OutboxRepository.OutboxEvent event : pending) {
            Map<?, ?> payload = objectMapper.readValue(event.payload(), Map.class);
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.EXCHANGE,
                    RabbitMQConfig.TX_RECORDED_ROUTING_KEY,
                    payload
            );
            outboxRepository.markAsPublished(event.id());
            log.info("Announced transaction {} (outbox {})", event.transactionId(), event.id());
        }
    }
}
