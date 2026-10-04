CREATE TABLE shows (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    price_paise BIGINT NOT NULL CHECK (price_paise >= 0),
    per_user_limit INTEGER NOT NULL CHECK (per_user_limit BETWEEN 1 AND 100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reservations (
    id UUID PRIMARY KEY,
    show_id UUID NOT NULL REFERENCES shows(id),
    user_id VARCHAR(200) NOT NULL,
    amount_paise BIGINT NOT NULL CHECK (amount_paise >= 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('confirmed', 'cancelled')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    cancelled_at TIMESTAMPTZ,
    UNIQUE (show_id, id),
    CHECK ((status = 'cancelled') = (cancelled_at IS NOT NULL))
);
CREATE INDEX reservations_owner_idx ON reservations(show_id, user_id);

-- A single row owns the current allocation; historical bookings live separately.
CREATE TABLE show_seats (
    show_id UUID NOT NULL REFERENCES shows(id),
    seat_label VARCHAR(32) COLLATE "C" NOT NULL,
    reservation_id UUID,
    PRIMARY KEY (show_id, seat_label),
    FOREIGN KEY (show_id, reservation_id) REFERENCES reservations(show_id, id)
);
CREATE INDEX show_seats_reservation_idx ON show_seats(reservation_id) WHERE reservation_id IS NOT NULL;

CREATE TABLE reservation_seats (
    show_id UUID NOT NULL,
    reservation_id UUID NOT NULL,
    seat_label VARCHAR(32) COLLATE "C" NOT NULL,
    PRIMARY KEY (reservation_id, seat_label),
    FOREIGN KEY (show_id, reservation_id) REFERENCES reservations(show_id, id),
    FOREIGN KEY (show_id, seat_label) REFERENCES show_seats(show_id, seat_label)
);

-- This row is the per-user/per-show mutex across all application instances.
CREATE TABLE user_show_usage (
    show_id UUID NOT NULL REFERENCES shows(id),
    user_id VARCHAR(200) NOT NULL,
    active_seat_count INTEGER NOT NULL DEFAULT 0 CHECK (active_seat_count >= 0),
    PRIMARY KEY (show_id, user_id)
);

CREATE TABLE idempotency_records (
    user_id VARCHAR(200) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    key VARCHAR(128) NOT NULL,
    canonical_request TEXT NOT NULL,
    http_status INTEGER,
    response_json TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, operation, key),
    CHECK ((http_status IS NULL) = (response_json IS NULL))
);

-- Append-only rows avoid a global metrics counter becoming a hot lock.
CREATE TABLE request_outcomes (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    show_id UUID NOT NULL REFERENCES shows(id),
    outcome VARCHAR(40) NOT NULL CHECK (outcome IN (
        'confirmed', 'seat_taken', 'per_user_limit', 'idempotent_replay',
        'idempotency_key_reused', 'cancelled')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
