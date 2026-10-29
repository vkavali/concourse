package ai.concourse.travel;

import ai.concourse.evidence.SignObservation;
import ai.concourse.itinerary.Itinerary;
import ai.concourse.itinerary.ItineraryValidator;
import ai.concourse.itinerary.ValidatedItinerary;
import ai.concourse.journey.ContactEntity;
import ai.concourse.journey.ContactService;
import ai.concourse.journey.JourneyController;
import ai.concourse.journey.JourneyEntity;
import ai.concourse.journey.JourneyRepository;
import ai.concourse.journey.JourneyService;
import ai.concourse.spark.SparkClient;
import ai.concourse.spark.SparkRequest;
import ai.concourse.stt.SttClient;
import ai.concourse.voice.ParentTurn;
import ai.concourse.audio.Wav;
import ai.concourse.tts.TtsClient;
import ai.concourse.whatsapp.InboundMessageHandler;
import ai.concourse.whatsapp.WhatsAppClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Travel Mode's API: link-token gate, state, card, consent from the screen, a camera frame → verdict. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TravelControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JourneyService journeys;
    @Autowired JourneyController controller;
    @Autowired ItineraryValidator validator;
    @Autowired ContactService contacts;
    @Autowired JourneyRepository journeyRepo;
    @Autowired TravelLinks links;
    @Autowired ai.concourse.common.PhoneHasher hasher;
    @MockBean WhatsAppClient whatsApp;
    @MockBean TtsClient tts;
    @MockBean SparkClient spark;
    @MockBean SttClient stt;
    @MockBean InboundMessageHandler handler;

    private JourneyEntity journey;
    private String token;
    private static final String CHILD = "17165551234", PARENT = "919876501234";

    @BeforeEach
    void setUp() {
        journeyRepo.deleteAll();
        when(tts.isConfigured()).thenReturn(false);
        contacts.register(CHILD, ContactEntity.Role.CHILD, "en");
        contacts.register(PARENT, ContactEntity.Role.PARENT, "te");
        ValidatedItinerary v = validator.validate(new Itinerary(true, "e-ticket", "Lakshmi", null, List.of(
                new Itinerary.Segment("Qatar Airways", "QR4793", "HYD", "DOH", "2026-11-28T03:45", "2026-11-28T06:05", null, "B12", 1.0, ""),
                new Itinerary.Segment("Qatar Airways", "QR729", "DOH", "DFW", "2026-11-28T08:40", "2026-11-28T15:50", "D", null, 1.0, ""))));
        journey = journeys.createFromItinerary(hasher.hash(CHILD), v);
        journeys.confirmItinerary(journey.getId(), hasher.hash(CHILD));
        journeys.attachParent(hasher.hash(CHILD), hasher.hash(PARENT));
        controller.onJourneyReady(journey.getId());
        token = links.token(journey.getId());
    }

    private String url(String path) { return "/travel/api/" + journey.getId() + "/" + path + "?t=" + token; }

    @Test
    void theLinkIsTheKey() throws Exception {
        verify(whatsApp).sendLinkButton(eq(PARENT), any(), eq("తెరవండి"), contains("/travel.html#" + journey.getId() + "." + token));
        mvc.perform(get("/travel/api/" + journey.getId() + "/state?t=nope")).andExpect(status().isForbidden());
        mvc.perform(get("/travel/api/not-a-uuid/state?t=" + token)).andExpect(status().isForbidden());
        mvc.perform(get(url("state"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.traveler", is("Lakshmi")))
                .andExpect(jsonPath("$.consented", is(false)))
                .andExpect(jsonPath("$.stage.type", is("PRE_TRIP")))
                .andExpect(jsonPath("$.stage.telugu", containsString("నా ప్రయాణం మొదలు")))
                .andExpect(jsonPath("$.audio", nullValue()))
                .andExpect(jsonPath("$.staff_card", containsString("PLEASE HELP")));
        mvc.perform(get(url("card"))).andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test
    void consentAndAStageFromTheScreenThenACameraFrame() throws Exception {
        mvc.perform(post(url("action/start"))).andExpect(status().isOk());
        mvc.perform(get(url("state"))).andExpect(jsonPath("$.consented", is(true)));
        controller.fireNext(journeys.find(journey.getId()).orElseThrow()); // CHECK_IN
        controller.fireNext(journeys.find(journey.getId()).orElseThrow()); // SECURITY
        controller.fireNext(journeys.find(journey.getId()).orElseThrow()); // WAITING_AT_ORIGIN (gate B12)
        mvc.perform(get(url("state"))).andExpect(jsonPath("$.stage.type", is("WAITING_AT_ORIGIN")))
                .andExpect(jsonPath("$.stage.gate", is("B12")))
                .andExpect(jsonPath("$.stage.shows_gate_card", is(true)))
                .andExpect(jsonPath("$.stage.asks_done", is(true)));

        when(spark.completeJson(any(SparkRequest.class), eq(SignObservation.class), any(ObjectMapper.class)))
                .thenReturn(new SignObservation(true, List.of("GATES B1-B20"), List.of("B1-B20"), "left", 0.9, null));
        byte[] jpeg = new byte[2000];
        mvc.perform(post(url("frame")).contentType(MediaType.IMAGE_JPEG).content(jpeg))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind", is("MATCH")))
                .andExpect(jsonPath("$.arrow", is("left")))
                .andExpect(jsonPath("$.telugu", containsString("గేట్ B12")))
                .andExpect(jsonPath("$.telugu", containsString("ఎడమ వైపు")))
                .andExpect(jsonPath("$.stage", is("WAITING_AT_ORIGIN")));
        mvc.perform(post(url("frame")).contentType(MediaType.IMAGE_JPEG).content(jpeg)).andExpect(status().isTooManyRequests());

        // hold to talk: a recording from the screen takes the WhatsApp voice path and answers with the camera suggestion
        when(stt.transcribeWav(any(), any())).thenReturn(new SttClient.Transcript("s", "నా గేట్ ఎక్కడ", 1200));
        when(spark.completeJson(any(SparkRequest.class), eq(ParentTurn.class), any(ObjectMapper.class)))
                .thenReturn(new ParentTurn("WHERE_IS_GATE", null, 0.95, "none", "x", "where is my gate"));
        Thread.sleep(1600);
        byte[] wav = Wav.write(16_000, new byte[2 * 16_000 / 2]);
        mvc.perform(post(url("voice")).contentType("audio/wav").content(wav))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome", is("gate")))
                .andExpect(jsonPath("$.telugu", containsString("గేట్ B12")))
                .andExpect(jsonPath("$.camera", is(true)));
        verify(whatsApp).sendLinkButton(eq(PARENT), any(), eq("కెమెరా తెరవండి"), org.mockito.ArgumentMatchers.endsWith(".cam"));
        mvc.perform(get(url("state"))).andExpect(jsonPath("$.suggest", is("camera")));

        mvc.perform(post(url("action/help"))).andExpect(status().isOk()).andExpect(jsonPath("$.staff_card", containsString("Gate B12")));
        verify(whatsApp).sendText(eq(CHILD), contains("pressed HELP on the travel screen"));
        mvc.perform(post(url("action/done"))).andExpect(status().isOk()).andExpect(jsonPath("$.status", is("WAITING_AT_ORIGIN")));
    }
}
