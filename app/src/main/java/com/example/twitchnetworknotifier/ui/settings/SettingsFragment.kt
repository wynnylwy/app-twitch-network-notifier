package com.example.twitchnetworknotifier.ui.settings

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
import com.example.twitchnetworknotifier.databinding.FragmentSettingsBinding
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels()

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

        binding.buttonSave.setOnClickListener {
            viewModel.save(
                channelName = binding.editChannelName.text.toString(),
                clientId = binding.editClientId.text.toString(),
                clientSecret = binding.editClientSecret.text.toString()
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
