package me.apet97.breakcompliance.addon.lifecycle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Logs unknown top-level keys in inbound Clockify payloads (lifecycle,
 * webhook) so that schema additions don't drift silently past the
 * handlers. Each {@code (context, sorted-unknown-keys)} fingerprint is
 * logged at most once per process lifetime — the first sighting emits a
 * WARN, subsequent sightings are silent.
 *
 * <p>Use this as a tripwire, not a validator. The handler itself remains
 * responsible for the values it consumes. A WARN here is the cue to
 * audit the Clockify spec for a new field and decide whether to consume
 * it, ignore it explicitly, or model it as an opt-in feature.
 */
@Component
public class PayloadDriftLogger {

    private static final Logger log = LoggerFactory.getLogger(PayloadDriftLogger.class);

    private final ConcurrentHashMap<String, Boolean> seenFingerprints = new ConcurrentHashMap<>();

    public void check(String context, Map<String, Object> payload, Set<String> knownKeys) {
        if (payload == null || payload.isEmpty() || knownKeys == null) {
            return;
        }
        List<String> unknown = new ArrayList<>();
        for (String key : payload.keySet()) {
            if (key != null && !knownKeys.contains(key)) {
                unknown.add(key);
            }
        }
        if (unknown.isEmpty()) {
            return;
        }
        Collections.sort(unknown);
        String fingerprint = context + ":" + String.join(",", unknown);
        if (seenFingerprints.putIfAbsent(fingerprint, Boolean.TRUE) != null) {
            return;
        }
        log.warn("payload.drift context={} unknownKeys={}", context, unknown);
    }

    int seenFingerprintCount() {
        return seenFingerprints.size();
    }

    void resetSeenFingerprints() {
        seenFingerprints.clear();
    }
}
