-- Read-only checks for the MOST RECENT show named interview-demo.
-- The first result's ID must match show_id in the API client.
-- These queries work in psql and DBeaver; no extensions are required.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;

SELECT id, name, price_paise, per_user_limit, created_at
FROM shows WHERE name = 'interview-demo' ORDER BY created_at DESC LIMIT 1;

-- Current ownership (not historical bookings): after the full demo, only A1 belongs to Bob.
WITH demo AS (SELECT id FROM shows WHERE name = 'interview-demo' ORDER BY created_at DESC LIMIT 1)
SELECT s.seat_label, CASE WHEN s.reservation_id IS NULL THEN 'available' ELSE 'confirmed' END AS seat_status,
       s.reservation_id, r.user_id
FROM show_seats s JOIN demo d ON d.id = s.show_id
LEFT JOIN reservations r ON r.id = s.reservation_id ORDER BY s.seat_label;

-- Reservation history: Alice's two-seat cancelled booking and Bob's confirmed one-seat booking.
WITH demo AS (SELECT id FROM shows WHERE name = 'interview-demo' ORDER BY created_at DESC LIMIT 1)
SELECT r.id, r.user_id, r.status, r.amount_paise, array_agg(h.seat_label ORDER BY h.seat_label) AS seats
FROM reservations r JOIN demo d ON d.id = r.show_id
JOIN reservation_seats h ON h.reservation_id = r.id
GROUP BY r.id ORDER BY r.created_at;

-- Current usage should agree with actual seat ownership and remain within the limit.
WITH demo AS (SELECT id FROM shows WHERE name = 'interview-demo' ORDER BY created_at DESC LIMIT 1)
SELECT u.user_id, u.active_seat_count, s.per_user_limit,
       (SELECT count(*) FROM show_seats t JOIN reservations r ON r.id = t.reservation_id
        WHERE t.show_id = u.show_id AND r.user_id = u.user_id) AS actual_owned_seats,
       u.active_seat_count <= s.per_user_limit AS within_limit
FROM user_show_usage u JOIN demo d ON d.id = u.show_id JOIN shows s ON s.id = u.show_id
ORDER BY u.user_id;

-- Final expected values: available=4, held=0, confirmed=1, total_seats=5.
WITH demo AS (SELECT id FROM shows WHERE name = 'interview-demo' ORDER BY created_at DESC LIMIT 1)
SELECT count(*) FILTER (WHERE reservation_id IS NULL) AS available, 0 AS held,
       count(*) FILTER (WHERE reservation_id IS NOT NULL) AS confirmed, count(*) AS total_seats
FROM show_seats s JOIN demo d ON d.id = s.show_id;

-- Only one stored result for Alice's original key; replay does not create another reservation.
-- Original HTTP status remains 201 in storage even though a replay's HTTP response is 200.
WITH demo AS (SELECT id FROM shows WHERE name = 'interview-demo' ORDER BY created_at DESC LIMIT 1)
SELECT i.user_id, i.key, i.http_status, i.response_json::jsonb ->> 'reservation_id' AS reservation_id
FROM idempotency_records i JOIN demo d ON i.canonical_request LIKE d.id::text || ':%'
ORDER BY i.created_at;

-- Historical outcomes are cumulative, unlike current inventory.
WITH demo AS (SELECT id FROM shows WHERE name = 'interview-demo' ORDER BY created_at DESC LIMIT 1)
SELECT outcome, count(*) FROM request_outcomes o JOIN demo d ON d.id = o.show_id
GROUP BY outcome ORDER BY outcome;
COMMIT;
