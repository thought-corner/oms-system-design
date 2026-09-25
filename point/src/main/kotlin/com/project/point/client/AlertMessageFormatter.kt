package com.project.point.client

object AlertMessageFormatter {

    private const val FORWARD_TYPE = "POINT_USE"
    private const val CANCEL_TYPE = "POINT_CANCEL"
    private const val FORWARD_WORK = "포인트 사용"
    private const val FORWARD_WORK_SUBJECT = "포인트 사용이"
    private const val CANCEL_WORK = "포인트 환불"
    private const val CANCEL_WORK_SUBJECT = "포인트 환불이"
    private const val FORWARD_UNTOUCHED = "포인트는 아직 차감되지 않았습니다"
    private const val CANCEL_LEFTOVER = "포인트가 차감된 채 남아 있을 수 있습니다"
    private const val CANCEL_RESTORED = "포인트는 자동으로 환불되므로"
    private const val ASSET_OBJECT = "포인트 잔액을"
    const val EXCEPTION_MESSAGE_LIMIT = 500

    private enum class Work { FORWARD, CANCEL, UNKNOWN }

    fun format(alert: DeadLetterAlert): String {
        val order = "주문 ${alert.orderId ?: "(번호 없음)"}"
        val work = workOf(alert.messageType)
        val summary = when (alert.kind) {
            DeadLetterKind.RETRY_EXHAUSTED -> retryExhausted(order, work)
            DeadLetterKind.POISON -> if (alert.failedReplyWritten) poisonReplied(order, work) else poisonUnreplied(order, work)
        }
        return summary.joinToString("\n") + "\n\n" + details(alert)
    }

    private fun retryExhausted(order: String, work: Work): List<String> =
        when (work) {
            Work.FORWARD -> listOf(
                "🟠 [장애] ${order}의 $FORWARD_WORK_SUBJECT 시스템 장애로 멈췄습니다",
                "상황: 여러 차례 자동으로 다시 시도했지만 모두 실패했습니다. 고객 주문은 '처리 중'으로 머물러 있고, $FORWARD_UNTOUCHED.",
                "조치: 개발팀에 장애 복구를 요청해 주세요. 복구되면 주문은 자동으로 이어서 처리되므로 주문을 따로 손대지 않아도 됩니다.",
            )
            Work.CANCEL -> listOf(
                "🟠 [장애] ${order}의 $CANCEL_WORK_SUBJECT 시스템 장애로 멈췄습니다",
                "상황: 주문 처리에 실패해 되돌리는 중인 주문입니다. 여러 차례 자동으로 다시 시도했지만 모두 실패해 $CANCEL_LEFTOVER.",
                "조치: 개발팀에 장애 복구를 요청해 주세요. 복구되면 $CANCEL_RESTORED 따로 손대지 않아도 됩니다.",
            )
            Work.UNKNOWN -> listOf(
                "🟠 [장애] ${order}의 처리가 시스템 장애로 멈췄습니다",
                "상황: 여러 차례 자동으로 다시 시도했지만 모두 실패했습니다.",
                "조치: 개발팀에 장애 복구를 요청해 주세요.",
            )
        }

    private fun poisonUnreplied(order: String, work: Work): List<String> =
        when (work) {
            Work.FORWARD -> listOf(
                "🔴 [즉시 확인] ${order}의 $FORWARD_WORK 요청을 처리할 수 없어 멈췄습니다",
                "상황: 요청 내용이 잘못되어 다시 시도해도 처리할 수 없고, 주문을 실패로 돌리는 자동 취소도 시작되지 못했습니다. 고객 주문이 '처리 중'에서 멈춰 있을 수 있습니다.",
                "조치: 어드민 수동 처리가 필요합니다. 주문 상태와 $ASSET_OBJECT 직접 확인해 주문을 정리하고, 개발팀에 결함 수정을 요청해 주세요.",
            )
            Work.CANCEL -> listOf(
                "🔴 [즉시 확인] ${order}의 $CANCEL_WORK 요청을 처리할 수 없어 멈췄습니다",
                "상황: 주문 처리에 실패해 되돌리는 중이었지만 요청 내용이 잘못되어 다시 시도해도 처리할 수 없습니다. $CANCEL_LEFTOVER.",
                "조치: 어드민 수동 처리가 필요합니다. $ASSET_OBJECT 직접 확인해 수동으로 되돌리고, 개발팀에 결함 수정을 요청해 주세요.",
            )
            Work.UNKNOWN -> listOf(
                "🔴 [즉시 확인] ${order}의 요청을 처리할 수 없어 멈췄습니다",
                "상황: 어떤 처리인지 알 수 없는 요청이라 자동으로 되돌릴 수 없습니다.",
                "조치: 어드민 수동 처리가 필요합니다. 주문 상태와 $ASSET_OBJECT 직접 확인하고, 개발팀에 결함 수정을 요청해 주세요.",
            )
        }

    private fun poisonReplied(order: String, work: Work): List<String> =
        listOf(
            "🟡 [결함] ${order}의 ${if (work == Work.FORWARD) "$FORWARD_WORK 요청" else "요청"}이 잘못되어 주문을 실패 처리했습니다",
            "상황: 요청 내용이 잘못되어 처리할 수 없었습니다. 주문은 자동으로 실패 처리되고, 앞 단계에서 이미 처리된 것은 자동으로 되돌려집니다. 고객에게는 주문 실패로 보입니다.",
            "조치: 따로 되돌릴 것은 없습니다. 개발팀에 결함 수정을 요청해 주세요.",
        )

    private fun workOf(messageType: String?): Work =
        when (messageType) {
            FORWARD_TYPE -> Work.FORWARD
            CANCEL_TYPE -> Work.CANCEL
            else -> Work.UNKNOWN
        }

    private fun details(alert: DeadLetterAlert): String =
        listOf(
            "[개발자용 로그]",
            "```",
            "kind=${alert.kind}",
            "failedReplyWritten=${alert.failedReplyWritten}",
            "topic=${alert.topic}",
            "orderId=${alert.orderId}",
            "sagaId=${alert.sagaId}",
            "messageType=${alert.messageType}",
            "exception=${alert.exceptionClass}",
            "message=${alert.exceptionMessage?.let(::shorten)}",
            "```",
        ).joinToString("\n")

    private fun shorten(message: String): String =
        if (message.length <= EXCEPTION_MESSAGE_LIMIT) message else message.take(EXCEPTION_MESSAGE_LIMIT) + "…"
}
