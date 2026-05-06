package ru.truconf.proxydb.truconf;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TrueConfRateLimiterTests {

  @Test
  void spacesPermitsEvenlyAcrossOneSecond() {
    AtomicLong now = new AtomicLong();
    List<Long> sleeps = new ArrayList<>();
    TrueConfRateLimiter limiter = new TrueConfRateLimiter(
        10,
        now::get,
        nanos -> {
          sleeps.add(nanos);
          now.addAndGet(nanos);
        });

    limiter.acquire();
    limiter.acquire();
    limiter.acquire();

    assertThat(sleeps).containsExactly(100_000_000L, 100_000_000L);
    assertThat(now.get()).isEqualTo(200_000_000L);
  }
}
