package com.revesoft.tms.push;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Firebase Cloud Messaging via the Firebase Admin SDK, authenticated with a service-account JSON file. */
public class FcmPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(FcmPushSender.class);
    private static final int BATCH = 500; // FCM multicast limit

    /** Errors meaning the token will never work again. */
    private static final Set<MessagingErrorCode> DEAD = Set.of(
            MessagingErrorCode.UNREGISTERED, MessagingErrorCode.SENDER_ID_MISMATCH, MessagingErrorCode.INVALID_ARGUMENT);

    private final FirebaseMessaging messaging;

    public FcmPushSender(FirebaseMessaging messaging) {
        this.messaging = messaging;
    }

    public static FcmPushSender fromServiceAccount(Path credentialsFile) {
        try (InputStream in = Files.newInputStream(credentialsFile)) {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(in))
                    .build();
            FirebaseApp app = FirebaseApp.getApps().stream().filter(a -> a.getName().equals("tms")).findFirst()
                    .orElseGet(() -> FirebaseApp.initializeApp(options, "tms"));
            log.info("Push notifications: Firebase Cloud Messaging enabled ({})", credentialsFile.getFileName());
            return new FcmPushSender(FirebaseMessaging.getInstance(app));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the Firebase service account file " + credentialsFile, e);
        }
    }

    @Override
    public Set<String> send(List<String> tokens, PushMessage message) {
        Set<String> invalid = new HashSet<>();
        for (int from = 0; from < tokens.size(); from += BATCH) {
            List<String> batch = tokens.subList(from, Math.min(from + BATCH, tokens.size()));
            MulticastMessage msg = MulticastMessage.builder()
                    .addAllTokens(batch)
                    .setNotification(Notification.builder().setTitle(message.title()).setBody(message.body()).build())
                    .putAllData(message.data())
                    .setAndroidConfig(AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH).build())
                    .setApnsConfig(ApnsConfig.builder().setAps(Aps.builder().setSound("default").build()).build())
                    .build();
            try {
                BatchResponse response = messaging.sendEachForMulticast(msg);
                List<SendResponse> results = response.getResponses();
                for (int i = 0; i < results.size(); i++) {
                    SendResponse r = results.get(i);
                    if (!r.isSuccessful()) {
                        FirebaseMessagingException e = r.getException();
                        if (e != null && DEAD.contains(e.getMessagingErrorCode())) {
                            invalid.add(batch.get(i));
                        } else {
                            log.warn("Push to one device failed: {}", e == null ? "unknown" : e.getMessage());
                        }
                    }
                }
            } catch (FirebaseMessagingException e) {
                log.warn("Push batch failed: {}", e.getMessage());
            }
        }
        return invalid;
    }
}
