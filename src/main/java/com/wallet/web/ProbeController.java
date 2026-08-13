package com.wallet.web;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness answers "is the process up" and never touches the database.
 * Readiness proves the datastore is reachable with a real round-trip; the
 * bounded pool/socket timeouts keep it from hanging when Postgres is down.
 */
@RestController
public class ProbeController {

    private final JdbcClient jdbc;

    public ProbeController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/healthz")
    public Map<String, String> healthz() {
        return Map.of("status", "ok");
    }

    @GetMapping("/readyz")
    public ResponseEntity<Map<String, String>> readyz() {
        try {
            jdbc.sql("SELECT 1").query(Integer.class).single();
            return ResponseEntity.ok(Map.of("status", "ready"));
        } catch (Exception e) {
            return ResponseEntity.status(503)
                    .body(Map.of("status", "unready", "reason", "database_unreachable"));
        }
    }
}
