package com.example.twitchnetworknotifier.ui.main

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.twitchnetworknotifier.R
import com.example.twitchnetworknotifier.databinding.FragmentMainBinding
import com.example.twitchnetworknotifier.monitor.StreamMonitorService
import com.example.twitchnetworknotifier.monitor.model.StreamStatus
import kotlinx.coroutines.launch

class MainFragment : Fragment() {

    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by viewModels()
    private val historyAdapter = HistoryAdapter()
    private var isUpdatingSwitchProgrammatically = false

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op: monitoring proceeds regardless; alerts just won't show if denied */ }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recyclerHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerHistory.adapter = historyAdapter

        binding.switchMonitoring.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitchProgrammatically) return@setOnCheckedChangeListener
            if (isChecked) {
                maybeRequestNotificationPermission()
                viewModel.setMonitoringEnabled(true)
                StreamMonitorService.start(requireContext(), showWelcome = true)
            } else {
                setSwitchChecked(true)
                showToggleOffConfirmation()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.monitoringEnabled.collect { setSwitchChecked(it) } }
                launch { viewModel.currentStatus.collect { updateStatusText(it) } }
                launch { viewModel.history.collect { historyAdapter.submitList(it) } }
            }
        }
    }

    private fun setSwitchChecked(checked: Boolean) {
        isUpdatingSwitchProgrammatically = true
        binding.switchMonitoring.isChecked = checked
        isUpdatingSwitchProgrammatically = false
    }

    private fun updateStatusText(status: StreamStatus) {
        binding.textStatus.text = when (status) {
            StreamStatus.UNKNOWN -> getString(R.string.status_unknown)
            StreamStatus.LIVE -> getString(R.string.status_live)
            StreamStatus.OFFLINE -> getString(R.string.status_offline)
            StreamStatus.CONNECTION_ISSUE -> getString(R.string.status_connection_issue)
        }
    }

    private fun showToggleOffConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.dialog_toggle_off_title)
            .setPositiveButton(R.string.dialog_yes) { _, _ ->
                viewModel.setMonitoringEnabled(false)
                StreamMonitorService.stop(requireContext())
            }
            .setNegativeButton(R.string.dialog_no, null)
            .setCancelable(false)
            .show()
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
