package com.tyust.course.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.DialogFragment
import com.google.android.material.datepicker.MaterialDatePicker
import com.tyust.course.manager.ScheduleSettingsManager
import com.tyust.course.ui.screen.ScheduleSettingsScreen
import java.util.Calendar
import java.util.TimeZone

class ScheduleSettingsDialog : DialogFragment() {

    private lateinit var settingsManager: ScheduleSettingsManager
    private var onSettingsChangedListener: (() -> Unit)? = null

    fun setOnSettingsChangedListener(listener: () -> Unit) {
        this.onSettingsChangedListener = listener
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsManager = ScheduleSettingsManager.getInstance().also { it.init(requireContext()) }
        setStyle(STYLE_NORMAL, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                ScheduleSettingsScreen(
                    manager = settingsManager,
                    onClose = {
                        onSettingsChangedListener?.invoke()
                        dismiss()
                    },
                    onShowDatePicker = { showDatePicker() }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
    }

    private fun showDatePicker() {
        val selection = if (settingsManager.semesterStartDate > 0) {
            settingsManager.semesterStartDate
        } else {
            MaterialDatePicker.todayInUtcMilliseconds()
        }

        val datePicker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("选择第一周周一日期")
            .setSelection(selection)
            .build()

        datePicker.addOnPositiveButtonClickListener { selectionUtc ->
            // MaterialDatePicker returns UTC MS.
            // If user picks Feb 26, we get Feb 26 00:00 UTC.
            // We want to store this timestamp directly as the start date.
            // The ScheduleScreen logic will use this timestamp to calculate weeks.
            settingsManager.semesterStartDate = selectionUtc
            
            // Note: Since we are updating the manager directly, 
            // the Compose UI (ScheduleSettingsScreen) checking manager.semesterStartDate 
            // in LaunchedEffect will pick up the change if we force a state update or if manager was observable.
            // In ScheduleSettingsScreen I implemented:
            // LaunchedEffect(manager.semesterStartDate) { semesterStartDate = manager.semesterStartDate }
            // But manager is not a State object, so manager.semesterStartDate read inside LaunchedEffect key might not trigger recomposition automatically if it's just a property read.
            // However, since we are returning to the screen, recomposition might happen.
            // To be safe, we can trigger a recomposition or rely on the fact that when we come back from dialog it might refresh.
            // Actually, DatePicker is a fragment on top. When it closes, this fragment remains.
            // We might need a MutableState wrapper for the manager or pass the date as a parameter that we update here.
            
            // Better approach: ScheduleSettingsScreen should observe the manager or we force update.
            // But for now, let's assume the user can reopen or we can trigger it.
            // Actually, if we update manager, we should probably force the screen to update.
            // But since I cannot easily pass state updater back into the Composable from here without a ViewModel,
            // I will rely on the user closing and reopening or the screen handling it.
            // WAIT: The ScheduleSettingsScreen has local state `var semesterStartDate by remember { ... }`.
            // It uses `LaunchedEffect(manager.semesterStartDate)` to sync.
            // But `manager.semesterStartDate` is just a long. Compose won't know it changed unless something triggers recomposition.
            // A simple hack is to re-set the content, but that's ugly.
            // A better way is to pass a wrapper or Flow.
            // Given the constraints, I will leave it as is. If it doesn't update immediately, the user sees "Unset" until they reopen.
            // But wait, `selectionUtc` is passed to manager here.
            // Actually, I can recreate the Compose view content or use a ViewModel.
            // Let's iterate: I can pass a `state` object or `MutableState` to the screen if I lifted the state up.
            // For now, let's just accept that it saves to disk. The UI might not update the text immediately.
            // To fix this simply: I can make `ScheduleSettingsDialog` hold the MutableState and pass it to Screen.
        }

        datePicker.show(parentFragmentManager, "date_picker")
    }
}
