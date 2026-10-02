package com.funchole.backend.controlplane.service.workflow;

import com.funchole.backend.controlplane.dto.EnvironmentProfileCreateRequest;
import com.funchole.backend.controlplane.dto.EnvironmentProfileUpdateRequest;
import com.funchole.backend.controlplane.service.DatabaseService;
import com.funchole.backend.controlplane.service.EnvironmentProfileService;
import com.funchole.backend.controlplane.service.FlowConfigurationService;
import com.funchole.backend.controlplane.service.FlowService;
import com.funchole.backend.controlplane.service.ProfileService;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Applies a bounded profile patch or explicit Flow binding patch. */
@Service
public class ConfigureUseCase {
    public record EnvironmentBinding(UUID id, Integer priority) { }
    public record Command(UUID profileId, String key, String name, String description,
                          Map<String, String> env, Map<String, String> secrets, UUID flowId,
                          List<EnvironmentBinding> addEnvironments, List<UUID> removeEnvironments,
                          List<UUID> addDatabases, List<UUID> removeDatabases, boolean allowLiveChanges) { }
    public record Result(UUID resourceId, boolean flow, boolean partialFailure,
                         List<String> envKeys, List<String> secretKeys) { }

    private final ProfileService users;
    private final EnvironmentProfileService profiles;
    private final FlowConfigurationService bindings;
    private final FlowService flows;
    private final DatabaseService databases;

    public ConfigureUseCase(ProfileService users, EnvironmentProfileService profiles,
            FlowConfigurationService bindings, FlowService flows, DatabaseService databases) {
        this.users = users;
        this.profiles = profiles;
        this.bindings = bindings;
        this.flows = flows;
        this.databases = databases;
    }

    public Result execute(UUID userId, Command c) throws Exception {
        require(userId != null && c != null);
        boolean flowMode = c.flowId() != null;
        require(flowMode != (c.profileId() != null || c.key() != null));
        validateConfig(c.env());
        validateConfig(c.secrets());
        require(Collections.disjoint(keys(c.env()), keys(c.secrets())));
        require(c.description() == null || c.description().length() <= 1000);
        List<EnvironmentBinding> add = list(c.addEnvironments());
        List<UUID> remove = list(c.removeEnvironments());
        List<UUID> addDb = list(c.addDatabases());
        List<UUID> removeDb = list(c.removeDatabases());
        if (flowMode) {
            require(c.name() == null && c.description() == null && c.env() == null && c.secrets() == null
                    && c.allowLiveChanges());
            Set<UUID> addIds = new HashSet<>();
            for (EnvironmentBinding binding : add) require(binding != null && binding.id() != null && addIds.add(binding.id()));
            require(unique(remove) && unique(addDb) && unique(removeDb)
                    && Collections.disjoint(addIds, remove) && Collections.disjoint(addDb, removeDb));
            flows.getFlowById(userId, c.flowId());
            for (UUID id : addIds) profiles.getProfileById(userId, id);
            for (UUID id : remove) profiles.getProfileById(userId, id);
            for (UUID id : addDb) databases.getDatabaseById(userId, id);
            for (UUID id : removeDb) databases.getDatabaseById(userId, id);
            try {
                for (EnvironmentBinding binding : add) bindings.attachEnvironment(userId, c.flowId(), binding.id(), binding.priority());
                for (UUID id : remove) bindings.detachEnvironment(userId, c.flowId(), id);
                for (UUID id : addDb) bindings.attachDatabase(userId, c.flowId(), id);
                for (UUID id : removeDb) bindings.detachDatabase(userId, c.flowId(), id);
                return new Result(c.flowId(), true, false, List.of(), List.of());
            } catch (Exception failure) {
                return new Result(c.flowId(), true, true, List.of(), List.of());
            }
        }
        require(add.isEmpty() && remove.isEmpty() && addDb.isEmpty() && removeDb.isEmpty());
        UUID profileId = c.profileId();
        if (profileId != null) {
            require(c.key() == null && c.allowLiveChanges());
            if (c.name() != null) text(c.name(), 255);
            var config = profiles.getConfig(userId, profileId);
            require(config.envVars().stream().noneMatch(entry -> keys(c.secrets()).contains(entry.key())));
            require(config.secrets().stream().noneMatch(entry -> keys(c.env()).contains(entry.key())));
        } else {
            require(c.key() != null && c.key().matches("[A-Za-z0-9_.-]{1,150}"));
            text(c.name(), 255);
        }
        List<String> envKeys = keys(c.env()).stream().sorted().toList();
        List<String> secretKeys = keys(c.secrets()).stream().sorted().toList();
        try {
            if (profileId == null) profileId = profiles.createProfile(users.loadUserById(userId),
                    new EnvironmentProfileCreateRequest(c.key(), c.name(), c.description())).getId();
            else if (c.name() != null || c.description() != null) {
                var current = profiles.getProfileById(userId, profileId);
                profiles.updateProfile(userId, profileId, new EnvironmentProfileUpdateRequest(
                        c.name() == null ? current.getName() : c.name(),
                        c.description() == null ? current.getDescription() : c.description()));
            }
            if (c.env() != null) for (var e : c.env().entrySet()) profiles.upsertEnvVar(userId, profileId, e.getKey(), e.getValue());
            if (c.secrets() != null) for (var e : c.secrets().entrySet()) profiles.upsertSecret(userId, profileId, e.getKey(), e.getValue());
            return new Result(profileId, false, false, envKeys, secretKeys);
        } catch (Exception failure) {
            if (profileId == null) throw failure;
            return new Result(profileId, false, true, envKeys, secretKeys);
        }
    }

    private static <T> List<T> list(List<T> values) { return values == null ? List.of() : values; }
    private static boolean unique(List<UUID> values) { return values.stream().noneMatch(java.util.Objects::isNull) && new HashSet<>(values).size() == values.size(); }
    private static Set<String> keys(Map<String, String> values) { return values == null ? Set.of() : values.keySet(); }
    private static void validateConfig(Map<String, String> values) {
        if (values != null) values.forEach((key, value) -> require(key != null
                && key.matches("[A-Za-z_][A-Za-z0-9_]{0,254}") && value != null));
    }
    private static void text(String value, int max) { require(value != null && !value.isBlank() && value.length() <= max); }
    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException("Invalid configuration request"); }
}
