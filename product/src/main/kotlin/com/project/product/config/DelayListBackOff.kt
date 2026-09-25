package com.project.product.config

import org.springframework.util.backoff.BackOff
import org.springframework.util.backoff.BackOffExecution

class DelayListBackOff(private val delays: List<Long>) : BackOff {

    override fun start(): BackOffExecution {
        val remaining = delays.iterator()
        return BackOffExecution { if (remaining.hasNext()) remaining.next() else BackOffExecution.STOP }
    }
}
