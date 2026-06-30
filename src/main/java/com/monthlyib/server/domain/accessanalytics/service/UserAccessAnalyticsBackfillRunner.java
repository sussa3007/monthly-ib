package com.monthlyib.server.domain.accessanalytics.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserAccessAnalyticsBackfillRunner {

    private final UserAccessAnalyticsService userAccessAnalyticsService;

    @Value("${monthlyib.access-analytics.backfill-last-access-on-startup:true}")
    private boolean backfillEnabled;

    @EventListener(ApplicationReadyEvent.class)
    public void backfillFromUserLastAccessAt() {
        if (!backfillEnabled) {
            return;
        }

        int insertedRows = userAccessAnalyticsService.backfillFromUserLastAccessAt();
        log.info("User access analytics lastAccessAt backfill completed. insertedRows={}", insertedRows);
    }
}
