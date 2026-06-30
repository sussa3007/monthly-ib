package com.monthlyib.server.domain.accessanalytics.repository;

import com.monthlyib.server.constant.Authority;
import com.monthlyib.server.domain.accessanalytics.entity.UserAccessDaily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface UserAccessDailyJpaRepository extends JpaRepository<UserAccessDaily, Long> {

    @Modifying
    @Transactional
    @Query(value = """
            insert ignore into user_access_daily
                (access_date, user_id, first_access_at, last_access_at, access_count)
            values
                (:accessDate, :userId, :accessAt, :accessAt, 1)
            """, nativeQuery = true)
    void upsertAccess(
            @Param("accessDate") LocalDate accessDate,
            @Param("userId") Long userId,
            @Param("accessAt") LocalDateTime accessAt
    );

    @Query("""
            select daily
            from UserAccessDaily daily
            join fetch daily.user user
            where daily.accessDate between :startDate and :endDate
              and user.authority = :authority
              and user.mergedIntoUserId is null
            order by daily.accessDate asc, daily.lastAccessAt desc
            """)
    List<UserAccessDaily> findUserAccesses(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("authority") Authority authority
    );
}
