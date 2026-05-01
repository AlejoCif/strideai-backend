package com.strideai.controller

import com.strideai.dto.ActivitySummary
import com.strideai.dto.StravaActivityDto
import com.strideai.repository.ActivityRepository
import com.strideai.service.StravaService
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(ActivitiesController::class)
class ActivitiesControllerTest(@Autowired val mockMvc: MockMvc) {

    @MockBean
    private lateinit var stravaService: StravaService

    @MockBean
    private lateinit var activityRepository: ActivityRepository

    @Test
    @WithMockUser
    fun `GET activities returns list with correct structure`() {
        val stravaActivity = stravaActivityDto()
        val summary = activitySummary()

        given(stravaService.getRecentActivities(perPage = 20, page = 1))
            .willReturn(listOf(stravaActivity))
        given(stravaService.toActivitySummary(stravaActivity))
            .willReturn(summary)

        mockMvc.perform(get("/api/activities"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
            .andExpect(jsonPath("$[0].stravaId").value(123L))
            .andExpect(jsonPath("$[0].name").value("Morning Run"))
            .andExpect(jsonPath("$[0].type").value("Run"))
            .andExpect(jsonPath("$[0].distanceKm").value(10.0))
    }

    @Test
    @WithMockUser
    fun `GET activities respects limit and page params`() {
        given(stravaService.getRecentActivities(perPage = 10, page = 2))
            .willReturn(emptyList())

        mockMvc.perform(get("/api/activities?limit=10&page=2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isArray)
            .andExpect(jsonPath("$").isEmpty)
    }

    @Test
    fun `GET activities without auth redirects to OAuth2 login`() {
        // OAuth2-secured apps redirect unauthenticated requests to the provider (302), not 401
        mockMvc.perform(get("/api/activities"))
            .andExpect(status().is3xxRedirection)
    }

    // ── Helpers ───────────────────────────────────────────────

    private fun stravaActivityDto() = StravaActivityDto(
        id = 123L,
        name = "Morning Run",
        type = "Run",
        sport_type = "Run",
        distance = 10000.0,
        moving_time = 3600,
        elapsed_time = 3700,
        total_elevation_gain = 100.0,
        average_speed = 2.78,
        max_speed = 4.0,
        average_heartrate = 145.0,
        max_heartrate = 165.0,
        average_watts = null,
        weighted_average_watts = null,
        start_date = "2024-01-15T08:00:00Z",
        start_date_local = "2024-01-15T09:00:00",
        timezone = "Europe/Madrid",
        kudos_count = 5,
        achievement_count = 1,
        pr_count = 0,
        suffer_score = 65
    )

    private fun activitySummary() = ActivitySummary(
        stravaId = 123L,
        name = "Morning Run",
        type = "Run",
        date = "2024-01-15",
        distanceKm = 10.0,
        movingTimeFormatted = "1h 0m",
        elevationGain = 100.0,
        avgHeartrate = 145.0,
        avgWatts = null,
        tss = 65
    )
}
