package com.example.seats.api;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** A bounded, sanitized view of recent API activity for the public assignment demo. */
@RestController
public class LogsController {
    private final ArrayDeque<Event> events = new ArrayDeque<>();

    public synchronized void record(String id, String method, String path, int status,
                                    long duration, Object outcome) {
        // Do not publish arbitrary paths, query strings, identities, headers, or request bodies.
        String route;
        if (path.equals("/shows")) route = "/shows";
        else if (path.matches("/shows/[0-9a-fA-F-]{36}/reserve")) route = "/shows/{id}/reserve";
        else if (path.matches("/shows/[0-9a-fA-F-]{36}")) route = "/shows/{id}";
        else if (path.matches("/reservations/[0-9a-fA-F-]{36}/cancel")) route = "/reservations/{id}/cancel";
        else if (path.matches("/reservations/[0-9a-fA-F-]{36}")) route = "/reservations/{id}";
        else return;
        if (events.size() == 1000) events.removeFirst();
        events.addLast(new Event(Instant.now(), id, method, route, status, duration,
                outcome == null ? null : outcome.toString()));
    }

    @GetMapping("/logs")
    public synchronized ResponseEntity<?> recent() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of(
                "retention", "Last 1000 API requests on this instance; cleared on restart",
                "events", List.copyOf(events)));
    }

    public record Event(Instant timestamp, String requestId, String method, String route,
                        int status, long durationMs, String outcome) {}
}
