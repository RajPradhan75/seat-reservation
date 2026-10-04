package com.example.seats.service;

import com.example.seats.api.ApiException;
import java.util.HashSet;
import java.util.List;

final class SeatSelection {
    private SeatSelection() {}

    static List<String> canonical(List<String> seats) {
        if (new HashSet<>(seats).size() != seats.size()) {
            throw ApiException.badRequest("Seat labels must not be duplicated.");
        }
        return seats.stream().sorted().toList();
    }
}
