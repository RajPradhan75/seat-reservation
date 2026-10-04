package com.example.seats.service;

import com.example.seats.api.ApiException;
import com.example.seats.api.Models.*;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShowService {
    private final JdbcClient db;
    private final JdbcTemplate batch;

    public ShowService(JdbcClient db, JdbcTemplate batch) {
        this.db = db;
        this.batch = batch;
    }

    @Transactional
    public ShowView create(CreateShowRequest request) {
        List<String> seats = SeatSelection.canonical(request.seats());
        UUID id = UUID.randomUUID();
        int limit = request.perUserLimit() == null ? 4 : request.perUserLimit();
        db.sql("INSERT INTO shows(id, name, price_paise, per_user_limit) VALUES (?, ?, ?, ?)")
                .params(id, request.name(), request.pricePaise(), limit).update();
        batch.batchUpdate("INSERT INTO show_seats(show_id, seat_label) VALUES (?, ?)", seats, 1000,
                (statement, seat) -> { statement.setObject(1, id); statement.setString(2, seat); });
        return get(id);
    }

    public ShowView get(UUID id) {
        // One SQL statement = one MVCC snapshot, even while reservations commit.
        var rows = db.sql("""
                SELECT s.name, s.price_paise, s.per_user_limit, t.seat_label,
                       (t.reservation_id IS NOT NULL) AS occupied
                FROM shows s JOIN show_seats t ON t.show_id = s.id
                WHERE s.id = ? ORDER BY t.seat_label
                """).param(id).query((rs, row) -> new SnapshotRow(rs.getString("name"),
                rs.getLong("price_paise"), rs.getInt("per_user_limit"), rs.getString("seat_label"),
                rs.getBoolean("occupied"))).list();
        if (rows.isEmpty()) throw ApiException.notFound();
        var first = rows.getFirst();
        var seats = rows.stream().map(r -> new SeatView(r.seat(), r.occupied() ? "confirmed" : "available")).toList();
        long confirmed = rows.stream().filter(SnapshotRow::occupied).count();
        return new ShowView(id, first.name(), first.price(), first.limit(), seats,
                new Counts(seats.size() - confirmed, 0, confirmed, seats.size()));
    }

    private record SnapshotRow(String name, long price, int limit, String seat, boolean occupied) {}
}
