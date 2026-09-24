package com.project.operation.repository

import com.project.operation.domain.OutboxBacklog
import com.project.operation.domain.OutboxFailure
import com.project.operation.domain.OutboxMessage
import com.project.operation.domain.OutboxSource
import com.project.operation.domain.OutboxStatus
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class OutboxRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {

    fun findClaimable(source: OutboxSource, limit: Int, leaseSeconds: Long): List<OutboxMessage> =
        jdbc.query(
            """
            SELECT id, message_id, topic, message_key, saga_id, message_type, payload
            FROM ${source.table}
            WHERE status = :pending
              AND (claimed_at IS NULL OR claimed_at < NOW(6) - INTERVAL :leaseSeconds SECOND)
            ORDER BY id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """.trimIndent(),
            mapOf("pending" to OutboxStatus.PENDING.name, "leaseSeconds" to leaseSeconds, "limit" to limit),
        ) { rs, _ ->
            OutboxMessage(
                id = rs.getLong("id"),
                messageId = rs.getString("message_id"),
                topic = rs.getString("topic"),
                messageKey = rs.getString("message_key"),
                sagaId = rs.getString("saga_id"),
                messageType = rs.getString("message_type"),
                payload = rs.getString("payload"),
            )
        }

    fun markClaimed(source: OutboxSource, ids: Collection<Long>): Int =
        jdbc.update(
            "UPDATE ${source.table} SET claimed_at = NOW(6) WHERE id IN (:ids)",
            mapOf("ids" to ids),
        )

    fun markPublished(source: OutboxSource, ids: Collection<Long>): Int =
        jdbc.update(
            "UPDATE ${source.table} SET status = :published, published_at = NOW(6) WHERE id IN (:ids) AND status = :pending",
            mapOf("ids" to ids, "published" to OutboxStatus.PUBLISHED.name, "pending" to OutboxStatus.PENDING.name),
        )

    fun findPendingFailCountsForUpdate(source: OutboxSource, ids: Collection<Long>): Map<Long, Int> =
        jdbc.query(
            "SELECT id, fail_count FROM ${source.table} WHERE id IN (:ids) AND status = :pending FOR UPDATE",
            mapOf("ids" to ids, "pending" to OutboxStatus.PENDING.name),
        ) { rs, _ -> rs.getLong("id") to rs.getInt("fail_count") }.toMap()

    fun recordFailures(source: OutboxSource, failures: List<OutboxFailure>): IntArray =
        jdbc.batchUpdate(
            """
            UPDATE ${source.table}
            SET fail_count = :failCount, failed_at = NOW(6), last_error = :lastError, status = :status
            WHERE id = :id AND status = :pending
            """.trimIndent(),
            failures.map { failure ->
                MapSqlParameterSource()
                    .addValue("id", failure.message.id)
                    .addValue("failCount", failure.failCount)
                    .addValue("lastError", failure.lastError)
                    .addValue("status", failure.status.name)
                    .addValue("pending", OutboxStatus.PENDING.name)
            }.toTypedArray(),
        )

    fun deletePublishedBefore(source: OutboxSource, retentionDays: Long, limit: Int): Int =
        jdbc.update(
            """
            DELETE FROM ${source.table}
            WHERE status = :published AND published_at < NOW(6) - INTERVAL :retentionDays DAY
            ORDER BY id
            LIMIT :limit
            """.trimIndent(),
            mapOf("published" to OutboxStatus.PUBLISHED.name, "retentionDays" to retentionDays, "limit" to limit),
        )

    fun findBacklog(source: OutboxSource): OutboxBacklog =
        jdbc.query(
            """
            SELECT COALESCE(SUM(status = :pending), 0) AS pending,
                   MIN(CASE WHEN status = :pending THEN occurred_at END) AS oldest_pending,
                   COALESCE(SUM(status = :failed), 0) AS failed
            FROM ${source.table}
            WHERE status IN (:pending, :failed)
            """.trimIndent(),
            mapOf("pending" to OutboxStatus.PENDING.name, "failed" to OutboxStatus.FAILED.name),
        ) { rs, _ ->
            OutboxBacklog(
                pending = rs.getLong("pending"),
                oldestPendingOccurredAt = rs.getObject("oldest_pending", LocalDateTime::class.java),
                failed = rs.getLong("failed"),
            )
        }.single()
}
