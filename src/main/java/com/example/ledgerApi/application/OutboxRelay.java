package com.example.ledgerApi.application;

import com.example.ledgerApi.adapter.db.OutboxRepository;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Component
public class OutboxRelay {

    private final OutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;

    public OutboxRelay(OutboxRepository outboxRepository, RabbitTemplate rabbitTemplate) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Scheduled(fixedDelay = 2000) // Run every 2 seconds
    @Transactional
    public void publishPendingEvents() {
        List<OutboxRepository.OutboxEvent> pending = outboxRepository.findTop50Unpublished();

        for (OutboxRepository.OutboxEvent event : pending) {
            try {
                rabbitTemplate.convertAndSend("ledger.exchange", "transaction.recorded", event.payload());
                outboxRepository.markAsPublished(event.id());
            } catch (Exception e) {
                break;
            }
        }
    }
}