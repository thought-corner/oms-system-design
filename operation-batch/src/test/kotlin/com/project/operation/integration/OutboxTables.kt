package com.project.operation.integration

import com.project.operation.domain.OutboxMessage
import com.project.operation.domain.OutboxSource
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.mysql.MySQLContainer
import java.time.LocalDateTime
import java.util.UUID

class OutboxTables(mysql: MySQLContainer) {

    private val root = JdbcTemplate(DriverManagerDataSource(mysql.jdbcUrl, "root", IntegrationTestConfig.PASSWORD))

    fun insert(
        source: OutboxSource,
        topic: String,
        sagaId: String,
        occurredAt: LocalDateTime? = null,
        status: String = "PENDING",
        failCount: Int = 0,
        held: Boolean = false,
    ): String {
        val messageId = UUID.randomUUID().toString()
        val occurredAtValue = if (occurredAt == null) "NOW(6)" else "?"
        val claimedAtValue = if (held) "NOW(6) + INTERVAL 1 DAY" else "NULL"
        root.update(
            "INSERT INTO ${source.table} (message_id, topic, message_key, saga_id, message_type, payload, status, fail_count, occurred_at, claimed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, $occurredAtValue, $claimedAtValue)",
            *listOfNotNull(messageId, topic, MESSAGE_KEY, sagaId, MESSAGE_TYPE, payloadOf(sagaId), status, failCount, occurredAt).toTypedArray(),
        )
        return messageId
    }

    fun insertWaitingAtMost(source: OutboxSource, topic: String, sagaId: String, lockWaitSeconds: Int) {
        root.execute(
            ConnectionCallback { connection ->
                connection.createStatement().use { it.execute("SET SESSION innodb_lock_wait_timeout = $lockWaitSeconds") }
                connection.prepareStatement(
                    "INSERT INTO ${source.table} (message_id, topic, message_key, saga_id, message_type, payload, status, fail_count, occurred_at) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, NOW(6))",
                ).use { statement ->
                    statement.setString(1, UUID.randomUUID().toString())
                    statement.setString(2, topic)
                    statement.setString(3, MESSAGE_KEY)
                    statement.setString(4, sagaId)
                    statement.setString(5, MESSAGE_TYPE)
                    statement.setBytes(6, payloadOf(sagaId))
                    statement.executeUpdate()
                }
            },
        )
    }

    fun release(source: OutboxSource, sagaIds: Collection<String>) {
        root.update(
            "UPDATE ${source.table} SET claimed_at = NULL WHERE saga_id IN (${placeholders(sagaIds)})",
            *sagaIds.toTypedArray(),
        )
    }

    fun claimedSecondsAgo(source: OutboxSource, sagaId: String, seconds: Long) {
        root.update("UPDATE ${source.table} SET claimed_at = NOW(6) - INTERVAL ? SECOND WHERE saga_id = ?", seconds, sagaId)
    }

    fun publishedDaysAgo(source: OutboxSource, sagaId: String, days: Long) {
        root.update("UPDATE ${source.table} SET published_at = NOW(6) - INTERVAL ? DAY WHERE saga_id = ?", days, sagaId)
    }

    fun failedDaysAgo(source: OutboxSource, sagaId: String, days: Long) {
        root.update("UPDATE ${source.table} SET failed_at = NOW(6) - INTERVAL ? DAY WHERE saga_id = ?", days, sagaId)
    }

    fun delete(source: OutboxSource, sagaIds: Collection<String>) {
        root.update(
            "DELETE FROM ${source.table} WHERE saga_id IN (${placeholders(sagaIds)})",
            *sagaIds.toTypedArray(),
        )
    }

    fun exists(source: OutboxSource, sagaId: String): Boolean =
        root.queryForList("SELECT id FROM ${source.table} WHERE saga_id = ?", sagaId).isNotEmpty()

    fun row(source: OutboxSource, sagaId: String): Map<String, Any?> =
        root.queryForMap("SELECT * FROM ${source.table} WHERE saga_id = ?", sagaId)

    fun message(source: OutboxSource, sagaId: String): OutboxMessage =
        root.query(
            "SELECT id, message_id, topic, message_key, saga_id, message_type, payload FROM ${source.table} WHERE saga_id = ?",
            { rs, _ ->
                OutboxMessage(
                    id = rs.getLong("id"),
                    messageId = rs.getString("message_id"),
                    topic = rs.getString("topic"),
                    messageKey = rs.getString("message_key"),
                    sagaId = rs.getString("saga_id"),
                    messageType = rs.getString("message_type"),
                    payload = rs.getBytes("payload"),
                )
            },
            sagaId,
        ).single()

    fun publishedAt(source: OutboxSource, sagaId: String): LocalDateTime? =
        root.queryForObject("SELECT published_at FROM ${source.table} WHERE saga_id = ?", LocalDateTime::class.java, sagaId)

    fun claimedAt(source: OutboxSource, sagaId: String): LocalDateTime? =
        root.queryForObject("SELECT claimed_at FROM ${source.table} WHERE saga_id = ?", LocalDateTime::class.java, sagaId)

    private fun placeholders(values: Collection<String>): String = values.joinToString { "?" }

    companion object {
        private const val MESSAGE_KEY = "10"
        private const val MESSAGE_TYPE = "STOCK_BUY"

        fun payloadOf(sagaId: String): ByteArray =
            byteArrayOf(0x0A, sagaId.length.toByte()) + sagaId.toByteArray(Charsets.UTF_8)
    }
}
