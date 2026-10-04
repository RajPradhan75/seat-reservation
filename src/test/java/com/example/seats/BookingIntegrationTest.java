package com.example.seats;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class BookingIntegrationTest {
    private static final String SECRET = "integration-test-only-secret-with-more-than-32-bytes";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static EmbeddedPostgres postgres;
    private static final List<ConfigurableApplicationContext> apps = new ArrayList<>();
    private static final List<String> bases = new ArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        postgres = EmbeddedPostgres.builder().setPort(0).start();
        // Two independent application instances sharing the same real PostgreSQL database.
        for (int i = 0; i < 2; i++) {
            var app = new SpringApplicationBuilder(SeatReservationApplication.class).run(
                    "--server.port=0", "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
                    "--spring.datasource.username=postgres", "--spring.datasource.password=postgres",
                    "--app.jwt.secret=" + SECRET, "--spring.datasource.hikari.maximum-pool-size=12");
            apps.add(app);
            bases.add("http://localhost:" + ((WebServerApplicationContext) app).getWebServer().getPort());
        }
    }

    @AfterAll
    static void stop() throws Exception {
        for (var app : apps) app.close();
        if (postgres != null) postgres.close();
        HTTP.close();
    }

    @Test
    void hotSeatHasExactlyOneWinnerAcrossTwoInstances() throws Exception {
        String show = createShow(2, 4);
        var responses = burst(500, i -> reserve(i % 2, show, "buyer-" + i, "hot-" + i, "A1"));
        assertThat(responses.stream().filter(r -> r.statusCode() == 201)).hasSize(1);
        assertThat(responses.stream().filter(r -> r.statusCode() == 409)).hasSize(499);
        for (var r : responses) if (r.statusCode() == 409) assertThat(body(r).get("code").asText()).isEqualTo("SEAT_TAKEN");
        assertInventory(show, 1, 1);
        String metrics = call(0, "GET", "/metrics", null, null, null).body();
        assertThat(metrics).contains("seats{show_id=\"" + show + "\",state=\"confirmed\"} 1");
    }

    @Test
    void parallelRequestsCannotExceedUserLimit() throws Exception {
        String show = createShow(10, 4);
        String user = UUID.randomUUID().toString();
        var responses = burst(10, i -> reserve(i % 2, show, user, "limit-" + i, "A" + (i + 1)));
        assertThat(responses.stream().filter(r -> r.statusCode() == 201)).hasSize(4);
        assertThat(responses.stream().filter(r -> r.statusCode() == 409)).hasSize(6);
        for (var r : responses) if (r.statusCode() == 409) assertThat(body(r).get("code").asText()).isEqualTo("PER_USER_LIMIT");
        assertInventory(show, 6, 4);
    }

    @Test
    void concurrentRetriesCreateExactlyOneReservation() throws Exception {
        String show = createShow(3, 4);
        String user = UUID.randomUUID().toString();
        var responses = burst(50, i -> reserve(i % 2, show, user, "same-key", "A1", "A2"));
        assertThat(responses.stream().filter(r -> r.statusCode() == 201)).hasSize(1);
        assertThat(responses.stream().filter(r -> r.statusCode() == 200)).hasSize(49);
        assertThat(responses.stream().map(r -> body(r).get("reservation_id").asText()).distinct()).hasSize(1);
        assertThat(body(responses.getFirst()).get("amount_paise").asLong()).isEqualTo(50000);
        assertThat(reserve(0, show, user, "same-key", "A2", "A1").statusCode()).isEqualTo(200);
        var mismatch = reserve(0, show, user, "same-key", "A3");
        assertThat(mismatch.statusCode()).isEqualTo(409);
        assertThat(body(mismatch).get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        String otherShow = createShow(1, 4);
        assertThat(reserve(0, otherShow, user, "same-key", "A1").statusCode()).isEqualTo(409);
        assertInventory(show, 1, 2);
    }

    @Test
    void multiSeatRequestsAreAtomicAndOverlappingOrdersDoNotDeadlock() throws Exception {
        String show = createShow(3, 4);
        assertThat(reserve(0, show, "owner", "owner-" + show, "A1").statusCode()).isEqualTo(201);
        String user = UUID.randomUUID().toString();
        var failed = reserve(0, show, user, "partial", "A1", "A2");
        assertThat(failed.statusCode()).isEqualTo(409);
        assertThat(reserve(1, show, "other", "free-" + show, "A2").statusCode()).isEqualTo(201);
        assertInventory(show, 1, 2);

        String overlap = createShow(2, 4);
        var responses = burst(40, i -> reserve(i % 2, overlap, "overlap-" + i, "overlap-" + overlap + i,
                i % 2 == 0 ? new String[]{"A1", "A2"} : new String[]{"A2", "A1"}));
        assertThat(responses.stream().filter(r -> r.statusCode() == 201)).hasSize(1);
        assertThat(responses.stream().filter(r -> r.statusCode() == 409)).hasSize(39);
        assertInventory(overlap, 0, 2);
    }

    @Test
    void cancellationRestoresCapacityAndStaleCancellationCannotReleaseNewOwner() throws Exception {
        String show = createShow(2, 1);
        String user = UUID.randomUUID().toString();
        var original = reserve(0, show, user, "original", "A1");
        String id = body(original).get("reservation_id").asText();
        assertThat(call(0, "POST", "/reservations/" + id + "/cancel", token("intruder", false), null, null).statusCode()).isEqualTo(404);
        assertThat(cancel(id, user).statusCode()).isEqualTo(200);
        assertThat(reserve(1, show, "new-owner", "rebook-" + show, "A1").statusCode()).isEqualTo(201);
        assertThat(cancel(id, user).statusCode()).isEqualTo(200);
        assertThat(reserve(0, show, user, "restored-limit", "A2").statusCode()).isEqualTo(201);
        assertThat(body(reserve(0, show, user, "original", "A1")).get("reservation_id").asText()).isEqualTo(id);
        var current = call(0, "GET", "/reservations/" + id, token(user, false), null, null);
        assertThat(body(current).get("status").asText()).isEqualTo("cancelled");
        assertInventory(show, 0, 2);
    }

    @Test
    void declineIsReplayedAfterSeatBecomesAvailable() throws Exception {
        String show = createShow(1, 4);
        String owner = UUID.randomUUID().toString(), buyer = UUID.randomUUID().toString();
        String id = body(reserve(0, show, owner, "owner", "A1")).get("reservation_id").asText();
        assertThat(reserve(0, show, buyer, "declined", "A1").statusCode()).isEqualTo(409);
        cancel(id, owner);
        var replay = reserve(1, show, buyer, "declined", "A1");
        assertThat(replay.statusCode()).isEqualTo(409);
        assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertInventory(show, 1, 0);
        assertThat(reserve(0, show, buyer, "fresh-attempt", "A1").statusCode()).isEqualTo(201);
    }

    @Test
    void validatesIdentityInputAndAdminAccess() throws Exception {
        String show = createShow(2, 4);
        String user = UUID.randomUUID().toString();
        String path = "/shows/" + show + "/reserve";
        assertThat(call(0, "POST", path, null, "k", "{\"seats\":[\"A1\"]}").statusCode()).isEqualTo(401);
        assertThat(call(0, "POST", path, token(user, false), "k", "{\"seats\":[\"A1\"],\"user_id\":\"victim\"}").statusCode()).isEqualTo(400);
        assertThat(reserve(0, show, user, "k", "A1", "A1").statusCode()).isEqualTo(400);
        assertThat(reserve(0, show, user, "k", "DOES_NOT_EXIST").statusCode()).isEqualTo(400);
        assertThat(call(0, "POST", path, token(user, false), "one", "{\"seats\":[\"A1\"],\"idempotency_key\":\"two\"}").statusCode()).isEqualTo(400);
        var valid = reserve(0, show, user, "k", "A1");
        assertThat(valid.statusCode()).isEqualTo(201);
        assertThat(body(valid).get("user_id").asText()).isEqualTo(user);
        assertThat(call(0, "POST", "/shows", token(user, false), null,
                "{\"name\":\"denied\",\"seats\":[\"A1\"],\"price_paise\":10}").statusCode()).isEqualTo(403);
        assertThat(call(0, "POST", "/shows", token("admin", true), null,
                "{\"name\":\"invalid-money\",\"seats\":[\"A1\"],\"price_paise\":1.5}").statusCode()).isEqualTo(400);
        assertThat(call(0, "GET", "/actuator/health/readiness", null, null, null).statusCode()).isEqualTo(200);
    }

    private static String createShow(int count, int limit) throws Exception {
        String payload = JSON.writeValueAsString(Map.of("name", "test-" + UUID.randomUUID(),
                "seats", IntStream.rangeClosed(1, count).mapToObj(i -> "A" + i).toList(),
                "price_paise", 25000, "per_user_limit", limit));
        var r = call(0, "POST", "/shows", token("admin", true), null, payload);
        assertThat(r.statusCode()).withFailMessage(r.body()).isEqualTo(201);
        return body(r).get("id").asText();
    }

    private static void assertInventory(String show, int available, int confirmed) throws Exception {
        var state = body(call(0, "GET", "/shows/" + show, null, null, null));
        var counts = state.get("counts");
        assertThat(counts.get("available").asInt()).isEqualTo(available);
        assertThat(counts.get("confirmed").asInt()).isEqualTo(confirmed);
        assertThat(counts.get("held").asInt()).isZero();
        assertThat(counts.get("total_seats").asInt()).isEqualTo(available + confirmed);
        assertThat(state.get("seats").size()).isEqualTo(available + confirmed);
    }

    private static HttpResponse<String> reserve(int instance, String show, String user, String key, String... seats) {
        try {
            return call(instance, "POST", "/shows/" + show + "/reserve", token(user, false), key,
                    JSON.writeValueAsString(Map.of("seats", List.of(seats))));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static HttpResponse<String> cancel(String id, String user) throws Exception {
        return call(0, "POST", "/reservations/" + id + "/cancel", token(user, false), null, null);
    }

    private static List<HttpResponse<String>> burst(int count, IntFunction<HttpResponse<String>> operation) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = new CountDownLatch(count);
            var start = new CountDownLatch(1);
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(executor.submit(() -> { ready.countDown(); start.await(); return operation.apply(index); }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<HttpResponse<String>> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(90, TimeUnit.SECONDS));
            return results;
        }
    }

    private static HttpResponse<String> call(int instance, String method, String path, String token, String key, String payload) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(bases.get(instance) + path)).timeout(Duration.ofSeconds(90));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        if (payload != null) request.header("Content-Type", "application/json");
        request.method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode body(HttpResponse<String> response) { return JSON.readTree(response.body()); }

    private static String token(String subject, boolean admin) throws Exception {
        var claims = new JWTClaimsSet.Builder().subject(subject).issuer("seat-reservation").audience("seat-api")
                .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                .claim("scope", admin ? "admin" : "book").build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET));
        return jwt.serialize();
    }
}
