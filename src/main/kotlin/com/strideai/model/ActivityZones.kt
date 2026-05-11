package com.strideai.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "activity_zones")
data class ActivityZones(
    @Id val stravaActivityId: Long,
    val userId: Long,
    @Column(columnDefinition = "TEXT")
    val zonesJson: String,
    val createdAt: Instant = Instant.now()
)
