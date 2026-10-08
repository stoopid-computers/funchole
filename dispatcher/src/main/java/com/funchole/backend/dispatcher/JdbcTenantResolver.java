package com.funchole.backend.dispatcher;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;

/** Reads {@code invocations.app_user_id} (filled by a trigger since migration V31). */
public final class JdbcTenantResolver implements TenantResolver {

    private final DataSource dataSource;

    public JdbcTenantResolver(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public UUID resolve(InvocationStepExecution stepExecution) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT app_user_id FROM invocations WHERE id = ?")) {
            statement.setObject(1, stepExecution.invocationId());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getObject(1, UUID.class) : null;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load invocation owner: " + stepExecution.invocationId(), exception);
        }
    }
}
