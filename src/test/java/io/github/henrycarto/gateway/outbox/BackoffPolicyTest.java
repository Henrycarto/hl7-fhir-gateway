package io.github.henrycarto.gateway.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BackoffPolicyTest {

    private final BackoffPolicy policy = new BackoffPolicy(Duration.ofSeconds(5), Duration.ofMinutes(10));

    @Test
    void doublesUntilCapped() {
        assertThat(policy.delayAfter(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.delayAfter(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.delayAfter(5)).isEqualTo(Duration.ofSeconds(80));
        assertThat(policy.delayAfter(8)).isEqualTo(Duration.ofSeconds(600));
        assertThat(policy.delayAfter(60)).isEqualTo(Duration.ofSeconds(600));
    }
}
