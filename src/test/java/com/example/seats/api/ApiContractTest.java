package com.example.seats.api;

import com.example.seats.api.Models.*;
import com.example.seats.config.SecurityConfig;
import com.example.seats.service.ReservationService;
import com.example.seats.service.ShowService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = BookingController.class, properties = {
        "app.jwt.secret=contract-test-only-secret-at-least-32-bytes"})
@Import(SecurityConfig.class)
class ApiContractTest {
    @Autowired MockMvc http;
    @MockitoBean ShowService shows;
    @MockitoBean ReservationService reservations;

    @Test
    void serializesReservationContractAndUsesTokenSubject() throws Exception {
        UUID show = UUID.randomUUID(), reservation = UUID.randomUUID();
        when(reservations.reserve(eq(show), eq("alice"), eq("key"), eq(List.of("A1"))))
                .thenReturn(new Result(201, new ReservationView(reservation, show, "alice", List.of("A1"), 25000, "confirmed"), false));
        http.perform(post("/shows/" + show + "/reserve").with(jwt().jwt(j -> j.subject("alice")))
                        .header("Idempotency-Key", "key").contentType("application/json").content("{\"seats\":[\"A1\"]}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.user_id").value("alice"))
                .andExpect(jsonPath("$.amount_paise").value(25000))
                .andExpect(header().string("Location", "/reservations/" + reservation))
                .andExpect(header().exists("X-Request-ID"));
        verify(reservations).reserve(show, "alice", "key", List.of("A1"));
    }

    @Test
    void rejectsIdentitySpoofingAndMismatchedKeysBeforeService() throws Exception {
        String path = "/shows/" + UUID.randomUUID() + "/reserve";
        http.perform(post(path).with(jwt()).header("Idempotency-Key", "key").contentType("application/json")
                        .content("{\"seats\":[\"A1\"],\"user_id\":\"victim\"}"))
                .andExpect(status().isBadRequest());
        http.perform(post(path).with(jwt()).header("Idempotency-Key", "key").contentType("application/json")
                        .content("{\"seats\":[\"A1\"],\"idempotency_key\":\"different\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(reservations);
    }

    @Test
    void rejectsFloatingPointMoneyAndMissingPrice() throws Exception {
        var admin = jwt().authorities(new SimpleGrantedAuthority("SCOPE_admin"));
        for (String suffix : List.of(",\"price_paise\":1.5", "")) {
            http.perform(post("/shows").with(admin).contentType("application/json")
                            .content("{\"name\":\"show\",\"seats\":[\"A1\"]" + suffix + "}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(shows);
    }

    @Test
    void enforcesAuthenticationAndAdminRole() throws Exception {
        http.perform(post("/shows/" + UUID.randomUUID() + "/reserve").contentType("application/json")
                        .content("{\"seats\":[\"A1\"]}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        http.perform(post("/shows").with(jwt()).contentType("application/json")
                        .content("{\"name\":\"show\",\"seats\":[\"A1\"],\"price_paise\":1}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(shows, reservations);
    }
}
