package com.project.product.domain

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "saga_guards")
class SagaGuard(
    @Id
    val sagaId: String,
    @Enumerated(EnumType.STRING)
    val kind: SagaGuardKind,
    val createdAt: LocalDateTime,
)
