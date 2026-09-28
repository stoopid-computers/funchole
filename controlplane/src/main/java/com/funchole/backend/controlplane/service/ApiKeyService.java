package com.funchole.backend.controlplane.service;

import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.entity.AppUserApiKey;
import com.funchole.backend.controlplane.repository.AppUserApiKeyRepository;
import com.funchole.backend.core.base.exception.ResourceNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Long-lived credentials for machine clients (currently the MCP server) that
 * can't hold the short-lived browser JWT. The raw key is generated here;
 * its SHA-256 hash is persisted for fast auth-time lookups, and it is also
 * kept AES-encrypted (via {@link EncryptionService}) so it can be decrypted
 * and shown again later through {@link #revealApiKey}, not just once at
 * creation.
 */
@Service
public class ApiKeyService {

    private static final String KEY_PREFIX = "fh_mcp_";
    private static final int SECRET_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AppUserApiKeyRepository apiKeyRepository;
    private final EncryptionService encryptionService;

    public ApiKeyService(AppUserApiKeyRepository apiKeyRepository, EncryptionService encryptionService) {
        this.apiKeyRepository = apiKeyRepository;
        this.encryptionService = encryptionService;
    }

    public record GeneratedApiKey(AppUserApiKey entity, String rawKey) {
    }

    @Transactional
    public GeneratedApiKey createApiKey(AppUser appUser, String name) {
        byte[] secretBytes = new byte[SECRET_BYTES];
        SECURE_RANDOM.nextBytes(secretBytes);
        String rawKey = KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);

        AppUserApiKey apiKey = AppUserApiKey.create(
                appUser, name, displayPrefix(rawKey), hash(rawKey), encryptionService.encrypt(rawKey));
        apiKeyRepository.save(apiKey);
        return new GeneratedApiKey(apiKey, rawKey);
    }

    /**
     * Decrypts and returns the full raw key so it can be shown again after
     * creation. Keys created before {@code encryptedKey} existed have none
     * on record and can't be recovered - the caller gets a clear error
     * rather than a null/garbled value.
     */
    @Transactional(readOnly = true)
    public String revealApiKey(UUID appUserId, UUID apiKeyId) {
        AppUserApiKey apiKey = apiKeyRepository.findByIdAndAppUser_Id(apiKeyId, appUserId)
                .orElseThrow(() -> new ResourceNotFoundException("API key not found: " + apiKeyId));
        if (apiKey.getEncryptedKey() == null) {
            throw new ResourceNotFoundException("This key was created before viewing was supported and can't be shown again");
        }
        return encryptionService.decrypt(apiKey.getEncryptedKey());
    }

    @Transactional(readOnly = true)
    public List<AppUserApiKey> listApiKeys(UUID appUserId) {
        return apiKeyRepository.findAllByAppUser_IdOrderByCreatedAtDesc(appUserId);
    }

    @Transactional
    public void revokeApiKey(UUID appUserId, UUID apiKeyId) {
        AppUserApiKey apiKey = apiKeyRepository.findByIdAndAppUser_Id(apiKeyId, appUserId)
                .orElseThrow(() -> new ResourceNotFoundException("API key not found: " + apiKeyId));
        apiKey.revoke();
        apiKeyRepository.save(apiKey);
    }

    /**
     * Resolves a raw bearer token to its owning user, iff it looks like an
     * API key at all - callers (the auth filter) fall back to JWT handling
     * otherwise. Touches {@code lastUsedAt} so users can see which keys are
     * actually still in use before revoking one.
     */
    @Transactional
    public Optional<AppUser> resolve(String rawKey) {
        if (rawKey == null || !rawKey.startsWith(KEY_PREFIX)) {
            return Optional.empty();
        }
        return apiKeyRepository.findByHashedKeyAndRevokedAtIsNull(hash(rawKey))
                .map(apiKey -> {
                    apiKey.markUsed();
                    apiKeyRepository.save(apiKey);
                    return apiKey.getAppUser();
                });
    }

    public static boolean looksLikeApiKey(String rawKey) {
        return rawKey != null && rawKey.startsWith(KEY_PREFIX);
    }

    private static String displayPrefix(String rawKey) {
        return rawKey.substring(0, Math.min(rawKey.length(), KEY_PREFIX.length() + 6));
    }

    private static String hash(String rawKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }
}
