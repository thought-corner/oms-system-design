package com.project.point.repository

import com.project.point.domain.OutboxMessage
import org.springframework.data.jpa.repository.JpaRepository

interface OutboxMessageRepository : JpaRepository<OutboxMessage, Long>
