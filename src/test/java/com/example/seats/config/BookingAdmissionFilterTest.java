package com.example.seats.config;

import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.assertThat;

class BookingAdmissionFilterTest {
    @Test
    void boundsConcurrentWritesWhileHealthBypassesTheQueue() throws Exception {
        var filter = new BookingAdmissionFilter(2);
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<?>>();
            try {
                for (int i = 0; i < 20; i++) futures.add(executor.submit(() -> {
                    try {
                        filter.doFilter(new MockHttpServletRequest("POST", "/shows/x/reserve"),
                                new MockHttpServletResponse(), (request, response) -> {
                                    int current = active.incrementAndGet();
                                    peak.accumulateAndGet(current, Math::max);
                                    entered.countDown();
                                    try { release.await(); }
                                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                                    finally { active.decrementAndGet(); }
                                });
                    } catch (Exception e) { throw new RuntimeException(e); }
                }));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var healthReached = new AtomicInteger();
                filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health/readiness"),
                        new MockHttpServletResponse(), (request, response) -> healthReached.incrementAndGet());
                assertThat(healthReached.get()).isEqualTo(1);
                assertThat(active.get()).isEqualTo(2);
            } finally { release.countDown(); }
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
            assertThat(peak.get()).isEqualTo(2);
            assertThat(active.get()).isZero();
        }
    }
}
