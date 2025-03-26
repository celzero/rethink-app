package com.celzero.bravedns.ui.dialog

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_subscription_anim, container, false)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog?.setCancelable(true)
        b.konfettiView.start(festive())
        b.konfettiView.postDelayed({
            dismiss()
        }, 2000L)
    }

    private fun festive(): List<Party> {
        val party = Party(
            speed = 30f,
            maxSpeed = 50f,
            damping = 0.9f,
            angle = Angle.TOP,
            spread = 45,
            size = listOf(Size.SMALL, Size.LARGE, Size.LARGE, Size.LARGE, Size.LARGE, Size.LARGE, Size.LARGE, Size.LARGE, Size.LARGE, Size.LARGE),
            shapes = listOf(Shape.Square, Shape.Circle, Shape.Circle, Shape.Circle, Shape.Circle, Shape.Circle, Shape.Circle, Shape.Circle, Shape.Circle, Shape.Circle),
            timeToLive = 3000L,
            rotation = Rotation(),
            colors = listOf(0xf0efe4, 0xe6e5de, 0xf4306d, 0xfbfbf7, 0xd8d6c2, 0xf0efe4, 0xe6e5de, 0xf4306d, 0xfbfbf7, 0xd8d6c2),
            emitter = Emitter(duration = 100, TimeUnit.MILLISECONDS).max(30),
            position = Position.Relative(0.5, 1.0)
        )

        return listOf(
            party,
            party.copy(
                speed = 55f,
                maxSpeed = 65f,
                spread = 10,
                emitter = Emitter(duration = 100, TimeUnit.MILLISECONDS).max(10),
            ),
            party.copy(
                speed = 65f,
                maxSpeed = 80f,
                spread = 10,
                emitter = Emitter(duration = 100, TimeUnit.MILLISECONDS).max(10),
            )
        )
    }

}
