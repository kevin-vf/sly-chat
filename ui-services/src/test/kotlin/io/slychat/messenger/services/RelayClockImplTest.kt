package io.slychat.messenger.services

import io.slychat.messenger.core.currentTimestamp
import io.slychat.messenger.testutils.withTimeAs
import org.junit.Test
import rx.subjects.PublishSubject
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RelayClockImplTest {
    val clockDifference: PublishSubject<Long> = PublishSubject.create()

    fun withinThreshold(body: (RelayClockImpl, Long) -> Unit) {
        val threshold = 100L

        val relayClock = RelayClockImpl(
            clockDifference,
            threshold
        )

        for (diff in -threshold..threshold) {
            clockDifference.onNext(diff)
            body(relayClock, diff)
        }
    }

    fun withinThreshold(onNext: (Long) -> Unit) {
        val threshold = 100L

        val relayClock = RelayClockImpl(
            clockDifference,
            threshold
        )

        var wasCalled = false
        relayClock.clockDiffUpdates.subscribe {
            wasCalled = true
            onNext(it)
        }

        for (diff in -threshold..threshold) {
            clockDifference.onNext(diff)
        }

        assertTrue(wasCalled, "No event emitted")
    }

    @Test
    fun `it should ignore differences equal or below the given threshold`() {
        val millis = currentTimestamp()

        withinThreshold { relayClock, diff ->
            withTimeAs(millis) {
                assertEquals(millis, relayClock.currentTime(), "Difference should not be applied when value is $diff")
            }
        }
    }

    @Test
    fun `it should reset the difference if it falls below the threshold`() {
        val threshold = 100L

        val relayClock = RelayClockImpl(
            clockDifference,
            threshold
        )

        clockDifference.onNext(threshold + 1)
        clockDifference.onNext(threshold)

        val millis = currentTimestamp()
        withTimeAs(millis) {
            assertEquals(millis, relayClock.currentTime(), "Invalid time returned; diff was not reset")
        }
    }

    //if somehow the diff dropped due to external system clock changes, etc, so we wanna make sure to notify the ui
    @Test
    fun `it should emit a 0 update when the difference is equal or below the given threshold`() {
        withinThreshold { diff ->
            assertEquals(0, diff, "Diff should be 0 when below threshold")
        }
    }

    @Test
    fun `it should apply the diff when the value is above the given threshold`() {
        val threshold = 100L

        val relayClock = RelayClockImpl(
            clockDifference,
            threshold
        )

        val diff = threshold + 1
        clockDifference.onNext(diff)

        val millis = currentTimestamp()

        withTimeAs(millis) {
            assertEquals(millis + diff, relayClock.currentTime(), "Difference not applied")
        }
    }

    @Test
    fun `it should emit a diff event when the value is above the given threshold`() {
        val threshold = 100L

        val relayClock = RelayClockImpl(
            clockDifference,
            threshold
        )

        val diff = threshold + 1

        var wasCalled = false
        relayClock.clockDiffUpdates.subscribe {
            wasCalled = true

            assertEquals(diff, it, "Invalid diff in update")
        }

        clockDifference.onNext(diff)

        assertTrue(wasCalled, "No event emitted")
    }
}