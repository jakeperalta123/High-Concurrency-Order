package com.avalanche.high_concurrency_order.core.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import com.avalanche.high_concurrency_order.api.producer.SeckillOrderProducer;

@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic seckillOrderTopic() {
        return TopicBuilder.name(SeckillOrderProducer.SECKILL_TOPIC)
                .partitions(1)
                .replicas(1)
                .build();
    }
}
