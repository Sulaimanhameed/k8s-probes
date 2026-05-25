package com.example.hello.service;

import com.example.hello.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

@Service
public class UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);
    private final DataSource dataSource;

    @Autowired
    public UserService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id           BIGSERIAL PRIMARY KEY,
                    name         VARCHAR(100) NOT NULL,
                    place        VARCHAR(100) NOT NULL,
                    email        VARCHAR(200) NOT NULL UNIQUE,
                    date_of_birth DATE NOT NULL,
                    created_at   TIMESTAMP DEFAULT NOW()
                )
            """);
        } catch (Exception e) {
            logger.warn("Could not create users table: {}", e.getMessage());
        }
    }

    public void saveUser(String name, String place, String email, String dateOfBirth) throws SQLException {
        String sql = "INSERT INTO users (name, place, email, date_of_birth) VALUES (?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, place);
            ps.setString(3, email);
            ps.setDate(4, java.sql.Date.valueOf(dateOfBirth));
            ps.executeUpdate();
        }
    }

    public List<User> getAllUsers() {
        List<User> users = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT id, name, place, email, date_of_birth FROM users ORDER BY id DESC")) {
            while (rs.next()) {
                users.add(new User(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("place"),
                        rs.getString("email"),
                        rs.getDate("date_of_birth").toString()));
            }
        } catch (Exception e) {
            logger.warn("Could not fetch users: {}", e.getMessage());
        }
        return users;
    }
}
