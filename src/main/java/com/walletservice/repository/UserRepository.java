package com.walletservice.repository;

import com.walletservice.model.User;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Provides JDBC-backed lookups for registered user identities and activation state. */
@Repository
public class UserRepository {

    private static final String FIND_BY_ID = """
            SELECT user_id, name, active, created_at
            FROM users
            WHERE user_id = :userId
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /** Creates a user repository backed by Spring's named-parameter JDBC template. */
    public UserRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Finds a user by its externally meaningful UUID. */
    public Optional<User> findById(UUID userId) {
        List<User> users = jdbc.query(
                FIND_BY_ID,
                new MapSqlParameterSource("userId", userId),
                UserRepository::mapUser
        );
        return users.stream().findFirst();
    }

    /** Returns whether the identifier belongs to a present and active user. */
    public boolean isActive(UUID userId) {
        return findById(userId).filter(User::active).isPresent();
    }

    /** Maps the current JDBC row to the immutable user domain model. */
    private static User mapUser(ResultSet resultSet, int rowNumber) throws SQLException {
        return new User(
                resultSet.getObject("user_id", UUID.class),
                resultSet.getString("name"),
                resultSet.getBoolean("active"),
                resultSet.getTimestamp("created_at").toInstant()
        );
    }
}
