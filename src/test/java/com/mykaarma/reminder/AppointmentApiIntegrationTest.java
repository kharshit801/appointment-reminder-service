package com.mykaarma.reminder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AppointmentApiIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void createReturns201WithReminders() throws Exception {
        String future = Instant.now().plus(3, ChronoUnit.DAYS).toString();
        String body = """
                {
                  "dealershipId": "dealer-1",
                  "customerName": "Jane Doe",
                  "customerContact": "jane@example.com",
                  "scheduledAt": "%s"
                }
                """.formatted(future);

        mockMvc.perform(post("/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.status", is("BOOKED")))
                .andExpect(jsonPath("$.reminders", hasSize(2)));
    }

    @Test
    void getReturnsCreatedAppointment() throws Exception {
        String future = Instant.now().plus(3, ChronoUnit.DAYS).toString();
        String body = """
                {"dealershipId":"dealer-2","customerName":"John","customerContact":"+15551234567","scheduledAt":"%s"}
                """.formatted(future);

        String response = mockMvc.perform(post("/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode created = objectMapper.readTree(response);
        String id = created.get("id").asText();

        mockMvc.perform(get("/appointments/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(id)))
                .andExpect(jsonPath("$.dealershipId", is("dealer-2")))
                .andExpect(jsonPath("$.reminders", hasSize(2)));
    }

    @Test
    void createRejectsMissingFieldsWith400() throws Exception {
        String body = """
                {"customerName":"Jane","customerContact":"jane@example.com"}
                """;
        mockMvc.perform(post("/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRejectsPastScheduledTimeWith400() throws Exception {
        String past = Instant.now().minus(1, ChronoUnit.DAYS).toString();
        String body = """
                {"dealershipId":"d","customerName":"Jane","customerContact":"jane@example.com","scheduledAt":"%s"}
                """.formatted(past);
        mockMvc.perform(post("/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getUnknownAppointmentReturns404() throws Exception {
        mockMvc.perform(get("/appointments/{id}", "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
    }
}
