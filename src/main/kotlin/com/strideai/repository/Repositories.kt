package com.strideai.repository

import com.strideai.model.Activity
import com.strideai.model.AiAnalysis
import com.strideai.model.AthleteProfile
import com.strideai.model.TrainingPlan
import com.strideai.model.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface UserRepository : JpaRepository<User, Long> {
    fun findByStravaId(stravaId: Long): User?
}

@Repository
interface ActivityRepository : JpaRepository<Activity, Long> {
    fun findByUserIdOrderByStartDateDesc(userId: Long): List<Activity>
    fun findByUserIdAndStartDateAfterOrderByStartDateDesc(userId: Long, after: Instant): List<Activity>

    @Query("SELECT a FROM Activity a WHERE a.userId = :userId ORDER BY a.startDate DESC LIMIT :limit")
    fun findRecentByUserId(userId: Long, limit: Int): List<Activity>

    fun findByStravaId(stravaId: Long): Activity?

    fun findByUserIdAndNameContainingIgnoreCase(userId: Long, name: String): List<Activity>

    @Query(
        value = "SELECT * FROM activities WHERE user_id = :userId AND EXTRACT(MONTH FROM start_date) = :month ORDER BY start_date DESC",
        nativeQuery = true
    )
    fun findByUserIdAndMonth(@Param("userId") userId: Long, @Param("month") month: Int): List<Activity>

    @Query("SELECT a FROM Activity a WHERE a.userId = :userId AND a.startDate >= :from AND a.startDate < :to ORDER BY a.startDate DESC")
    fun findByUserIdAndDateRange(
        @Param("userId") userId: Long,
        @Param("from") from: Instant,
        @Param("to") to: Instant
    ): List<Activity>

    @Query("SELECT MIN(a.startDate) FROM Activity a WHERE a.userId = :userId")
    fun findMinStartDate(@Param("userId") userId: Long): Instant?

    @Query("SELECT MAX(a.startDate) FROM Activity a WHERE a.userId = :userId")
    fun findMaxStartDate(@Param("userId") userId: Long): Instant?

    fun countByUserId(userId: Long): Long
}

@Repository
interface TrainingPlanRepository : JpaRepository<TrainingPlan, Long> {
    fun findByUserIdOrderByCreatedAtDesc(userId: Long): List<TrainingPlan>
    fun findFirstByUserIdOrderByCreatedAtDesc(userId: Long): TrainingPlan?
}

@Repository
interface AiAnalysisRepository : JpaRepository<AiAnalysis, Long> {
    fun findFirstByUserIdOrderByCreatedAtDesc(userId: Long): AiAnalysis?
}

@Repository
interface AthleteProfileRepository : JpaRepository<AthleteProfile, Long> {
    fun findByUserId(userId: Long): AthleteProfile?
}
