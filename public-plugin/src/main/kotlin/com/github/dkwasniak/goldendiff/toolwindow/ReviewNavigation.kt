package com.github.dkwasniak.goldendiff.toolwindow

/**
 * Next/previous View for the Review tab's ‹ › buttons. Only [queue] (changed Views, in Files order)
 * is navigated, one View at a time, wrapping at both ends. A View outside the queue (an unchanged file
 * opened by hand) steps to the nearest queued View before/after it in [allOrder].
 */
internal fun steppedQueueViewId(allOrder: List<String>, queue: List<String>, current: String?, delta: Int): String? {
    if (queue.isEmpty()) return null
    val index = queue.indexOf(current)
    if (index >= 0) return queue[Math.floorMod(index + delta, queue.size)]
    val position = allOrder.indexOf(current)
    if (position < 0) return if (delta >= 0) queue.first() else queue.last()
    return if (delta >= 0) {
        queue.firstOrNull { allOrder.indexOf(it) > position } ?: queue.first()
    } else {
        queue.lastOrNull { allOrder.indexOf(it) in 0 until position } ?: queue.last()
    }
}

/** One-based "3 / 12" position inside the queue; null when the View is not queued. */
internal fun queuePositionLabel(queue: List<String>, current: String?): String? {
    val index = queue.indexOf(current)
    return if (index < 0) null else "${index + 1} / ${queue.size}"
}
