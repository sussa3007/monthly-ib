package com.monthlyib.server.api.accessanalytics.dto;

import com.monthlyib.server.constant.Authority;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class AccessAnalyticsUserResponseDto {

    private Long userId;
    private String username;
    private String nickName;
    private String email;
    private Authority authority;
    private LocalDateTime firstAccessAt;
    private LocalDateTime lastAccessAt;
    private Long accessCount;
    private Integer accessDateCount;
}
