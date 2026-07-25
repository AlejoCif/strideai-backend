package com.strideai.repository

import com.strideai.model.ActivityZones
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ActivityZonesRepository : JpaRepository<ActivityZones, Long> {
    fun findByUserIdOrderByCreatedAtDesc(userId: Long): List<ActivityZones>
}
