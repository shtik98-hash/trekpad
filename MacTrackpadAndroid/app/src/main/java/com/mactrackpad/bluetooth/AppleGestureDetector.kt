package com.mactrackpad.bluetooth

import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Повноцінний рушій мультитач-жестів у стилі Apple Magic Trackpad.
 * Розпізнає жести 1, 2, 3 та 4 пальцями з підтриманням інерції,
 * природного скролінгу (Natural Scrolling), щипка, обертання та крайових свайпів.
 */
class AppleGestureDetector(
    private val hidController: BluetoothHidController,
    private val onHapticFeedback: (Boolean) -> Unit
) {
    var pointerSensitivity: Float = 1.6f
    var scrollSensitivity: Float = 0.45f
    var naturalScrolling: Boolean = true
    var tapToClick: Boolean = true
    var threeFingerDragEnabled: Boolean = false // Якщо false — 3 пальці перемикають робочі столи та Mission Control

    private var maxPointersInGesture = 0
    private var downTime = 0L
    private var totalMovement = 0f

    private var lastX = 0f
    private var lastY = 0f
    private var startX = 0f
    private var startY = 0f
    private var viewWidth = 1080f

    // Для подвійного тапу 2 пальцями (Smart Zoom)
    private var lastTwoFingerTapTime = 0L

    // Для Pinch-to-Zoom та Обертання (2 або 4 пальці)
    private var initialSpan = 0f
    private var lastSpan = 0f
    private var lastAngle = 0f
    private var accumulatedRotation = 0f
    private var multiFingerActionTriggered = false

    // Для довгого натискання 1 пальцем (Force Touch / Look Up)
    private var forceClickTriggered = false

    fun setTrackpadWidth(width: Float) {
        if (width > 0) viewWidth = width
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val pointerCount = event.pointerCount

        if (pointerCount > maxPointersInGesture) {
            maxPointersInGesture = pointerCount
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                maxPointersInGesture = 1
                downTime = System.currentTimeMillis()
                totalMovement = 0f
                forceClickTriggered = false
                multiFingerActionTriggered = false
                accumulatedRotation = 0f

                startX = event.x
                startY = event.y
                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val centroid = getCentroid(event)
                lastX = centroid.first
                lastY = centroid.second
                startX = lastX
                startY = lastY

                if (pointerCount >= 2) {
                    initialSpan = getSpan(event)
                    lastSpan = initialSpan
                    lastAngle = getTwoFingerAngle(event)
                }

                if (pointerCount == 3 && threeFingerDragEnabled) {
                    hidController.setThreeFingerDragState(true)
                    onHapticFeedback(false)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val centroid = getCentroid(event)
                val dx = centroid.first - lastX
                val dy = centroid.second - lastY
                val stepDist = hypot(dx, dy)
                totalMovement += stepDist

                when (pointerCount) {
                    // ==================== 1 ПАЛЕЦЬ: РУХ КУРСОРА / FORCE TOUCH ====================
                    1 -> {
                        if (maxPointersInGesture == 1) {
                            val holdDuration = System.currentTimeMillis() - downTime
                            // Якщо тримаємо палець нерухомо > 550 мс — спрацьовує Force Click (Довідка / Quick Look)
                            if (holdDuration > 550 && totalMovement < 18f && !forceClickTriggered) {
                                forceClickTriggered = true
                                onHapticFeedback(true)
                                hidController.triggerLookUpOrQuickLook()
                            } else if (!forceClickTriggered) {
                                // Нелінійне прискорення курсора як у macOS
                                val velocityBoost = (stepDist * 0.12f).coerceIn(1.0f, 2.8f)
                                val sendDx = (dx * pointerSensitivity * velocityBoost).toInt()
                                val sendDy = (dy * pointerSensitivity * velocityBoost).toInt()
                                if (sendDx != 0 || sendDy != 0) {
                                    hidController.sendMouseReport(dx = sendDx, dy = sendDy)
                                }
                            }
                        }
                    }

                    // ==================== 2 ПАЛЬЦІ: СКРОЛ, ЩИПОК, ОБЕРТАННЯ, КРАЙОВИЙ СВАЙП ====================
                    2 -> {
                        if (maxPointersInGesture == 2) {
                            // 1. Перевірка свайпу від правого краю (Центр сповіщень macOS)
                            if (!multiFingerActionTriggered && startX > viewWidth * 0.88f && (centroid.first - startX) < -120f) {
                                multiFingerActionTriggered = true
                                onHapticFeedback(true)
                                hidController.triggerNotificationCenter()
                                return true
                            }

                            // 2. Перевірка обертання двома пальцями (Rotate)
                            val currentAngle = getTwoFingerAngle(event)
                            var angleDelta = currentAngle - lastAngle
                            if (angleDelta > 180f) angleDelta -= 360f
                            if (angleDelta < -180f) angleDelta += 360f
                            accumulatedRotation += angleDelta
                            lastAngle = currentAngle

                            if (abs(accumulatedRotation) > 28f) {
                                onHapticFeedback(false)
                                hidController.triggerRotate(clockwise = accumulatedRotation > 0)
                                accumulatedRotation = 0f
                                multiFingerActionTriggered = true
                            }

                            // 3. Перевірка щипка двома пальцями (Pinch to Zoom)
                            val currentSpan = getSpan(event)
                            val spanDelta = currentSpan - lastSpan
                            if (abs(spanDelta) > 48f && abs(currentSpan - initialSpan) > 70f) {
                                onHapticFeedback(false)
                                hidController.triggerPinchZoom(zoomIn = spanDelta > 0)
                                lastSpan = currentSpan
                                multiFingerActionTriggered = true
                            } else if (!multiFingerActionTriggered) {
                                // 4. Плавний двопальцевий скролінг (Вертикальний + Горизонтальний)
                                val direction = if (naturalScrolling) 1f else -1f
                                val scrollV = (dy * scrollSensitivity * direction).toInt()
                                val scrollH = (dx * scrollSensitivity * direction).toInt()
                                if (scrollV != 0 || scrollH != 0) {
                                    hidController.sendMouseReport(
                                        verticalScroll = scrollV,
                                        horizontalScroll = scrollH
                                    )
                                }
                            }
                        }
                    }

                    // ==================== 3 ПАЛЬЦІ: DRAG АБО РОБОЧІ СТОЛИ / MISSION CONTROL ====================
                    3 -> {
                        if (threeFingerDragEnabled) {
                            val sendDx = (dx * pointerSensitivity * 1.3f).toInt()
                            val sendDy = (dy * pointerSensitivity * 1.3f).toInt()
                            if (sendDx != 0 || sendDy != 0) {
                                hidController.sendMouseReport(leftButton = true, dx = sendDx, dy = sendDy)
                            }
                        } else if (!multiFingerActionTriggered) {
                            val totalDx = centroid.first - startX
                            val totalDy = centroid.second - startY
                            val swipeThreshold = 115f

                            if (abs(totalDx) > swipeThreshold && abs(totalDx) > abs(totalDy)) {
                                multiFingerActionTriggered = true
                                onHapticFeedback(true)
                                if (totalDx > 0) {
                                    hidController.triggerPreviousSpace()
                                } else {
                                    hidController.triggerNextSpace()
                                }
                            } else if (abs(totalDy) > swipeThreshold && abs(totalDy) > abs(totalDx)) {
                                multiFingerActionTriggered = true
                                onHapticFeedback(true)
                                if (totalDy < 0) {
                                    hidController.triggerMissionControl()
                                } else {
                                    hidController.triggerAppExpose()
                                }
                            }
                        }
                    }

                    // ==================== 4 ПАЛЬЦІ: MISSION CONTROL, SPACES, LAUNCHPAD, DESKTOP ====================
                    4 -> {
                        if (!multiFingerActionTriggered) {
                            val currentSpan = getSpan(event)
                            val spanDiff = currentSpan - initialSpan
                            val totalDx = centroid.first - startX
                            val totalDy = centroid.second - startY

                            // Перевірка щипка 4 пальцями (Launchpad / Показати Робочий стіл)
                            if (abs(spanDiff) > 95f) {
                                multiFingerActionTriggered = true
                                onHapticFeedback(true)
                                if (spanDiff < 0) {
                                    hidController.triggerLaunchpad()
                                } else {
                                    hidController.triggerShowDesktop()
                                }
                            } else if (abs(totalDx) > 110f && abs(totalDx) > abs(totalDy)) {
                                multiFingerActionTriggered = true
                                onHapticFeedback(true)
                                if (totalDx > 0) {
                                    hidController.triggerPreviousSpace()
                                } else {
                                    hidController.triggerNextSpace()
                                }
                            } else if (abs(totalDy) > 110f && abs(totalDy) > abs(totalDx)) {
                                multiFingerActionTriggered = true
                                onHapticFeedback(true)
                                if (totalDy < 0) {
                                    hidController.triggerMissionControl()
                                } else {
                                    hidController.triggerAppExpose()
                                }
                            }
                        }
                    }
                }

                lastX = centroid.first
                lastY = centroid.second
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (pointerCount - 1 < 3 && threeFingerDragEnabled) {
                    hidController.setThreeFingerDragState(false)
                }
                val centroid = getCentroid(event, ignoreIndex = event.actionIndex)
                lastX = centroid.first
                lastY = centroid.second
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (threeFingerDragEnabled) {
                    hidController.setThreeFingerDragState(false)
                }

                val duration = System.currentTimeMillis() - downTime
                if (tapToClick && duration < 240 && totalMovement < 24f && !forceClickTriggered && !multiFingerActionTriggered) {
                    when (maxPointersInGesture) {
                        1 -> {
                            onHapticFeedback(false)
                            hidController.performClick(left = true, label = "Лівий клік (Тап 1 пальцем)")
                        }
                        2 -> {
                            val now = System.currentTimeMillis()
                            if (now - lastTwoFingerTapTime < 320L) {
                                onHapticFeedback(true)
                                hidController.triggerSmartZoom()
                                lastTwoFingerTapTime = 0L
                            } else {
                                onHapticFeedback(false)
                                hidController.performClick(right = true, label = "Правий клік (Тап 2 пальцями)")
                                lastTwoFingerTapTime = now
                            }
                        }
                        3 -> {
                            onHapticFeedback(true)
                            hidController.triggerLookUpOrQuickLook()
                        }
                    }
                }
                maxPointersInGesture = 0
            }
        }
        return true
    }

    private fun getCentroid(event: MotionEvent, ignoreIndex: Int = -1): Pair<Float, Float> {
        var sumX = 0f
        var sumY = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == ignoreIndex) continue
            sumX += event.getX(i)
            sumY += event.getY(i)
            count++
        }
        return if (count > 0) Pair(sumX / count, sumY / count) else Pair(0f, 0f)
    }

    private fun getSpan(event: MotionEvent): Float {
        val (cx, cy) = getCentroid(event)
        var sumDist = 0f
        for (i in 0 until event.pointerCount) {
            sumDist += hypot(event.getX(i) - cx, event.getY(i) - cy)
        }
        return if (event.pointerCount > 0) sumDist / event.pointerCount else 0f
    }

    private fun getTwoFingerAngle(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }
}
