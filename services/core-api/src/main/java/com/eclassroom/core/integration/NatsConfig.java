package com.eclassroom.core.integration;

import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class NatsConfig {
    @Bean(destroyMethod = "close") Connection natsConnection(@Value("${app.nats.url}") String url) throws Exception {
        Options options = new Options.Builder().server(url).connectionTimeout(Duration.ofSeconds(3)).maxReconnects(-1).build();
        return Nats.connect(options);
    }
}
