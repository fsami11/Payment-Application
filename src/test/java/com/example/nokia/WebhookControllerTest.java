package com.example.nokia;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookControllerTest extends BaseIntegrationTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void webhook_invalidSignature_returns400() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Stripe-Signature", "invalid-signature");

        ResponseEntity<Void> response = restTemplate.exchange(
                "/stripe/webhook",
                HttpMethod.POST,
                new HttpEntity<>("{}", headers),
                Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
