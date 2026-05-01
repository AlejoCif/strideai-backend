package com.strideai.repository

import com.strideai.model.Activity
import com.strideai.model.TrainingPlan
import com.strideai.model.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
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
}

@Repository
interface TrainingPlanRepository : JpaRepository<TrainingPlan, Long> {
    fun findByUserIdOrderByCreatedAtDesc(userId: Long): List<TrainingPlan>
    fun findFirstByUserIdOrderByCreatedAtDesc(userId: Long): TrainingPlan?
}
