-- Collapse the common hot-seat decline into one database round trip.
-- This function can reject occupied seats; only the locked Java transaction can allocate them.
CREATE FUNCTION decline_occupied_seats(
    p_show UUID, p_user TEXT, p_key TEXT, p_canonical TEXT, p_seats TEXT
) RETURNS TABLE(http_status INTEGER, response_json TEXT, replayed BOOLEAN)
LANGUAGE plpgsql AS $$
DECLARE
    requested TEXT[];
    observed_count INTEGER;
    occupied BOOLEAN;
    created BOOLEAN;
    prior idempotency_records%ROWTYPE;
    decline_body TEXT := '{"code":"SEAT_TAKEN","message":"One or more requested seats are unavailable."}';
BEGIN
    SELECT array_agg(value) INTO requested FROM jsonb_array_elements_text(p_seats::jsonb);
    SELECT count(*), bool_or(reservation_id IS NOT NULL) INTO observed_count, occupied
    FROM show_seats WHERE show_id = p_show AND seat_label = ANY(requested);
    IF observed_count <> cardinality(requested) OR NOT coalesce(occupied, false) THEN
        RETURN;
    END IF;

    -- Occupancy is the decline's linearization point. A later cancellation does not undo it.
    -- The unique key waits for a concurrent original transaction to commit or roll back.
    INSERT INTO idempotency_records(user_id, operation, key, canonical_request, http_status, response_json)
    VALUES (p_user, 'reserve', p_key, p_canonical, 409, decline_body)
    ON CONFLICT DO NOTHING RETURNING true INTO created;
    IF coalesce(created, false) THEN
        INSERT INTO request_outcomes(show_id, outcome) VALUES (p_show, 'seat_taken');
        RETURN QUERY SELECT 409, decline_body, false;
        RETURN;
    END IF;

    -- A separate statement sees the original commit after ON CONFLICT has waited.
    SELECT * INTO STRICT prior FROM idempotency_records
    WHERE user_id = p_user AND operation = 'reserve' AND key = p_key;
    IF prior.canonical_request <> p_canonical THEN
        INSERT INTO request_outcomes(show_id, outcome) VALUES (p_show, 'idempotency_key_reused');
        RETURN QUERY SELECT 409,
            '{"code":"IDEMPOTENCY_KEY_REUSED","message":"This key has already been used for a different request."}'::TEXT,
            false;
    ELSE
        INSERT INTO request_outcomes(show_id, outcome) VALUES (p_show, 'idempotent_replay');
        RETURN QUERY SELECT CASE WHEN prior.http_status = 201 THEN 200 ELSE prior.http_status END,
            prior.response_json, true;
    END IF;
END;
$$;
