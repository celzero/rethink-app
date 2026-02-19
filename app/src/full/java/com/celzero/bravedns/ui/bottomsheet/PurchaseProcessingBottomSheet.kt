package com.celzero.bravedns.ui.bottomsheet

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.BottomsheetPurchaseProcessingBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.io.Serializable

/**
 * Bottom sheet for displaying purchase processing states
 * Provides visual feedback during subscription purchase, activation, and completion
 */
class PurchaseProcessingBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomsheetPurchaseProcessingBinding? = null
    private val binding get() = _binding!!

    private var currentState: ProcessingState = ProcessingState.Processing

    companion object {
        private const val TAG = "PurchaseProcessingBS"
        private const val ARG_STATE = "state"
        private const val ARG_MESSAGE = "message"

        fun newInstance(state: ProcessingState, message: String? = null): PurchaseProcessingBottomSheet {
            return PurchaseProcessingBottomSheet().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_STATE, state)
                    message?.let { putString(ARG_MESSAGE, it) }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.BottomSheetDialogTheme)
        isCancelable = false
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomsheetPurchaseProcessingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val state = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            arguments?.getSerializable(ARG_STATE, ProcessingState::class.java)
        } else {
            @Suppress("DEPRECATION")
            arguments?.getSerializable(ARG_STATE) as? ProcessingState
        } ?: ProcessingState.Processing

        val message = arguments?.getString(ARG_MESSAGE)

        updateState(state, message)
    }

    /**
     * Update the bottom sheet state
     */
    fun updateState(state: ProcessingState, message: String? = null) {
        currentState = state

        when (state) {
            ProcessingState.Processing -> showProcessing(message)
            ProcessingState.PendingVerification -> showPendingVerification()
            ProcessingState.Success -> showSuccess(message)
            ProcessingState.Error -> showError(message)
        }
    }

    private fun showProcessing(message: String?) {
        binding.progressIndicator.isVisible = true
        binding.statusIcon.isVisible = false
        binding.titleText.text = getString(R.string.processing_purchase)
        binding.messageText.text = message ?: getString(R.string.please_wait_processing)
        binding.actionButton.isVisible = false
    }

    private fun showPendingVerification() {
        binding.progressIndicator.isVisible = true
        binding.statusIcon.isVisible = false
        binding.titleText.text = getString(R.string.verifying_purchase)
        binding.messageText.text = getString(R.string.verifying_with_play_store)
        binding.actionButton.isVisible = false
    }

    private fun showSuccess(message: String?) {
        binding.progressIndicator.isVisible = false
        binding.statusIcon.isVisible = true
        binding.statusIcon.setImageResource(R.drawable.ic_check_circle)
        binding.titleText.text = getString(R.string.purchase_successful)
        binding.messageText.text = message ?: getString(R.string.subscription_activated)

        binding.actionButton.isVisible = true
        binding.actionButton.text = getString(R.string.continue_text)
        binding.actionButton.setOnClickListener {
            dismiss()
        }
    }

    private fun showError(message: String?) {
        binding.progressIndicator.isVisible = false
        binding.statusIcon.isVisible = true
        binding.statusIcon.setImageResource(androidx.biometric.R.drawable.fingerprint_dialog_error)
        binding.titleText.text = getString(R.string.purchase_failed)
        binding.messageText.text = message ?: getString(R.string.something_went_wrong)

        binding.actionButton.isVisible = true
        binding.actionButton.text = getString(R.string.close)
        binding.actionButton.setOnClickListener {
            dismiss()
        }
    }

    override fun dismiss() {
        // Check if fragment is in valid state before dismissing
        if (isAdded && !isStateSaved) {
            super.dismiss()
        }
    }

    override fun dismissAllowingStateLoss() {
        // Safe dismiss that works even after onSaveInstanceState
        if (isAdded) {
            super.dismissAllowingStateLoss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    enum class ProcessingState : Serializable {
        Processing,
        PendingVerification,
        Success,
        Error
    }
}