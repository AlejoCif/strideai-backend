package com.strideai.model

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@Entity
@Table(name = "athlete_profile")
data class AthleteProfile(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(unique = true, nullable = false)
    val userId: Long,

    val weightKg: BigDecimal? = null,
    val mainSport: String? = null,
    val goalEvent: String? = null,
    val goalDate: LocalDate? = null,

    @Column(columnDefinition = "TEXT")
    val equipment: String? = null,

    @Column(columnDefinition = "TEXT")
    val notes: String? = null,

    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now()
)
