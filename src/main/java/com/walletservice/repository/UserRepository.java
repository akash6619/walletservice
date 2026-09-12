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

@Repository
public class UserRepository {

    private static final String FIND_BY_ID = """
            SELECT user_id, name, active, created_at
            FROM users
            WHERE user_id = :userId
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public UserRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<User> findById(UUID userId) {
        List<User> users = jdbc.query(
                FIND_BY_ID,
                new MapSqlParameterSource("userId", userId),
                UserRepository::mapUser
        );
        return users.stream().findFirst();
    }

    public boolean isActive(UUID userId) {
        return findById(userId).filter(User::active).isPresent();
    }

    private static User mapUser(ResultSet resultSet, int rowNumber) throws SQLException {
        return new User(
                resultSet.getObject("user_id", UUID.class),
                resultSet.getString("name"),
                resultSet.getBoolean("active"),
                resultSet.getTimestamp("created_at").toInstant()
        );
    }
}
