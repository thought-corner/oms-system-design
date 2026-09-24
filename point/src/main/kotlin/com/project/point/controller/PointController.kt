package com.project.point.controller

import com.project.point.controller.dto.UseCancelRequest
import com.project.point.controller.dto.UseCancelResponse
import com.project.point.controller.dto.UseRequest
import com.project.point.service.PointService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class PointController(
    private val pointService: PointService,
) {

    @PostMapping("/point/use")
    fun use(@RequestBody request: UseRequest) {
        pointService.use(request.toCommand())
    }

    @PostMapping("/point/use/cancel")
    fun cancel(@RequestBody request: UseCancelRequest): UseCancelResponse =
        UseCancelResponse(pointService.cancel(request.toCommand()))
}
