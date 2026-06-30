package com.monthlyib.server.domain.accessanalytics.service;

import com.monthlyib.server.api.accessanalytics.dto.AccessAnalyticsBucketResponseDto;
import com.monthlyib.server.api.accessanalytics.dto.AccessAnalyticsDetailsResponseDto;
import com.monthlyib.server.api.accessanalytics.dto.AccessAnalyticsOverviewResponseDto;
import com.monthlyib.server.api.accessanalytics.dto.AccessAnalyticsSummaryResponseDto;
import com.monthlyib.server.api.accessanalytics.dto.AccessAnalyticsUserResponseDto;
import com.monthlyib.server.constant.Authority;
import com.monthlyib.server.constant.ErrorCode;
import com.monthlyib.server.domain.accessanalytics.entity.UserAccessDaily;
import com.monthlyib.server.domain.accessanalytics.repository.UserAccessDailyJpaRepository;
import com.monthlyib.server.domain.user.entity.User;
import com.monthlyib.server.exception.ServiceLogicException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class UserAccessAnalyticsService {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter WEEK_FORMATTER = DateTimeFormatter.ofPattern("YYYY-'W'ww");
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    private final UserAccessDailyJpaRepository userAccessDailyJpaRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAccess(User user, LocalDateTime accessAt) {
        if (user == null || user.getAuthority() != Authority.USER || user.getUserId() == null) {
            return;
        }

        LocalDateTime safeAccessAt = accessAt == null ? now() : accessAt;
        userAccessDailyJpaRepository.upsertAccess(safeAccessAt.toLocalDate(), user.getUserId(), safeAccessAt);
    }

    @Transactional(readOnly = true)
    public AccessAnalyticsOverviewResponseDto getOverview(User adminUser, int days, int weeks, int months) {
        verifyAdmin(adminUser);

        int safeDays = Math.max(7, Math.min(days, 120));
        int safeWeeks = Math.max(4, Math.min(weeks, 52));
        int safeMonths = Math.max(3, Math.min(months, 36));
        LocalDate today = today();
        LocalDate dailyStartDate = today.minusDays(safeDays - 1L);
        LocalDate weeklyStartDate = startOfWeek(today).minusWeeks(safeWeeks - 1L);
        LocalDate monthlyStartDate = YearMonth.from(today).minusMonths(safeMonths - 1L).atDay(1);
        LocalDate queryStartDate = min(dailyStartDate, min(weeklyStartDate, monthlyStartDate));

        List<UserAccessDaily> accesses = userAccessDailyJpaRepository.findUserAccesses(
                queryStartDate,
                today,
                Authority.USER
        );

        Map<LocalDate, Set<Long>> usersByDate = usersByDate(accesses);
        Map<String, WeekBucket> weekBuckets = initWeekBuckets(weeklyStartDate, safeWeeks);
        Map<YearMonth, MonthBucket> monthBuckets = initMonthBuckets(monthlyStartDate, safeMonths);
        for (UserAccessDaily access : accesses) {
            WeekBucket bucket = weekBuckets.get(weekKey(access.getAccessDate()));
            if (bucket != null) {
                bucket.userIds.add(access.getUser().getUserId());
            }
            MonthBucket monthBucket = monthBuckets.get(YearMonth.from(access.getAccessDate()));
            if (monthBucket != null) {
                monthBucket.userIds.add(access.getUser().getUserId());
            }
        }

        List<AccessAnalyticsBucketResponseDto> dailyBuckets = new ArrayList<>();
        for (int i = 0; i < safeDays; i++) {
            LocalDate date = dailyStartDate.plusDays(i);
            dailyBuckets.add(AccessAnalyticsBucketResponseDto.builder()
                    .period(date.toString())
                    .label(date.format(DateTimeFormatter.ofPattern("MM.dd")))
                    .startDate(date.toString())
                    .endDate(date.toString())
                    .uniqueUserCount(usersByDate.getOrDefault(date, Set.of()).size())
                    .build());
        }

        List<AccessAnalyticsBucketResponseDto> weeklyBuckets = weekBuckets.values().stream()
                .map(WeekBucket::toResponse)
                .toList();
        List<AccessAnalyticsBucketResponseDto> monthlyBuckets = monthBuckets.values().stream()
                .map(MonthBucket::toResponse)
                .toList();

        return AccessAnalyticsOverviewResponseDto.builder()
                .summary(AccessAnalyticsSummaryResponseDto.builder()
                        .todayUsers(uniqueUsersBetween(accesses, today, today))
                        .last7DaysUsers(uniqueUsersBetween(accesses, today.minusDays(6), today))
                        .last30DaysUsers(uniqueUsersBetween(accesses, today.minusDays(29), today))
                        .thisWeekUsers(uniqueUsersBetween(accesses, startOfWeek(today), today))
                .build())
                .dailyBuckets(dailyBuckets)
                .weeklyBuckets(weeklyBuckets)
                .monthlyBuckets(monthlyBuckets)
                .generatedAt(now())
                .build();
    }

    @Transactional(readOnly = true)
    public AccessAnalyticsDetailsResponseDto getDetails(User adminUser, String periodType, String period) {
        verifyAdmin(adminUser);

        DateRange range = resolveRange(periodType, period);
        List<UserAccessDaily> accesses = userAccessDailyJpaRepository.findUserAccesses(
                range.startDate(),
                range.endDate(),
                Authority.USER
        );

        Map<Long, UserAccessAccumulator> users = new LinkedHashMap<>();
        for (UserAccessDaily access : accesses) {
            users.computeIfAbsent(access.getUser().getUserId(), ignored -> new UserAccessAccumulator(access.getUser()))
                    .add(access);
        }

        List<AccessAnalyticsUserResponseDto> userResponses = users.values().stream()
                .sorted(Comparator.comparing(UserAccessAccumulator::firstAccessAt))
                .map(UserAccessAccumulator::toResponse)
                .toList();

        return AccessAnalyticsDetailsResponseDto.builder()
                .periodType(normalizePeriodType(periodType))
                .period(period)
                .startDate(range.startDate().toString())
                .endDate(range.endDate().toString())
                .uniqueUserCount(userResponses.size())
                .users(userResponses)
                .generatedAt(now())
                .build();
    }

    private Map<LocalDate, Set<Long>> usersByDate(List<UserAccessDaily> accesses) {
        Map<LocalDate, Set<Long>> result = new LinkedHashMap<>();
        for (UserAccessDaily access : accesses) {
            result.computeIfAbsent(access.getAccessDate(), ignored -> new LinkedHashSet<>())
                    .add(access.getUser().getUserId());
        }
        return result;
    }

    private long uniqueUsersBetween(List<UserAccessDaily> accesses, LocalDate startDate, LocalDate endDate) {
        Set<Long> userIds = new LinkedHashSet<>();
        for (UserAccessDaily access : accesses) {
            if (!access.getAccessDate().isBefore(startDate) && !access.getAccessDate().isAfter(endDate)) {
                userIds.add(access.getUser().getUserId());
            }
        }
        return userIds.size();
    }

    private Map<String, WeekBucket> initWeekBuckets(LocalDate startDate, int weeks) {
        Map<String, WeekBucket> result = new LinkedHashMap<>();
        LocalDate weekStart = startOfWeek(startDate);
        for (int i = 0; i < weeks; i++) {
            LocalDate currentStart = weekStart.plusWeeks(i);
            WeekBucket bucket = new WeekBucket(currentStart, currentStart.plusDays(6));
            result.put(weekKey(currentStart), bucket);
        }
        return result;
    }

    private DateRange resolveRange(String periodType, String period) {
        String normalizedType = normalizePeriodType(periodType);
        if ("DAY".equals(normalizedType)) {
            LocalDate date = LocalDate.parse(period);
            return new DateRange(date, date);
        }
        if ("WEEK".equals(normalizedType)) {
            LocalDate weekStart = parseIsoWeek(period);
            return new DateRange(weekStart, weekStart.plusDays(6));
        }
        if ("MONTH".equals(normalizedType)) {
            YearMonth yearMonth = YearMonth.parse(period, MONTH_FORMATTER);
            return new DateRange(yearMonth.atDay(1), yearMonth.atEndOfMonth());
        }
        throw new ServiceLogicException(ErrorCode.BAD_REQUEST);
    }

    private Map<YearMonth, MonthBucket> initMonthBuckets(LocalDate startDate, int months) {
        Map<YearMonth, MonthBucket> result = new LinkedHashMap<>();
        YearMonth startMonth = YearMonth.from(startDate);
        for (int i = 0; i < months; i++) {
            YearMonth yearMonth = startMonth.plusMonths(i);
            result.put(yearMonth, new MonthBucket(yearMonth));
        }
        return result;
    }

    private LocalDate min(LocalDate first, LocalDate second) {
        return first.isBefore(second) ? first : second;
    }

    private String normalizePeriodType(String periodType) {
        if (periodType == null || periodType.isBlank()) {
            throw new ServiceLogicException(ErrorCode.BAD_REQUEST);
        }
        return periodType.trim().toUpperCase();
    }

    private LocalDate parseIsoWeek(String period) {
        if (period == null || !period.matches("\\d{4}-W\\d{2}")) {
            throw new ServiceLogicException(ErrorCode.BAD_REQUEST);
        }
        int year = Integer.parseInt(period.substring(0, 4));
        int week = Integer.parseInt(period.substring(6));
        return Year.of(year)
                .atDay(4)
                .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private String weekKey(LocalDate date) {
        return date.format(WEEK_FORMATTER);
    }

    private LocalDate startOfWeek(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private LocalDate today() {
        return LocalDate.now(SERVICE_ZONE);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(SERVICE_ZONE);
    }

    private void verifyAdmin(User user) {
        if (user == null || user.getAuthority() != Authority.ADMIN) {
            throw new ServiceLogicException(ErrorCode.ACCESS_DENIED);
        }
    }

    private record DateRange(LocalDate startDate, LocalDate endDate) {
    }

    private static class WeekBucket {
        private final LocalDate startDate;
        private final LocalDate endDate;
        private final Set<Long> userIds = new LinkedHashSet<>();

        private WeekBucket(LocalDate startDate, LocalDate endDate) {
            this.startDate = startDate;
            this.endDate = endDate;
        }

        private AccessAnalyticsBucketResponseDto toResponse() {
            return AccessAnalyticsBucketResponseDto.builder()
                    .period(startDate.format(WEEK_FORMATTER))
                    .label(startDate.format(DateTimeFormatter.ofPattern("MM.dd")))
                    .startDate(startDate.toString())
                    .endDate(endDate.toString())
                    .uniqueUserCount(userIds.size())
                    .build();
        }
    }

    private static class MonthBucket {
        private final YearMonth yearMonth;
        private final Set<Long> userIds = new LinkedHashSet<>();

        private MonthBucket(YearMonth yearMonth) {
            this.yearMonth = yearMonth;
        }

        private AccessAnalyticsBucketResponseDto toResponse() {
            return AccessAnalyticsBucketResponseDto.builder()
                    .period(yearMonth.format(MONTH_FORMATTER))
                    .label(yearMonth.format(DateTimeFormatter.ofPattern("yy.MM")))
                    .startDate(yearMonth.atDay(1).toString())
                    .endDate(yearMonth.atEndOfMonth().toString())
                    .uniqueUserCount(userIds.size())
                    .build();
        }
    }

    private static class UserAccessAccumulator {
        private final User user;
        private LocalDateTime firstAccessAt;
        private LocalDateTime lastAccessAt;
        private long accessCount;
        private final Set<LocalDate> accessDates = new LinkedHashSet<>();

        private UserAccessAccumulator(User user) {
            this.user = user;
        }

        private void add(UserAccessDaily access) {
            if (firstAccessAt == null || access.getFirstAccessAt().isBefore(firstAccessAt)) {
                firstAccessAt = access.getFirstAccessAt();
            }
            if (lastAccessAt == null || access.getLastAccessAt().isAfter(lastAccessAt)) {
                lastAccessAt = access.getLastAccessAt();
            }
            accessCount += access.getAccessCount() == null ? 0L : access.getAccessCount();
            accessDates.add(access.getAccessDate());
        }

        private LocalDateTime firstAccessAt() {
            return firstAccessAt;
        }

        private AccessAnalyticsUserResponseDto toResponse() {
            return AccessAnalyticsUserResponseDto.builder()
                    .userId(user.getUserId())
                    .username(user.getUsername())
                    .nickName(user.getNickName())
                    .email(user.getEmail())
                    .authority(user.getAuthority())
                    .firstAccessAt(firstAccessAt)
                    .lastAccessAt(lastAccessAt)
                    .accessCount(accessCount)
                    .accessDateCount(accessDates.size())
                    .build();
        }
    }
}
