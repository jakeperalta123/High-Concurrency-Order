package com.avalanche.high_concurrency_order.api.producer;

import java.util.concurrent.CompletableFuture;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import com.avalanche.high_concurrency_order.api.dto.SeckillOrderMessage;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class SeckillOrderProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static final String SECKILL_TOPIC = "seckill-order-topic";

    public SeckillOrderProducer(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void sendSeckillMessage(Long userId, Long productId, Integer quantity, Long remainStock, String orderSn) {
        try {

            SeckillOrderMessage seckillOrderMessage = new SeckillOrderMessage(userId, productId, quantity, remainStock,
                    orderSn);
            String jsonMessage = OBJECT_MAPPER.writeValueAsString(seckillOrderMessage);
            log.info("[kafka Producer] Preparing to send asynchronous order. User:{}, Product:{}", userId, productId);
            CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(SECKILL_TOPIC,
                    String.valueOf(productId), jsonMessage);

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    log.info(" [Kafka producer] successfully send message to Topic! Topic:{}, Partition:{}, Offset:{}",
                            result.getRecordMetadata().topic(),
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                } else {
                    log.error("[Kafka Producer] failed to message to Topic! User: {}, Product:{}, Reason:{}", userId,
                            productId, ex);
                }
            });
        } catch (Exception e) {
            log.error("[Kafka Producer] critical error occured when trying to pack message to Topic", e);
        }
    }

}