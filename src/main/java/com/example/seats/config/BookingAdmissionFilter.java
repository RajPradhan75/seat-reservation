package com.example.seats.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Queue writes before authentication and DB work, leaving capacity for health and reads. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class BookingAdmissionFilter extends OncePerRequestFilter {
    private final Semaphore writers;

    public BookingAdmissionFilter(@Value("${app.booking.concurrency:12}") int concurrency) {
        if (concurrency < 1) throw new IllegalArgumentException("Booking concurrency must be positive");
        writers = new Semaphore(concurrency, true);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !request.getMethod().equals("POST")
                || !(path.equals("/shows") || path.endsWith("/reserve") || path.endsWith("/cancel"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            writers.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            response.sendError(503, "Service is shutting down");
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            writers.release();
        }
    }
}
