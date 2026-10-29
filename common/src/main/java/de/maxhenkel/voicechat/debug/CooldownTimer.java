package de.maxhenkel.voicechat.debug;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import java.time.Duration;

public class CooldownTimer {

    /**
     * Entries are evicted after this time, so cooldowns can't be longer than this.
     */
    private static final long MAX_COOLDOWN = 60_000L;

    private static final Cache<String, Long> cooldowns = CacheBuilder.newBuilder()
            .expireAfterWrite(Duration.ofMillis(MAX_COOLDOWN))
            .build();

    public static void run(String id, long time, Runnable runnable) {
        if (time > MAX_COOLDOWN) {
            throw new IllegalArgumentException("Cooldown can't be longer than %s ms".formatted(MAX_COOLDOWN));
        }
        Long lastRun = cooldowns.getIfPresent(id);
        if (lastRun == null || System.currentTimeMillis() - lastRun > time) {
            cooldowns.put(id, System.currentTimeMillis());
            runnable.run();
        }
    }

    public static void run(String id, Runnable runnable) {
        run(id, 10_000L, runnable);
    }

}
