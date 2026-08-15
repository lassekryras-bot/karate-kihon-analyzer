package dk.lasse.karatecliprecorder.learning

import dk.lasse.karateanalyzer.core.FeedbackCode
import dk.lasse.karateanalyzer.core.InstantVerificationStatus
import dk.lasse.karateanalyzer.core.TemporalVerificationStatus

object FindYourWeaponCoachCopy {
    fun requirementText(step: FindYourWeaponStep): String = when (step) {
        FindYourWeaponStep.OPEN_PALM ->
            "Show me an open hand. Keep your palm facing the camera and keep your thumb outside the hand."
        FindYourWeaponStep.BEND_FINGERTIPS ->
            "Now bend only the top parts of your fingers. Keep your thumb outside and away from the fist."
        FindYourWeaponStep.CLOSE_FINGERS ->
            "Now close your fingers into your palm. Keep the thumb outside until the next step."
        FindYourWeaponStep.THUMB_ON_TOP ->
            "Now lay the thumb across the front of the fingers. Let it cover the index finger, not the whole fist."
        FindYourWeaponStep.FRONT_TWO_KNUCKLES ->
            "Turn the back of the fist toward the camera. These two front knuckles are your weapon."
    }

    fun correctionText(step: FindYourWeaponStep, feedbackCode: FeedbackCode): String = when (feedbackCode) {
        FeedbackCode.NONE -> "Follow the hand guide on the screen."
        FeedbackCode.MOVE_INTO_GUIDE -> "Move your hand into the bright guide."
        FeedbackCode.MOVE_CLOSER -> "Move your hand a little closer to the camera."
        FeedbackCode.OPEN_FINGERS -> "Open the fingers more, like a flat high five."
        FeedbackCode.OPEN_THUMB -> openThumbText(step)
        FeedbackCode.BEND_FINGERTIPS_MORE ->
            "Bend the fingertips a little more, but keep the big knuckles open."
        FeedbackCode.DO_NOT_CLOSE_YET ->
            "Do not close the fist yet. Only bend the top parts of the fingers."
        FeedbackCode.FINGERS_UNEVEN ->
            "Try to move the four fingers together so they make the same shape."
        FeedbackCode.CLOSE_FINGERS_MORE -> closeFingersMoreText(step)
        FeedbackCode.MOVE_THUMB_ACROSS ->
            "Lay the thumb across the front of the fist, on top of the index finger."
        FeedbackCode.TURN_FIST_TOWARD_CAMERA -> turnFistText(step)
        FeedbackCode.INSUFFICIENT_VISIBILITY ->
            "I cannot see the hand clearly yet. Move the whole hand into the camera view and keep it steady."
        FeedbackCode.HOLD_STILL -> "Hold still for a moment so I can check the hand."
        FeedbackCode.GOOD -> temporalText(step, TemporalVerificationStatus.WAITING_FOR_DATA)
    }

    fun temporalText(step: FindYourWeaponStep, status: TemporalVerificationStatus): String = when (status) {
        TemporalVerificationStatus.WAITING_FOR_DATA -> "Good shape. Hold it still."
        TemporalVerificationStatus.BUILDING_PROGRESS -> "Good. Keep holding this shape."
        TemporalVerificationStatus.HOLDING -> "Almost there. Stay still."
        TemporalVerificationStatus.PAUSED -> "I saw it. Hold still again."
        TemporalVerificationStatus.LOSING_PROGRESS ->
            "The shape changed. Go back to the last good position."
        TemporalVerificationStatus.ACCEPTED -> if (step == FindYourWeaponStep.FRONT_TWO_KNUCKLES) {
            "This is your weapon. Hit with these two knuckles."
        } else {
            "Good. That step is ready."
        }
    }

    fun messageText(
        step: FindYourWeaponStep,
        state: FindYourWeaponAnalysisState?,
        finalAccepted: Boolean = false,
    ): String {
        if (finalAccepted && step == FindYourWeaponStep.FRONT_TWO_KNUCKLES) {
            return temporalText(step, TemporalVerificationStatus.ACCEPTED)
        }

        if (state == null || state.activeStep != step || state.instantResult == null) {
            return requirementText(step)
        }

        val instant = state.instantResult
        if (!state.handDetected || instant.status == InstantVerificationStatus.INSUFFICIENT_DATA) {
            return correctionText(step, FeedbackCode.INSUFFICIENT_VISIBILITY)
        }

        if (instant.feedbackCode != FeedbackCode.GOOD) {
            return correctionText(step, instant.feedbackCode)
        }

        return temporalText(
            step = step,
            status = state.temporalResult?.status ?: TemporalVerificationStatus.WAITING_FOR_DATA,
        )
    }

    private fun openThumbText(step: FindYourWeaponStep): String = when (step) {
        FindYourWeaponStep.OPEN_PALM,
        FindYourWeaponStep.BEND_FINGERTIPS ->
            "Move the thumb out to the side. Do not fold it across the fingers yet."
        FindYourWeaponStep.CLOSE_FINGERS ->
            "Keep the thumb outside the line. Do not put the thumb across until the next step."
        FindYourWeaponStep.THUMB_ON_TOP ->
            "Move the thumb back so it stays on the index finger side. Do not cover the middle finger."
        FindYourWeaponStep.FRONT_TWO_KNUCKLES ->
            "The thumb is okay for now. Turn the back of the fist toward the camera."
    }

    private fun closeFingersMoreText(step: FindYourWeaponStep): String = when (step) {
        FindYourWeaponStep.CLOSE_FINGERS -> "Close the fingers more into the palm."
        FindYourWeaponStep.THUMB_ON_TOP -> "Close the fingers first, then lay the thumb across."
        FindYourWeaponStep.OPEN_PALM,
        FindYourWeaponStep.BEND_FINGERTIPS,
        FindYourWeaponStep.FRONT_TWO_KNUCKLES -> "Close the fingers more."
    }

    private fun turnFistText(step: FindYourWeaponStep): String = when (step) {
        FindYourWeaponStep.OPEN_PALM -> "Turn your palm toward the camera."
        FindYourWeaponStep.FRONT_TWO_KNUCKLES ->
            "Turn the back of the fist toward the camera so I can see the two knuckles."
        FindYourWeaponStep.BEND_FINGERTIPS,
        FindYourWeaponStep.CLOSE_FINGERS,
        FindYourWeaponStep.THUMB_ON_TOP -> "Turn the fist toward the camera."
    }
}
