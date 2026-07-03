package com.strideai.model

import jakarta.persistence.*
import java.time.Instant
import java.time.LocalDateTime

@Entity
@Table(name = "users")
data class User(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(unique = true, nullable = false)
    val stravaId: Long,

    val firstName: String,
    val lastName: String,
    val profileImageUrl: String? = null,
    val city: String? = null,
    val country: String? = null,

    var accessToken: String,
    var refreshToken: String,
    var tokenExpiresAt: Long,

    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now()
)

@Entity
@Table(name = "activities")
data class Activity(
    @Id
    val stravaId: Long,

    val userId: Long,
    val name: String,
    val type: String,
    val sportType: String,

    val distanceMeters: Double,
    val movingTimeSecs: Int,
    val elapsedTimeSecs: Int,
    val totalElevationGain: Double,

    val averageSpeed: Double,
    val maxSpeed: Double,

    val averageHeartrate: Double? = null,
    val maxHeartrate: Double? = null,
    val averageWatts: Double? = null,
    val weightedAverageWatts: Int? = null,
    val averageCadence: Double? = null,
    val calories: Double? = null,

    val startDate: Instant,
    val startDateLocal: String,
    val timezone: String? = null,

    val kudosCount: Int = 0,
    val achievementCount: Int = 0,
    val prCount: Int = 0,

    val sufferScore: Int? = null,

    @Column(columnDefinition = "TEXT")
    val summaryPolyline: String? = null,

    val createdAt: Instant = Instant.now()
)

@Entity
@Table(name = "training_plans")
data class TrainingPlan(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    val userId: Long,
    val weekStartDate: String,

    @Column(columnDefinition = "TEXT")
    val planJson: String,

    val weekTss: Int? = null,
    val focus: String? = null,
    val goal: String? = null,

    val createdAt: Instant = Instant.now()
)

@Entity
@Table(name = "ai_analyses")
data class AiAnalysis(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    val userId: Long,

    @Column(columnDefinition = "TEXT")
    val content: String,

    val activitiesHash: String,

    val createdAt: LocalDateTime = LocalDateTime.now()
)
