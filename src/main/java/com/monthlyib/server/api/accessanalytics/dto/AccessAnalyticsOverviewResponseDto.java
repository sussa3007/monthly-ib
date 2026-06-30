package com.monthlyib.server.api.accessanalytics.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class AccessAnalyticsOverviewResponseDto {

    private AccessAnalyticsSummaryResponseDto summary;
    private List<AccessAnalyticsBucketResponseDto> dailyBuckets;
    private List<AccessAnalyticsBucketResponseDto> weeklyBuckets;
    private List<AccessAnalyticsBucketResponseDto> monthlyBuckets;
    private LocalDateTime generatedAt;
}
