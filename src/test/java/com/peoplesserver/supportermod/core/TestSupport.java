package com.peoplesserver.supportermod.core;

import com.peoplesserver.supportermod.platform.Messenger;
import com.peoplesserver.supportermod.platform.PlayerDirectory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Test doubles for the platform ports. */
final class TestSupport {

    private TestSupport() {}

    /** A clock the test moves by hand, so expiry can be tested without waiting. */
    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant start) {
            this.instant = start;
        }

        void advance(Duration by) {
            instant = instant.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    static final class FakeDirectory implements PlayerDirectory {
        private final Map<String, UUID> byName = new HashMap<>();
        private final Set<UUID> online = new HashSet<>();

        void register(String username, UUID uuid) {
            byName.put(username.toLowerCase(Locale.ROOT), uuid);
        }

        void setOnline(UUID uuid, boolean isOnline) {
            if (isOnline) {
                online.add(uuid);
            } else {
                online.remove(uuid);
            }
        }

        @Override
        public Optional<UUID> uuidFor(String username) {
            return Optional.ofNullable(byName.get(username.toLowerCase(Locale.ROOT)));
        }

        @Override
        public Optional<String> usernameFor(UUID uuid) {
            return byName.entrySet().stream()
                    .filter(e -> e.getValue().equals(uuid))
                    .map(Map.Entry::getKey)
                    .findFirst();
        }

        @Override
        public boolean isOnline(UUID uuid) {
            return online.contains(uuid);
        }
    }

    static final class RecordingMessenger implements Messenger {
        final List<String> sent = new ArrayList<>();
        final List<String> broadcasts = new ArrayList<>();

        @Override
        public void send(UUID uuid, String message) {
            sent.add(uuid + ": " + message);
        }

        @Override
        public void broadcast(String message) {
            broadcasts.add(message);
        }

        boolean sentContaining(String fragment) {
            return sent.stream().anyMatch(m -> m.contains(fragment));
        }
    }
}
