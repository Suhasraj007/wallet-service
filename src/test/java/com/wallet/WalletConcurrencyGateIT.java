package com.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * This is Paytm's live probe, reproduced as a test: brand-new users,
 * concurrent first-transfers, concurrent retries of one idempotency key -
 * asserting exact conservation, exactly-once wallet creation, exactly-once
 * application of retried transfers, and zero 5xx.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "auth.jwt-secret=integration-test-secret-0123456789abcdef",
                "wallet.seed-balance-paise=100000",
                "spring.datasource.hikari.maximum-pool-size=30"
        })
@Testcontainers(disabledWithoutDocker = true)
class WalletConcurrencyGateIT {

    private static final long SEED = 100_000L;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcClient jdbc;

    // ---------- helpers ----------

    private String token(String userId) {
        ResponseEntity<Map> response = rest.postForEntity(
                "/auth/token", Map.of("user_id", userId), Map.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return (String) response.getBody().get("token");
    }

    private HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    private ResponseEntity<Map> createAccount(String token) {
        return rest.exchange("/accounts", HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers(token)), Map.class);
    }

    private ResponseEntity<Map> transfer(String token, String toUser, long amountPaise,
                                         String idempotencyKey) {
        Map<String, Object> body = new HashMap<>();
        body.put("to_user", toUser);
        body.put("amount_paise", amountPaise);
        body.put("idempotency_key", idempotencyKey);
        return rest.exchange("/transfers", HttpMethod.POST,
                new HttpEntity<>(body, headers(token)), Map.class);
    }

    private long balance(String token) {
        ResponseEntity<Map> response = rest.exchange("/accounts/me", HttpMethod.GET,
                new HttpEntity<>(headers(token)), Map.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return ((Number) response.getBody().get("balance_paise")).longValue();
    }

    /** Fires all callables as simultaneously as the JVM allows. */
    private <T> List<T> burst(List<Callable<T>> jobs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(jobs.size());
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> job : jobs) {
            futures.add(pool.submit(() -> {
                startGate.await();
                return job.call();
            }));
        }
        startGate.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get());
        }
        pool.shutdown();
        return results;
    }

    private long walletRows(String userId) {
        return jdbc.sql("SELECT count(*) FROM wallets WHERE user_id = :u")
                .param("u", userId).query(Long.class).single();
    }

    private static String freshUser() {
        return "it-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ---------- the gate ----------

    @Test
    void crux_concurrentFirstTransfers_createEachWalletExactlyOnce_andConserveMoney()
            throws Exception {
        String alice = freshUser();
        String bob = freshUser();
        String aliceToken = token(alice);
        // Note: neither wallet exists yet. The transfers themselves must
        // get-or-create both, under a 100-way race.
        List<Callable<ResponseEntity<Map>>> jobs = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String key = "crux-" + i;
            jobs.add(() -> transfer(aliceToken, bob, 500L, key));
        }

        List<ResponseEntity<Map>> results = burst(jobs);

        assertThat(results).allSatisfy(r ->
                assertThat(r.getStatusCode().value()).isEqualTo(201));
        assertThat(walletRows(alice)).isEqualTo(1);
        assertThat(walletRows(bob)).isEqualTo(1);
        assertThat(balance(aliceToken)).isEqualTo(SEED - 100 * 500L);
        assertThat(balance(token(bob))).isEqualTo(SEED + 100 * 500L);
    }

    @Test
    void idempotency_concurrentRetriesOfOneKey_applyOnce_andReturnTheOriginalOutcome()
            throws Exception {
        String alice = freshUser();
        String bob = freshUser();
        String aliceToken = token(alice);
        String key = "the-one-key";
        List<Callable<ResponseEntity<Map>>> jobs = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            jobs.add(() -> transfer(aliceToken, bob, 7_000L, key));
        }

        List<ResponseEntity<Map>> results = burst(jobs);

        Set<Object> transferIds = new HashSet<>();
        Set<Object> newBalances = new HashSet<>();
        for (ResponseEntity<Map> r : results) {
            assertThat(r.getStatusCode().value()).isEqualTo(201);
            transferIds.add(r.getBody().get("transfer_id"));
            newBalances.add(r.getBody().get("new_balance_paise"));
        }
        assertThat(transferIds).hasSize(1);
        assertThat(newBalances).hasSize(1);
        long rows = jdbc.sql("SELECT count(*) FROM transfers "
                        + "WHERE from_user = :u AND idempotency_key = :k")
                .param("u", alice).param("k", key).query(Long.class).single();
        assertThat(rows).isEqualTo(1);
        assertThat(balance(aliceToken)).isEqualTo(SEED - 7_000L);
        assertThat(balance(token(bob))).isEqualTo(SEED + 7_000L);
    }

    @Test
    void idempotency_sameKeyDifferentBody_returns409() {
        String alice = freshUser();
        String aliceToken = token(alice);
        String bob = freshUser();
        assertThat(transfer(aliceToken, bob, 1_000L, "conflict-key")
                .getStatusCode().value()).isEqualTo(201);

        ResponseEntity<Map> conflict = transfer(aliceToken, bob, 2_000L, "conflict-key");

        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(conflict.getBody().get("error")).isEqualTo("idempotency_key_conflict");
    }

    @Test
    void insufficientFunds_underConcurrency_exactCountApplies_neverNegative()
            throws Exception {
        String alice = freshUser();
        String bob = freshUser();
        String aliceToken = token(alice);
        long amount = 1_700L; // floor(100000/1700) = 58 can apply, 42 must reject
        List<Callable<ResponseEntity<Map>>> jobs = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String key = "drain-" + i;
            jobs.add(() -> transfer(aliceToken, bob, amount, key));
        }

        List<ResponseEntity<Map>> results = burst(jobs);

        long applied = results.stream()
                .filter(r -> r.getStatusCode().value() == 201).count();
        long rejected = results.stream()
                .filter(r -> r.getStatusCode().value() == 422).count();
        assertThat(applied).isEqualTo(58);
        assertThat(rejected).isEqualTo(42);
        assertThat(balance(aliceToken)).isEqualTo(SEED - 58 * amount);
        assertThat(balance(token(bob))).isEqualTo(SEED + 58 * amount);
    }

    @Test
    void rejectedTransfer_retriedWithSameKey_replaysTheStoredRejection() {
        String poor = freshUser();
        String rich = freshUser();
        String poorToken = token(poor);
        // Drain almost everything so the next transfer must reject.
        assertThat(transfer(poorToken, rich, SEED - 1, "setup-drain")
                .getStatusCode().value()).isEqualTo(201);

        ResponseEntity<Map> first = transfer(poorToken, rich, 50_000L, "will-reject");
        ResponseEntity<Map> retry = transfer(poorToken, rich, 50_000L, "will-reject");

        assertThat(first.getStatusCode().value()).isEqualTo(422);
        assertThat(retry.getStatusCode().value()).isEqualTo(422);
        assertThat(retry.getBody().get("transfer_id"))
                .isEqualTo(first.getBody().get("transfer_id"));
        assertThat(((Number) retry.getBody().get("balance_paise")).longValue()).isEqualTo(1L);
    }

    @Test
    void accounts_getOrCreate_isIdempotentUnderConcurrency() throws Exception {
        String carol = freshUser();
        String carolToken = token(carol);
        List<Callable<ResponseEntity<Map>>> jobs = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            jobs.add(() -> createAccount(carolToken));
        }

        List<ResponseEntity<Map>> results = burst(jobs);

        assertThat(results).allSatisfy(r ->
                assertThat(r.getStatusCode().value()).isEqualTo(200));
        long createdCount = results.stream()
                .filter(r -> Boolean.TRUE.equals(r.getBody().get("created"))).count();
        assertThat(createdCount).isEqualTo(1);
        assertThat(walletRows(carol)).isEqualTo(1);
    }

    @Test
    void authorization_identityComesFromTheTokenOnly() {
        String alice = freshUser();
        String bob = freshUser();
        String mallory = freshUser();
        String aliceToken = token(alice);
        ResponseEntity<Map> created = transfer(aliceToken, bob, 1_000L, "authz-key");
        String transferId = (String) created.getBody().get("transfer_id");

        // No token at all -> 401. Checked on a bodyless GET as well as a POST,
        // so the assertion never depends on how the client streams a body.
        HttpHeaders bare = new HttpHeaders();
        bare.setContentType(MediaType.APPLICATION_JSON);
        assertThat(rest.exchange("/accounts/me", HttpMethod.GET,
                        new HttpEntity<>(bare), Map.class)
                .getStatusCode().value()).isEqualTo(401);

        Map<String, Object> body = Map.of(
                "to_user", bob, "amount_paise", 100, "idempotency_key", "x");
        assertThat(rest.exchange("/transfers", HttpMethod.POST,
                        new HttpEntity<>(body, bare), Map.class)
                .getStatusCode().value()).isEqualTo(401);

        // Participants can read the transfer
        assertThat(rest.exchange("/transfers/" + transferId, HttpMethod.GET,
                        new HttpEntity<>(headers(token(bob))), Map.class)
                .getStatusCode().value()).isEqualTo(200);
        // A non-participant gets the same 404 as a missing id - no leaks
        assertThat(rest.exchange("/transfers/" + transferId, HttpMethod.GET,
                        new HttpEntity<>(headers(token(mallory))), Map.class)
                .getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void edgeCases_selfTransfer_badAmounts_unknownRoutes() {
        String dave = freshUser();
        String daveToken = token(dave);

        assertThat(transfer(daveToken, dave, 100L, "self-key")
                .getStatusCode().value()).isEqualTo(422);

        Map<String, Object> zero = Map.of(
                "to_user", freshUser(), "amount_paise", 0, "idempotency_key", "zero-key");
        assertThat(rest.exchange("/transfers", HttpMethod.POST,
                        new HttpEntity<>(zero, headers(daveToken)), Map.class)
                .getStatusCode().value()).isEqualTo(400);

        Map<String, Object> negative = Map.of(
                "to_user", freshUser(), "amount_paise", -50, "idempotency_key", "neg-key");
        assertThat(rest.exchange("/transfers", HttpMethod.POST,
                        new HttpEntity<>(negative, headers(daveToken)), Map.class)
                .getStatusCode().value()).isEqualTo(400);

        assertThat(rest.exchange("/transfers/not-a-uuid", HttpMethod.GET,
                        new HttpEntity<>(headers(daveToken)), Map.class)
                .getStatusCode().value()).isEqualTo(400);

        assertThat(rest.exchange("/accounts/me", HttpMethod.GET,
                        new HttpEntity<>(headers(token(freshUser()))), Map.class)
                .getStatusCode().value()).isEqualTo(404);

        // A wrong method on a real route is a client error, not a server fault.
        assertThat(rest.exchange("/transfers/" + UUID.randomUUID(), HttpMethod.DELETE,
                        new HttpEntity<>(headers(daveToken)), Map.class)
                .getStatusCode().value()).isEqualTo(405);
    }

    @Test
    void probes_healthzAndReadyz() {
        assertThat(rest.getForEntity("/healthz", Map.class)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(rest.getForEntity("/readyz", Map.class)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(rest.getForEntity("/metrics", String.class)
                .getStatusCode().value()).isEqualTo(200);
    }
}