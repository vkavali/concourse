package ai.concourse.ops;

import ai.concourse.journey.JourneyRepository;
import ai.concourse.tts.TtsClient;
import ai.concourse.whatsapp.InboundMessageHandler;
import ai.concourse.whatsapp.WhatsAppClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The simulator's API: a demo journey without a ticket, acting for her, the outbox of what each phone got. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpsControllerTest {
    private static final String TOKEN = "test-admin"; // application-test.yml
    @Autowired MockMvc mvc;
    @Autowired JourneyRepository journeys;
    @MockBean WhatsAppClient whatsApp;
    @MockBean TtsClient tts;
    @MockBean InboundMessageHandler handler;

    @BeforeEach
    void setUp() {
        journeys.deleteAll();
        when(tts.isConfigured()).thenReturn(false);
    }

    @Test
    void everythingNeedsTheAdminToken() throws Exception {
        mvc.perform(get("/ops/journeys/latest")).andExpect(status().isForbidden());
        mvc.perform(post("/ops/demo").contentType(MediaType.APPLICATION_JSON).content("{\"child\":\"17165550123\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/ops/events").header("X-Admin-Token", "wrong").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"GATE_CHANGE\",\"new_gate\":\"C7\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/ops/clock")).andExpect(status().isOk()).andExpect(jsonPath("$.travelling", is(false)));
    }

    @Test
    void demoJourneyThenConsentThenNextStageThenGateChange() throws Exception {
        mvc.perform(post("/ops/demo").header("X-Admin-Token", TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"child\":\"+1 716 555 0123\",\"parent\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parent_is_child", is(true)));
        verify(whatsApp).sendButtons(eq("17165550123"), contains("నా ప్రయాణం మొదలు"), any());

        mvc.perform(get("/ops/journeys/latest").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traveler", is("Lakshmi")))
                .andExpect(jsonPath("$.intro_sent", is(true)))
                .andExpect(jsonPath("$.consent_at", nullValue()))
                .andExpect(jsonPath("$.stages", hasSize(17)))
                .andExpect(jsonPath("$.stages[1].type", is("CHECK_IN")))
                .andExpect(jsonPath("$.flights[1].gate", nullValue()));

        mvc.perform(post("/ops/journeys/latest/consent").header("X-Admin-Token", TOKEN)).andExpect(status().isOk());
        mvc.perform(post("/ops/journeys/latest/next").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fired", is("CHECK_IN")))
                .andExpect(jsonPath("$.status", is("CHECK_IN")));
        verify(whatsApp).sendButtons(eq("17165550123"), contains("కౌంటర్"), any());

        mvc.perform(post("/ops/events").header("X-Admin-Token", TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"GATE_CHANGE\",\"segment\":2,\"new_gate\":\"C7\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source", is("demo operations event")));
        mvc.perform(get("/ops/journeys/latest").header("X-Admin-Token", TOKEN))
                .andExpect(jsonPath("$.flights[1].gate", is("C7")))
                .andExpect(jsonPath("$.clock.travelling", is(true)));

        // the outbox shows both phones by role, never a number
        mvc.perform(get("/ops/outbox").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role", is("parent")))
                .andExpect(jsonPath("$[0].kind", is("buttons")))
                .andExpect(jsonPath("$[0].buttons[0]", is("నా ప్రయాణం మొదలు")))
                .andExpect(jsonPath("$[*].text", not(hasItem(containsString("17165550123")))));

        // a second demo journey for the same traveler abandons the first
        mvc.perform(post("/ops/demo").header("X-Admin-Token", TOKEN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"child\":\"17165550123\"}")).andExpect(status().isOk());
        mvc.perform(post("/ops/clock").header("X-Admin-Token", TOKEN).contentType(MediaType.APPLICATION_JSON).content("{\"reset\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.travelling", is(false)));
    }
}
