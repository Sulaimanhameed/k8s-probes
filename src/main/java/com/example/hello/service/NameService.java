package com.example.hello.service;

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
public class NameService {

    private static final Logger logger = LoggerFactory.getLogger(NameService.class);
    private final DataSource dataSource;

    @Autowired
    public NameService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS names (
                    id   BIGSERIAL PRIMARY KEY,
                    name VARCHAR(200) NOT NULL
                )
            """);
        } catch (Exception e) {
            logger.warn("Could not create names table: {}", e.getMessage());
        }
    }

    public void save(String name) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO names (name) VALUES (?)")) {
            ps.setString(1, name);
            ps.executeUpdate();
        } catch (Exception e) {
            logger.warn("Could not save name: {}", e.getMessage());
        }
    }

    public List<String> getAll() {
        List<String> names = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM names ORDER BY id DESC")) {
            while (rs.next()) names.add(rs.getString("name"));
        } catch (Exception e) {
            logger.warn("Could not fetch names: {}", e.getMessage());
        }
        return names;
    }
}
