package com.avalanche.high_concurrency_order.api.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import com.avalanche.high_concurrency_order.api.dto.SeckillOrderMessage;
import com.avalanche.high_concurrency_order.api.producer.SeckillOrderProducer;
import com.avalanche.high_concurrency_order.services.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class SeckillOrderConsumer {

    private final OrderService orderService;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public SeckillOrderConsumer(OrderService orderService) {
        this.orderService = orderService;
        log.info("SeckillOrderConsumer has been loaded successfully by Spring");
    }

    @KafkaListener(topics = SeckillOrderProducer.SECKILL_TOPIC, groupId = "seckill-order-group-v2")
    public void consumeSeckillMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.info("Kafka consumer received a seckill order message. Key (productId): {}, partition: {}, offset: {}",
                record.key(), record.partition(), record.offset());

        String orderSn = null;
        try {
            String jsonMessage = record.value();
            SeckillOrderMessage message = OBJECT_MAPPER.readValue(jsonMessage, SeckillOrderMessage.class);

            Long userId = message.getUserId();
            Long productId = message.getProductId();
            Integer quantity = message.getQuantity();
            Long remainStock = message.getRemainStock();
            orderSn = message.getOrderSn();

            log.info("Kafka consumer started processing the MySQL transaction for user: {}, product: {}, quantity: {}",
                    userId, productId, quantity);

            orderService.handleDBPersistence(userId, productId, quantity, remainStock, orderSn);

            log.info("Kafka consumer finished processing for user: {}", message.getUserId());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            log.warn("Duplicate Kafka event detected. Order [{}] already exists in MySQL", orderSn);
            log.warn("MySQL rejected the duplicate order and acknowledged the message");

            ack.acknowledge();
        } catch (Exception e) {
            log.error("Kafka consumer encountered a critical error; the order may be lost. Message: {}", record.value(),
                    e);
        }
    }

}