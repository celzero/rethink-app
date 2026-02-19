package com.celzero.bravedns.ui.dialog

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.DialogFragment
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.DialogSubscriptionAnimBinding
import nl.dionsegijn.konfetti.core.Angle
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.Rotation
import nl.dionsegijn.konfetti.core.emitter.Emitter
import nl.dionsegijn.konfetti.core.models.Shape
import nl.dionsegijn.konfetti.core.models.Size
import java.util.concurrent.TimeUnit

class SubscriptionAnimDialog : DialogFragment() {
    private val b by viewBinding(DialogSubscriptionAnimBinding::bind)

    companion object {
        // Dialog display duration
        private const val DIALOG_DISPLAY_DURATION_MS = 2000L

        // Konfetti animation constants
        private const val PARTY_SPEED_DEFAULT = 30f
        private const val PARTY_MAX_SPEED_DEFAULT = 50f
        private const val PARTY_DAMPING = 0.9f
        private const val PARTY_SPREAD_DEFAULT = 45
        private const val PARTY_TIME_TO_LIVE_MS = 3000L
        private const val PARTY_EMITTER_DURATION_MS = 100L
        private const val PARTY_EMITTER_MAX_DEFAULT = 30

        // Speed variations for party copies
        private const val PARTY_SPEED_VARIANT_1 = 55f
        private const val PARTY_MAX_SPEED_VARIANT_1 = 65f
        private const val PARTY_SPREAD_VARIANT = 10
        private const val PARTY_EMITTER_MAX_VARIANT = 10

        private const val PARTY_SPEED_VARIANT_2 = 65f
        private const val PARTY_MAX_SPEED_VARIANT_2 = 80f

        // Position constants
        private const val POSITION_X_CENTER = 0.5
        private const val POSITION_Y_BOTTOM = 1.0
    }


    private val autoDismissRunnable = Runnable {
        if (isAdded && !isStateSaved) {
            // safe to dismiss normally
            dismiss()
        } else if (isAdded) {
            // if state is already saved, allow state loss to avoid IllegalStateException
            dismissAllowingStateLoss()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_subscription_anim, container, false)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        dialog?.setCancelable(true)
        b.konfettiView.start(festive())
        // post delayed auto-dismiss safely
        b.konfettiView.postDelayed(autoDismissRunnable, DIALOG_DISPLAY_DURATION_MS)
    }

    override fun onDestroyView() {
        // cancel pending auto-dismiss runnable to avoid running after view/state is gone
        b.konfettiView.removeCallbacks(autoDismissRunnable)
        super.onDestroyView()
    }

    private fun festive(): List<Party> {
        val party = Party(
            speed = PARTY_SPEED_DEFAULT,
            maxSpeed = PARTY_MAX_SPEED_DEFAULT,
            damping = PARTY_DAMPING,
            angle = Angle.TOP,
            spread = PARTY_SPREAD_DEFAULT,
            size = listOf(Size.SMALL, Size.LARGE, Size.LARGE, Size.LARGE, Size.LARGE, Size.LARGE, Size.LARGE, Size.LARGE, Size.LARGE, Size.LARGE),
            shapes = listOf(Shape.Square, Shape.Circle, Shape.Circle, Shape.Circle, Shape.Circle, Shape.Circle, Shape.Circle, Shape.Circle, Shape.Circle, Shape.Circle),
            timeToLive = PARTY_TIME_TO_LIVE_MS,
            rotation = Rotation(),
            colors = listOf(0xf0efe4, 0xe6e5de, 0xf4306d, 0xfbfbf7, 0xd8d6c2, 0xf0efe4, 0xe6e5de, 0xf4306d, 0xfbfbf7, 0xd8d6c2),
            emitter = Emitter(duration = PARTY_EMITTER_DURATION_MS, TimeUnit.MILLISECONDS).max(PARTY_EMITTER_MAX_DEFAULT),
            position = Position.Relative(POSITION_X_CENTER, POSITION_Y_BOTTOM)
        )

        return listOf(
            party,
            party.copy(
                speed = PARTY_SPEED_VARIANT_1,
                maxSpeed = PARTY_MAX_SPEED_VARIANT_1,
                spread = PARTY_SPREAD_VARIANT,
                emitter = Emitter(duration = PARTY_EMITTER_DURATION_MS, TimeUnit.MILLISECONDS).max(PARTY_EMITTER_MAX_VARIANT),
            ),
            party.copy(
                speed = PARTY_SPEED_VARIANT_2,
                maxSpeed = PARTY_MAX_SPEED_VARIANT_2,
                spread = PARTY_SPREAD_VARIANT,
                emitter = Emitter(duration = PARTY_EMITTER_DURATION_MS, TimeUnit.MILLISECONDS).max(PARTY_EMITTER_MAX_VARIANT),
            )
        )
    }

}
