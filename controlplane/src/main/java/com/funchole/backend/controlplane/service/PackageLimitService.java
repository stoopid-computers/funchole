package com.funchole.backend.controlplane.service;

import com.funchole.backend.controlplane.config.CloudModeProperties;
import com.funchole.backend.controlplane.constant.PackageLimitKey;
import com.funchole.backend.controlplane.entity.UserPackage;
import com.funchole.backend.controlplane.repository.PackageLimitRepository;
import com.funchole.backend.controlplane.repository.UserPackageOverrideRepository;
import com.funchole.backend.controlplane.repository.UserPackageRepository;
import com.funchole.backend.core.base.exception.QuotaExceededException;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Enforces the cloud package/quota system (see V26's migration javadoc for
 * the data model). Called from the service layer, not controllers or MCP
 * tool classes - both already delegate to the same
 * {@code FunctionService}/{@code FlowService}/{@code GatewayService}/
 * {@code DomainService} creation methods, so one call site per resource
 * type covers REST and MCP together.
 *
 * <p>{@link CloudModeProperties#enabled()} is checked first and short-circuits
 * to a no-op when false - the self-hosted default, where this entire
 * system stays completely inert regardless of what's in the
 * packages/package_limits/user_packages/user_package_overrides tables.
 */
@Service
public class PackageLimitService {

    private final CloudModeProperties cloudModeProperties;
    private final UserPackageRepository userPackageRepository;
    private final PackageLimitRepository packageLimitRepository;
    private final UserPackageOverrideRepository userPackageOverrideRepository;

    public PackageLimitService(
            CloudModeProperties cloudModeProperties,
            UserPackageRepository userPackageRepository,
            PackageLimitRepository packageLimitRepository,
            UserPackageOverrideRepository userPackageOverrideRepository
    ) {
        this.cloudModeProperties = cloudModeProperties;
        this.userPackageRepository = userPackageRepository;
        this.packageLimitRepository = packageLimitRepository;
        this.userPackageOverrideRepository = userPackageOverrideRepository;
    }

    /**
     * Throws {@link QuotaExceededException} if {@code currentUsage} has
     * already reached the effective limit for {@code key} - callers pass
     * the count of the resource the user is ABOUT to create one more of
     * (i.e. call this before creating, with the count of what already
     * exists), so "at the limit" (not "over it") is what blocks the next
     * one.
     */
    public void enforce(UUID appUserId, PackageLimitKey key, long currentUsage) {
        if (!cloudModeProperties.enabled()) {
            return;
        }

        Integer limit = resolveEffectiveLimit(appUserId, key);
        if (limit != null && currentUsage >= limit) {
            throw new QuotaExceededException(
                    "Your package allows up to " + limit + " " + describe(key) + "; you already have " + currentUsage + ".");
        }
    }

    /**
     * override -> the user's assigned package's own limit -> unlimited
     * ({@code null}). Package-less users (should not normally happen once
     * self-registration always assigns one, but defensively) are treated
     * as unlimited rather than silently blocked - a missing assignment is
     * a data problem to fix, not a reason to lock a user out entirely.
     *
     * <p>Deliberately checks {@code Optional#isPresent} rather than
     * chaining {@code Optional#map}/{@code orElseGet} through the override
     * lookup: an override row whose own {@code limitValue} is {@code null}
     * (explicitly unlimited for this user) must short-circuit here without
     * falling through to the package limit - {@code Optional#map} would
     * otherwise collapse "found, value null" and "not found" into the same
     * empty Optional, which is a real, different answer.
     */
    /** Whether the package/quota system is active at all (cloud mode). */
    public boolean isEnabled() {
        return cloudModeProperties.enabled();
    }

    /**
     * The limit that would apply to this user for {@code key}: {@code null}
     * means unlimited. Same resolution as {@link #enforce}, but readable
     * without a creation attempt (for the usage endpoint).
     */
    public Integer effectiveLimit(UUID appUserId, PackageLimitKey key) {
        return resolveEffectiveLimit(appUserId, key);
    }

    private Integer resolveEffectiveLimit(UUID appUserId, PackageLimitKey key) {
        var override = userPackageOverrideRepository.findByAppUser_IdAndLimitKey(appUserId, key.name());
        if (override.isPresent()) {
            return override.get().getLimitValue();
        }

        return userPackageRepository.findByAppUser_Id(appUserId)
                .map(UserPackage::getPackageId)
                .flatMap(packageId -> packageLimitRepository.findByPackageIdAndLimitKey(packageId, key.name()))
                .map(limit -> limit.getLimitValue())
                .orElse(null);
    }

    private String describe(PackageLimitKey key) {
        return switch (key) {
            case MAX_GATEWAYS -> "gateway(s)";
            case MAX_FLOWS -> "flow(s)";
            case MAX_FUNCTIONS -> "function(s)";
            case MAX_DOMAINS -> "domain(s)";
            case MAX_DATABASES -> "database(s)";
            case MAX_CUSTOM_DOMAINS -> "custom domain(s)";
        };
    }
}
