package com.monthlyib.server.api.accessanalytics.controller;

import com.monthlyib.server.annotation.UserSession;
import com.monthlyib.server.api.accessanalytics.dto.AccessAnalyticsDetailsResponseDto;
import com.monthlyib.server.api.accessanalytics.dto.AccessAnalyticsOverviewResponseDto;
import com.monthlyib.server.domain.accessanalytics.service.UserAccessAnalyticsService;
import com.monthlyib.server.domain.user.entity.User;
import com.monthlyib.server.dto.ResponseDto;
import com.monthlyib.server.dto.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/access-analytics")
public class AdminAccessAnalyticsController {

    private final UserAccessAnalyticsService userAccessAnalyticsService;

    @GetMapping("/overview")
    public ResponseEntity<ResponseDto<?>> getOverview(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "12") int weeks,
            @RequestParam(defaultValue = "12") int months,
            @UserSession User user
    ) {
        AccessAnalyticsOverviewResponseDto response = userAccessAnalyticsService.getOverview(user, days, weeks, months);
        return ResponseEntity.ok(ResponseDto.of(response, Result.ok()));
    }

    @GetMapping("/details")
    public ResponseEntity<ResponseDto<?>> getDetails(
            @RequestParam String periodType,
            @RequestParam String period,
            @UserSession User user
    ) {
        AccessAnalyticsDetailsResponseDto response = userAccessAnalyticsService.getDetails(user, periodType, period);
        return ResponseEntity.ok(ResponseDto.of(response, Result.ok()));
    }
}
