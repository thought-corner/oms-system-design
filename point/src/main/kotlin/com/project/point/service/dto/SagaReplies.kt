package com.project.point.service.dto

enum class PointMessageType(val direction: ReplyDirection) {
    POINT_USE(ReplyDirection.FORWARD),
    POINT_CANCEL(ReplyDirection.CANCEL),
}

enum class ReplyDirection {
    FORWARD, CANCEL
}
