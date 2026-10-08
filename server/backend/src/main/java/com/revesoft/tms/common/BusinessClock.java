package com.revesoft.tms.common;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Business "today" in the configured time zone. Replaceable in tests. */
@Component
public class BusinessClock {

    private Clock clock;

    public BusinessClock(@Value("${tms.zone}") String zone) {
        this.clock = Clock.system(ZoneId.of(zone));
    }

    public java.time.Instant now() {
        return clock.instant();
    }

    public ZoneId zone() {
        return clock.getZone();
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public YearMonth currentMonth() {
        return YearMonth.from(today());
    }

    /** For tests only. */
    public void setClock(Clock clock) {
        this.clock = clock;
    }
}
