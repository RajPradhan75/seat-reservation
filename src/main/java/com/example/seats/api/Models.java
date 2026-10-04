package com.example.seats.api;

import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public final class Models {
    private Models() {}

    public record CreateShowRequest(
            @NotBlank @Size(max = 200) String name,
            @NotEmpty @Size(max = 50000) List<@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,32}") String> seats,
            @NotNull @Min(0) @Max(92233720368547758L) Long pricePaise,
            @Min(1) @Max(100) Integer perUserLimit) {}

    public record ReserveRequest(
            @NotEmpty @Size(max = 100) List<@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,32}") String> seats,
            @Size(min = 1, max = 128) String idempotencyKey) {}

    public record SeatView(String seat, String status) {}
    public record Counts(long available, long held, long confirmed, long totalSeats) {}
    public record ShowView(UUID id, String name, long pricePaise, int perUserLimit,
                           List<SeatView> seats, Counts counts) {}
    public record ReservationView(UUID reservationId, UUID showId, String userId,
                                  List<String> seats, long amountPaise, String status) {}
    public record ErrorBody(String code, String message, String requestId) {}
    public record StoredError(String code, String message) {}
    public record Result(int status, Object body, boolean replay) {}
}
