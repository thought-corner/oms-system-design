package com.project.operation.repository

import com.project.operation.domain.OutboxDelay
import com.project.operation.domain.OutboxFailure
import com.project.operation.domain.OutboxMessage
import com.project.operation.domain.OutboxSource
import com.project.operation.domain.OutboxStatus
import com.project.operation.domain.PublishFailure
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
                payload = rs.getBytes("payload"),
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

    fun countFailures(source: OutboxSource, failures: List<PublishFailure>, maxAttempts: Int): IntArray =
        jdbc.batchUpdate(
            """
            UPDATE ${source.table}
            SET status = CASE WHEN fail_count + 1 >= :maxAttempts THEN :failed ELSE :pending END,
                fail_count = fail_count + 1,
                failed_at = NOW(6),
                last_error = :lastError
            WHERE id = :id AND status = :pending
            """.trimIndent(),
            failures.map { failure ->
                MapSqlParameterSource()
                    .addValue("id", failure.message.id)
                    .addValue("lastError", failure.error)
                    .addValue("maxAttempts", maxAttempts)
                    .addValue("failed", OutboxStatus.FAILED.name)
                    .addValue("pending", OutboxStatus.PENDING.name)
            }.toTypedArray(),
        )

    fun findFailed(source: OutboxSource, messages: Collection<OutboxMessage>): List<OutboxFailure> {
        val messagesById = messages.associateBy { it.id }
        return jdbc.query(
            "SELECT id, fail_count, last_error FROM ${source.table} WHERE id IN (:ids) AND status = :failed ORDER BY id",
            mapOf("ids" to messagesById.keys, "failed" to OutboxStatus.FAILED.name),
        ) { rs, _ ->
            OutboxFailure(
                message = messagesById.getValue(rs.getLong("id")),
                failCount = rs.getInt("fail_count"),
                lastError = rs.getString("last_error"),
            )
        }
    }

    fun deletePublishedBefore(source: OutboxSource, retentionDays: Long, limit: Int): Int {
        val expiredIds = jdbc.queryForList(
            """
            SELECT id FROM ${source.table}
            WHERE status = :published AND published_at < NOW(6) - INTERVAL :retentionDays DAY
            ORDER BY id
            LIMIT :limit
            """.trimIndent(),
            mapOf("published" to OutboxStatus.PUBLISHED.name, "retentionDays" to retentionDays, "limit" to limit),
            Long::class.java,
        )
        if (expiredIds.isEmpty()) {
            return 0
        }
        return jdbc.update(
            "DELETE FROM ${source.table} WHERE id IN (:ids) AND status = :published",
            mapOf("ids" to expiredIds, "published" to OutboxStatus.PUBLISHED.name),
        )
    }

    fun findDelay(source: OutboxSource): OutboxDelay =
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
            OutboxDelay(
                pending = rs.getLong("pending"),
                oldestPendingOccurredAt = rs.getObject("oldest_pending", LocalDateTime::class.java),
                failed = rs.getLong("failed"),
            )
        }.single()
}
