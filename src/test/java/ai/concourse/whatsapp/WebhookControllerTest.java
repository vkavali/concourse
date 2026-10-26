package ai.concourse.whatsapp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebhookControllerTest {
    @Autowired MockMvc mvc;
    @MockBean InboundMessageHandler handler;

    @Test
    void verifyHandshakeEchoesChallenge() throws Exception {
        mvc.perform(get("/webhook/whatsapp")
                        .param("hub.mode", "subscribe")
                        .param("hub.verify_token", "verify-me")
                        .param("hub.challenge", "12345"))
                .andExpect(status().isOk())
                .andExpect(content().string("12345"));
    }

    @Test
    void verifyRejectsWrongToken() throws Exception {
        mvc.perform(get("/webhook/whatsapp")
                        .param("hub.mode", "subscribe")
                        .param("hub.verify_token", "nope")
                        .param("hub.challenge", "12345"))
                .andExpect(status().isForbidden());
    }

    @Test
    void postWithBadSignatureIsUnauthorized() throws Exception {
        mvc.perform(post("/webhook/whatsapp")
                        .header("X-Hub-Signature-256", "sha256=bad")
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
