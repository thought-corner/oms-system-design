package com.project.payment.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

@Entity
@Table(
    name = "outbox",
    uniqueConstraints = [UniqueConstraint(name = "uk_outbox_message_id", columnNames = ["message_id"])],
    indexes = [Index(name = "idx_outbox_status_id", columnList = "status, id")],
)
class OutboxMessage(
    @Column(name = "message_id", nullable = false, length = 36)
    val messageId: String,
    @Column(name = "topic", nullable = false, length = 100)
    val topic: String,
    @Column(name = "message_key", nullable = false, length = 50)
    val messageKey: String,
    @Column(name = "saga_id", nullable = false, length = 36)
    val sagaId: String,
    @Column(name = "message_type", nullable = false, length = 50)
    val messageType: String,
    @Column(name = "payload", nullable = false, columnDefinition = "BLOB")
    val payload: ByteArray,
    @Column(name = "occurred_at", nullable = false)
    val occurredAt: LocalDateTime,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, updatable = false, columnDefinition = "VARCHAR(20)")
    var status: OutboxStatus = OutboxStatus.PENDING
        protected set

    @Column(name = "fail_count", nullable = false, updatable = false, columnDefinition = "INT DEFAULT 0")
    var failCount: Int = 0
        protected set

    @Column(name = "claimed_at", insertable = false, updatable = false)
    var claimedAt: LocalDateTime? = null
        protected set

    @Column(name = "published_at", insertable = false, updatable = false)
    var publishedAt: LocalDateTime? = null
        protected set

    @Column(name = "failed_at", insertable = false, updatable = false)
    var failedAt: LocalDateTime? = null
        protected set

    @Column(name = "last_error", length = 255, insertable = false, updatable = false)
    var lastError: String? = null
        protected set
}
