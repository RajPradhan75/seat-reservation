package com.example.seats.service;

import com.example.seats.api.ApiException;
import com.example.seats.api.Models.*;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ReservationService {
    private final JdbcClient db;
    private final JsonMapper json;

    public ReservationService(JdbcClient db, JsonMapper json) {
        this.db = db;
        this.json = json;
    }

    @Transactional
    public Result reserve(UUID showId, String userId, String key, List<String> requestedSeats) {
        List<String> seats = SeatSelection.canonical(requestedSeats);
        // Labels cannot contain commas or colons, so this encoding is unambiguous.
        String canonical = showId + ":" + String.join(",", seats);
        var show = db.sql("SELECT price_paise, per_user_limit FROM shows WHERE id = ?").param(showId)
                .query((rs, row) -> new ShowRules(rs.getLong(1), rs.getInt(2)))
                .optional().orElseThrow(ApiException::notFound);

        // The unique key is the mutex for concurrent retries. No unfinished record is committed.
        int inserted = db.sql("""
                INSERT INTO idempotency_records(user_id, operation, key, canonical_request)
                VALUES (?, 'reserve', ?, ?) ON CONFLICT DO NOTHING
                """).params(userId, key, canonical).update();
        if (inserted == 0) {
            var original = db.sql("""
                    SELECT canonical_request, http_status, response_json FROM idempotency_records
                    WHERE user_id = ? AND operation = 'reserve' AND key = ?
                    """).params(userId, key).query((rs, row) ->
                    new PriorResult(rs.getString(1), rs.getInt(2), rs.getString(3))).single();
            if (!original.canonical().equals(canonical)) {
                outcome(showId, "idempotency_key_reused");
                return new Result(409, new StoredError("IDEMPOTENCY_KEY_REUSED",
                        "This key has already been used for a different request."), false);
            }
            outcome(showId, "idempotent_replay");
            return new Result(original.status() == 201 ? 200 : original.status(),
                    json.readTree(original.body()), true);
        }

        // Global mutation order: idempotency -> user usage -> reservation (cancel only) -> sorted seats.
        int used = lockUsage(showId, userId);
        var locked = lockSeats(showId, seats);
        if (locked.size() != seats.size()) {
            // Invalid requests roll back the key claim so a corrected request may reuse it.
            throw ApiException.badRequest("One or more seat labels do not exist in this show.");
        }
        if (locked.stream().anyMatch(s -> s.reservationId() != null)) {
            return decline(showId, userId, key, "seat_taken", "SEAT_TAKEN",
                    "One or more requested seats are unavailable.");
        }
        if (used + seats.size() > show.limit()) {
            return decline(showId, userId, key, "per_user_limit", "PER_USER_LIMIT",
                    "This request would exceed the per-user seat limit.");
        }

        UUID reservationId = UUID.randomUUID();
        long amount = Math.multiplyExact(show.price(), seats.size());
        db.sql("""
                INSERT INTO reservations(id, show_id, user_id, amount_paise, status)
                VALUES (?, ?, ?, ?, 'confirmed')
                """).params(reservationId, showId, userId, amount).update();
        int allocated = db.sql("""
                UPDATE show_seats SET reservation_id = :reservation
                WHERE show_id = :show AND seat_label IN (:seats) AND reservation_id IS NULL
                """).param("reservation", reservationId).param("show", showId).param("seats", seats).update();
        if (allocated != seats.size()) throw new IllegalStateException("Seat allocation invariant violated");
        db.sql("""
                INSERT INTO reservation_seats(show_id, reservation_id, seat_label)
                SELECT show_id, reservation_id, seat_label FROM show_seats WHERE reservation_id = ?
                """).param(reservationId).update();
        db.sql("""
                UPDATE user_show_usage SET active_seat_count = active_seat_count + ?
                WHERE show_id = ? AND user_id = ?
                """).params(seats.size(), showId, userId).update();

        var response = new ReservationView(reservationId, showId, userId, seats, amount, "confirmed");
        outcome(showId, "confirmed");
        return finish(userId, key, 201, response);
    }

    @Transactional
    public ReservationView cancel(UUID id, String userId) {
        var initial = find(id, userId, false);
        lockUsage(initial.showId(), userId);
        var reservation = find(id, userId, true);
        List<String> seats = historicalSeats(id);
        if (reservation.status().equals("cancelled")) return view(reservation, seats);
        lockSeats(reservation.showId(), seats);

        // A stale cancellation can clear only its own allocation, never a later owner's.
        int released = db.sql("""
                UPDATE show_seats SET reservation_id = NULL
                WHERE show_id = ? AND reservation_id = ?
                """).params(reservation.showId(), id).update();
        if (released != seats.size()) throw new IllegalStateException("Seat release invariant violated");
        db.sql("""
                UPDATE user_show_usage SET active_seat_count = active_seat_count - ?
                WHERE show_id = ? AND user_id = ?
                """).params(released, reservation.showId(), userId).update();
        db.sql("UPDATE reservations SET status = 'cancelled', cancelled_at = now() WHERE id = ?")
                .param(id).update();
        outcome(reservation.showId(), "cancelled");
        return new ReservationView(id, reservation.showId(), userId, seats, reservation.amount(), "cancelled");
    }

    public ReservationView get(UUID id, String userId) {
        // Seat history is immutable, so it can be read separately from the current reservation state.
        return view(find(id, userId, false), historicalSeats(id));
    }

    private int lockUsage(UUID show, String user) {
        db.sql("INSERT INTO user_show_usage(show_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING")
                .params(show, user).update();
        return db.sql("SELECT active_seat_count FROM user_show_usage WHERE show_id = ? AND user_id = ? FOR UPDATE")
                .params(show, user).query(Integer.class).single();
    }

    private List<LockedSeat> lockSeats(UUID show, List<String> seats) {
        return db.sql("""
                SELECT seat_label, reservation_id FROM show_seats
                WHERE show_id = :show AND seat_label IN (:seats)
                ORDER BY seat_label FOR UPDATE
                """).param("show", show).param("seats", seats)
                .query((rs, row) -> new LockedSeat(rs.getString(1), rs.getObject(2, UUID.class))).list();
    }

    private Reservation find(UUID id, String user, boolean lock) {
        return db.sql("SELECT id, show_id, user_id, amount_paise, status FROM reservations WHERE id = ? AND user_id = ?"
                        + (lock ? " FOR UPDATE" : ""))
                .params(id, user).query((rs, row) -> new Reservation(rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class), rs.getString(3), rs.getLong(4), rs.getString(5)))
                .optional().orElseThrow(ApiException::notFound);
    }

    private List<String> historicalSeats(UUID id) {
        return db.sql("SELECT seat_label FROM reservation_seats WHERE reservation_id = ? ORDER BY seat_label")
                .param(id).query(String.class).list();
    }

    private ReservationView view(Reservation r, List<String> seats) {
        return new ReservationView(r.id(), r.showId(), r.user(), seats, r.amount(), r.status());
    }

    private Result decline(UUID show, String user, String key, String reason, String code, String message) {
        outcome(show, reason);
        return finish(user, key, 409, new StoredError(code, message));
    }

    private Result finish(String user, String key, int status, Object body) {
        db.sql("""
                UPDATE idempotency_records SET http_status = ?, response_json = ?
                WHERE user_id = ? AND operation = 'reserve' AND key = ?
                """).params(status, json.writeValueAsString(body), user, key).update();
        return new Result(status, body, false);
    }

    private void outcome(UUID show, String outcome) {
        db.sql("INSERT INTO request_outcomes(show_id, outcome) VALUES (?, ?)").params(show, outcome).update();
    }

    private record ShowRules(long price, int limit) {}
    private record LockedSeat(String label, UUID reservationId) {}
    private record PriorResult(String canonical, int status, String body) {}
    private record Reservation(UUID id, UUID showId, String user, long amount, String status) {}
}
