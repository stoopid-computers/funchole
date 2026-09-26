package com.funchole.backend.controlplane.service;

import com.funchole.backend.controlplane.config.DiscordNotificationProperties;
import com.funchole.backend.controlplane.event.UserSignedUpEvent;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class DiscordSignupNotificationListener {

    private static final Logger logger = LoggerFactory.getLogger(DiscordSignupNotificationListener.class);

    private final DiscordNotificationProperties properties;
    private final RestClient restClient;

    public DiscordSignupNotificationListener(
            DiscordNotificationProperties properties,
            RestClient.Builder restClientBuilder
    ) {
        this.properties = properties;
        this.restClient = restClientBuilder.build();
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void notifyDiscord(UserSignedUpEvent event) {
        if (!properties.hasSignupWebhook()) {
            return;
        }

        String message = event.identity() + ", joined FuncHole";
        try {
            restClient.post()
                    .uri(properties.signupWebhookUrl())
                    .body(Map.of("content", message))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            logger.warn("Failed to send Discord signup notification for '{}'.", event.identity(), exception);
        }
    }
}
