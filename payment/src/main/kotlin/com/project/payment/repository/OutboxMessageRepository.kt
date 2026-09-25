package com.project.payment.repository

import com.project.payment.domain.OutboxMessage
import org.springframework.data.jpa.repository.JpaRepository

interface OutboxMessageRepository : JpaRepository<OutboxMessage, Long>
