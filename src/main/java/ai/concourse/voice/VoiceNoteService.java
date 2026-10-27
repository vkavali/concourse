package ai.concourse.voice;

import ai.concourse.audio.AudioTranscoder;
import ai.concourse.stt.SttClient;
import ai.concourse.whatsapp.MessagingChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Inbound voice note → transcript. Day 1 stops here (the transcript is echoed back for the voice gate);
 * Day 2 adds intent extraction (Spark), the journey tools, and the Telugu voice reply.
 */
@Service
public class VoiceNoteService {
    private static final Logger log = LoggerFactory.getLogger(VoiceNoteService.class);
    public static final List<String> PARENT_LANGUAGE_BIAS = List.of("Telugu", "English");

    private final MessagingChannel whatsApp;
    private final AudioTranscoder transcoder;
    private final SttClient stt;

    public VoiceNoteService(MessagingChannel whatsApp, AudioTranscoder transcoder, SttClient stt) {
        this.whatsApp = whatsApp;
        this.transcoder = transcoder;
        this.stt = stt;
    }

    public SttClient.Transcript transcribe(String mediaId) {
        byte[] ogg = whatsApp.downloadMedia(mediaId);
        byte[] wav = transcoder.toWav16kMono(ogg);
        long ms = AudioTranscoder.wavDurationMs(wav);
        if (ms > 10 * 60 * 1000L) throw new IllegalArgumentException("voice note longer than 10 minutes");
        SttClient.Transcript t = stt.transcribeWav(wav, PARENT_LANGUAGE_BIAS);
        log.info("voice note transcribed: {} ms, {} chars", ms, t.text().length());
        return t;
    }
}
