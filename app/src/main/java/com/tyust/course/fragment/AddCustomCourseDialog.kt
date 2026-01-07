package com.tyust.course.fragment

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.fragment.app.DialogFragment
import com.tyust.course.manager.ScheduleSettingsManager

/**
 * 添加自定义课程对话框
 * Modern UI Version
 */
class AddCustomCourseDialog(
    private val onCourseAdded: (ScheduleSettingsManager.CustomCourse) -> Unit
) : DialogFragment() {
    
    private val PRIMARY_COLOR = Color.parseColor("#7C4DFF")
    private val TEXT_COLOR_PRIMARY = Color.parseColor("#333333")
    private val SURFACE_COLOR = Color.parseColor("#F5F5F7")
    
    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(
                (resources.displayMetrics.widthPixels * 0.9).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundDrawableResource(android.R.color.transparent)
            attributes.windowAnimations = android.R.style.Animation_Dialog
        }
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val rootLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dp2px(24f)
            setPadding(padding, padding, padding, padding)
            background = createRoundedDrawable(Color.WHITE, 16f)
            elevation = dp2px(8f).toFloat()
            
            // Entry Animation
            alpha = 0f
            translationY = dp2px(20f).toFloat()
            animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(300)
                .setInterpolator(OvershootInterpolator())
                .start()
        }
            
        // Title
        rootLayout.addView(TextView(context).apply {
            text = "添加自定义课程"
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(TEXT_COLOR_PRIMARY)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        
        rootLayout.addView(createSpacer(24))
        
        // Course name
        val nameEdit = createModernEditText("课程名称 *")
        rootLayout.addView(nameEdit)
        rootLayout.addView(createSpacer(12))
        
        // Location
        val locationEdit = createModernEditText("上课地点")
        rootLayout.addView(locationEdit)
        rootLayout.addView(createSpacer(12))
        
        // Teacher
        val teacherEdit = createModernEditText("授课教师")
        rootLayout.addView(teacherEdit)
        
        rootLayout.addView(createSpacer(16))
        
        // --- Row: Day & Weeks ---
        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        
        // Day Spinner
        val dayContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, 0, dp2px(8f), 0)
            
            addView(TextView(context).apply {
                text = "星期"
                textSize = 12f
                setTextColor(Color.GRAY)
                setPadding(dp2px(4f), 0, 0, dp2px(4f))
            })
            
            val bg = FrameLayout(context).apply {
                background = createRoundedDrawable(SURFACE_COLOR, 12f)
                setPadding(dp2px(8f), 0, dp2px(4f), 0)
            }
            
            val spinner = Spinner(context)
            val days = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
            spinner.adapter = createCustomSpinnerAdapter(days)
            bg.addView(spinner)
            addView(bg)
            
            tag = spinner // Save reference
        }
        row1.addView(dayContainer)
        
        // Weeks Input
        val weeksContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp2px(8f), 0, 0, 0)
            
            addView(TextView(context).apply {
                text = "周次 (如1-16)"
                textSize = 12f
                setTextColor(Color.GRAY)
                setPadding(dp2px(4f), 0, 0, dp2px(4f))
            })
            
            val et = createModernEditText("1-16周").apply { setText("1-16周") }
            addView(et)
            
            tag = et // Save reference
        }
        row1.addView(weeksContainer)
        
        rootLayout.addView(row1)
        rootLayout.addView(createSpacer(12))
        
        // --- Row: Start & End Period ---
        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        
        // Start Period
        val startContainer = createPeriodSpinner("开始节次", 1)
        row2.addView(startContainer)
        
        // End Period
        val endContainer = createPeriodSpinner("结束节次", 2).apply {
            setPadding(dp2px(8f), 0, 0, 0)
        }
        row2.addView(endContainer)
        
        rootLayout.addView(row2)
        rootLayout.addView(createSpacer(32))
        
        // Buttons
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            
            addView(Button(context).apply {
                text = "取消"
                textSize = 16f
                setTextColor(Color.GRAY)
                background = null
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                stateListAnimator = null
                setOnClickListener { dismiss() }
            })
            
            addView(createSpacer(16))
            
            addView(Button(context).apply {
                text = "添加"
                textSize = 16f
                setTextColor(Color.WHITE)
                background = createRoundedDrawable(PRIMARY_COLOR, 12f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                elevation = dp2px(4f).toFloat() // Shadow
                setOnClickListener {
                    val daySpinner = dayContainer.tag as Spinner
                    val weeksEt = weeksContainer.tag as EditText
                    val startSpinner = (startContainer.tag as Spinner)
                    val endSpinner = (endContainer.tag as Spinner)
                    
                    val name = nameEdit.text.toString().trim()
                    if (name.isEmpty()) {
                        Toast.makeText(context, "请输入课程名称", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    
                    val course = ScheduleSettingsManager.CustomCourse(
                        id = "custom_${System.currentTimeMillis()}",
                        name = name,
                        location = locationEdit.text.toString().trim(),
                        teacher = teacherEdit.text.toString().trim(),
                        day = daySpinner.selectedItemPosition + 1,
                        startPeriod = startSpinner.selectedItemPosition + 1,
                        endPeriod = endSpinner.selectedItemPosition + 1,
                        weeks = weeksEt.text.toString().ifEmpty { "1-16周" }
                    )
                    
                    onCourseAdded(course)
                    dismiss()
                }
            })
        }
        rootLayout.addView(buttonRow)
        
        return rootLayout
    }
    
    private fun createPeriodSpinner(label: String, defaultSelection: Int): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            
            addView(TextView(context).apply {
                text = label
                textSize = 12f
                setTextColor(Color.GRAY)
                setPadding(dp2px(4f), 0, 0, dp2px(4f))
            })
            
            val bg = FrameLayout(context).apply {
                background = createRoundedDrawable(SURFACE_COLOR, 12f)
                setPadding(dp2px(8f), 0, dp2px(4f), 0)
            }
            
            val spinner = Spinner(context)
            val periods = (1..14).map { "第${it}节" }
            spinner.adapter = createCustomSpinnerAdapter(periods)
            spinner.setSelection(defaultSelection - 1)
            bg.addView(spinner)
            addView(bg)
            
            tag = spinner
        }
    }
    
    private fun createCustomSpinnerAdapter(items: List<String>): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(requireContext(), android.R.layout.simple_spinner_dropdown_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.setTextColor(TEXT_COLOR_PRIMARY)
                return view
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent) as TextView
                view.setTextColor(TEXT_COLOR_PRIMARY)
                view.setBackgroundColor(Color.WHITE)
                view.setPadding(dp2px(16f), dp2px(12f), dp2px(16f), dp2px(12f))
                return view
            }
        }
    }
    
    private fun createModernEditText(hintText: String): EditText {
        return EditText(requireContext()).apply {
            hint = hintText
            setHintTextColor(Color.parseColor("#B0B0B0"))
            setTextColor(TEXT_COLOR_PRIMARY)
            background = createRoundedDrawable(SURFACE_COLOR, 12f)
            setPadding(dp2px(16f), dp2px(12f), dp2px(16f), dp2px(12f))
            textSize = 16f
        }
    }
    
    private fun dp2px(dp: Float): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
    
    private fun createSpacer(dpHeight: Int): View {
        return View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp2px(dpHeight.toFloat())
            )
        }
    }
    
    private fun createRoundedDrawable(color: Int, radiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp2px(radiusDp).toFloat()
        }
    }
}
