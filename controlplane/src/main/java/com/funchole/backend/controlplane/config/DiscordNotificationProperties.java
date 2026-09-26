package com.funchole.backend.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.notifications.discord")
public record DiscordNotificationProperties(String signupWebhookUrl) {

    public boolean hasSignupWebhook() {
        return signupWebhookUrl != null && !signupWebhookUrl.isBlank();
    }
}
