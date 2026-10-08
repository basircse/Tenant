package com.revesoft.tms.push;

import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Used when Firebase is not configured: only logs what would have been pushed. */
public class LoggingPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingPushSender.class);

    @Override
    public Set<String> send(List<String> tokens, PushMessage message) {
        log.debug("Push (not configured) to {} device(s): {}", tokens.size(), message.title());
        return Set.of();
    }
}
