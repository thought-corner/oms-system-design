package com.project.product.repository

import com.project.product.domain.OutboxMessage
import org.springframework.data.jpa.repository.JpaRepository

interface OutboxMessageRepository : JpaRepository<OutboxMessage, Long>
