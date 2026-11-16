package com.seniorinsight.gateway.infrastructure.redis;

import java.util.List;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import com.seniorinsight.gateway.application.port.out.RateLimitStore;
import com.seniorinsight.gateway.application.port.out.RateLimitStore.Quota;

import reactor.core.publisher.Mono;

/** Redis adapter that atomically increments and expires each user/window counter. */
@Repository
public class RedisRateLimitStore implements RateLimitStore {

    private static final String RATE_LIMIT_SCRIPT = """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            local ttl = redis.call('TTL', KEYS[1])
            local remaining = math.max(tonumber(ARGV[2]) - count, 0)
            if count <= tonumber(ARGV[2]) then return {1, remaining, ttl} end
            return {0, remaining, ttl}
            """;

    @SuppressWarnings("unchecked")
    private final RedisScript<List<Long>> script = new DefaultRedisScript<>(RATE_LIMIT_SCRIPT,
            (Class<List<Long>>) (Class<?>) List.class);

    private final ReactiveStringRedisTemplate redis;

    public RedisRateLimitStore(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Mono<Quota> consume(String key, int ttlSeconds, int limit) {
        return redis.execute(script, List.of(key), Integer.toString(ttlSeconds), Integer.toString(limit))
                .next()
                .map(result -> new Quota(
                        result.get(0) == 1L,
                        result.get(1),
                        Math.max(result.get(2), 1L)))
                .switchIfEmpty(Mono.error(new IllegalStateException("Redis rate limit script returned no result")));
    }
}
