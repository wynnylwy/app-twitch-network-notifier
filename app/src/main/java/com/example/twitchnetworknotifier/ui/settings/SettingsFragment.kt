package com.example.twitchnetworknotifier.ui.settings

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.twitchnetworknotifier.R
import com.example.twitchnetworknotifier.databinding.FragmentSettingsBinding
import com.example.twitchnetworknotifier.ui.settings.SettingsViewModel.SaveFlowState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels()

    private var flowDialog: AlertDialog? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val settings = viewModel.loadSettingsOnce()
                binding.editChannelName.setText(settings.channelName)
                binding.editClientId.setText(settings.clientId)
                binding.editClientSecret.setText(settings.clientSecret)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isSaving.collect { saving ->
                    binding.progressSave.visibility = if (saving) View.VISIBLE else View.GONE
                    binding.buttonSave.visibility = if (saving) View.INVISIBLE else View.VISIBLE
                    binding.buttonSave.isEnabled = !saving
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.saveResults.collect { result ->
                    val message = if (result.isSuccess) {
                        "Saved"
                    } else {
                        val reason = result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                        if (reason != null) "Save failed: $reason" else "Save failed"
                    }
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.saveFlowState.collectLatest { state -> renderSaveFlowState(state) }
            }
        }

        binding.buttonSave.setOnClickListener {
            viewModel.save(
                channelName = binding.editChannelName.text.toString(),
                clientId = binding.editClientId.text.toString(),
                clientSecret = binding.editClientSecret.text.toString()
            )
        }
    }

    // Dialogs are derived from state so a configuration change re-shows the
    // right dialog (the countdown restarting on rotation is accepted).
    // collectLatest cancels this suspend function's coroutine whenever a new
    // state arrives (including on backgrounding when repeatOnLifecycle exits
    // STARTED), so an in-progress countdown never fires navigation while the
    // fragment view is torn down.
    private suspend fun renderSaveFlowState(state: SaveFlowState) {
        clearFlowDialog()
        when (state) {
            SaveFlowState.Idle -> Unit
            SaveFlowState.Connecting -> {
                flowDialog = AlertDialog.Builder(requireContext())
                    .setMessage(R.string.dialog_connecting)
                    .setCancelable(false)
                    .show()
            }
            SaveFlowState.Connected -> {
                val dialog = AlertDialog.Builder(requireContext())
                    .setTitle(R.string.dialog_connected_title)
                    .setMessage(getString(R.string.dialog_connected_countdown, 3))
                    .setCancelable(false)
                    .show()
                flowDialog = dialog

                for (secondsLeft in 3 downTo 1) {
                    dialog.setMessage(getString(R.string.dialog_connected_countdown, secondsLeft))
                    delay(1_000L)
                }

                // Reset state BEFORE navigating so a re-render can't pop twice.
                viewModel.onConnectedNavigationHandled()
                clearFlowDialog()
                findNavController().popBackStack()
            }
            SaveFlowState.ConnectFailed -> {
                flowDialog = AlertDialog.Builder(requireContext())
                    .setTitle(R.string.dialog_connect_failed_title)
                    .setMessage(R.string.dialog_connect_failed_message)
                    .setCancelable(false)
                    .setPositiveButton(R.string.dialog_ok) { _, _ ->
                        viewModel.acknowledgeConnectFailed()
                    }
                    .show()
            }
        }
    }

    private fun clearFlowDialog() {
        flowDialog?.dismiss()
        flowDialog = null
    }

    override fun onDestroyView() {
        clearFlowDialog()
        super.onDestroyView()
        _binding = null
    }
}
