package com.example.seats.api;

import com.example.seats.api.Models.*;
import com.example.seats.service.ReservationService;
import com.example.seats.service.ShowService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class BookingController {
    private final ShowService shows;
    private final ReservationService reservations;
    private final JsonMapper json;

    public BookingController(ShowService shows, ReservationService reservations, JsonMapper json) {
        this.shows = shows;
        this.reservations = reservations;
        this.json = json;
    }

    @PostMapping("/shows")
    ResponseEntity<ShowView> create(@Valid @RequestBody CreateShowRequest request) {
        var show = shows.create(request);
        return ResponseEntity.created(URI.create("/shows/" + show.id())).body(show);
    }

    @GetMapping("/shows/{id}")
    ShowView show(@PathVariable UUID id) { return shows.get(id); }

    @PostMapping("/shows/{id}/reserve")
    ResponseEntity<?> reserve(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
                             @RequestHeader(value = "Idempotency-Key", required = false) String headerKey,
                             @Valid @RequestBody ReserveRequest body, HttpServletRequest request) {
        if (headerKey != null && body.idempotencyKey() != null && !headerKey.equals(body.idempotencyKey())) {
            throw ApiException.badRequest("Header and body idempotency keys must match.");
        }
        String key = headerKey != null ? headerKey : body.idempotencyKey();
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw ApiException.badRequest("An idempotency key of 1-128 letters, digits, '.', '_', ':', or '-' is required.");
        }
        Result result = reservations.reserve(id, jwt.getSubject(), key, body.seats());
        var tree = json.valueToTree(result.body());
        request.setAttribute("outcome", result.replay() ? "idempotent_replay"
                : result.status() < 400 ? "confirmed" : tree.get("code").asText());
        var response = ResponseEntity.status(result.status())
                .header("Idempotency-Replayed", Boolean.toString(result.replay()));
        if (result.status() >= 400) {
            return response.body(new ErrorBody(tree.get("code").asText(), tree.get("message").asText(),
                    org.slf4j.MDC.get("request_id")));
        }
        return response.location(URI.create("/reservations/" + tree.get("reservation_id").asText())).body(tree);
    }

    @GetMapping("/reservations/{id}")
    ReservationView reservation(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return reservations.get(id, jwt.getSubject());
    }

    @PostMapping("/reservations/{id}/cancel")
    ReservationView cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        var result = reservations.cancel(id, jwt.getSubject());
        request.setAttribute("outcome", "cancelled");
        return result;
    }
}
