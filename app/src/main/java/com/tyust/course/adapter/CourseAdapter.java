package com.tyust.course.adapter;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.tyust.course.model.Course;
import com.tyust.course.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import java.util.ArrayList;
import java.util.List;

public class CourseAdapter extends RecyclerView.Adapter<CourseAdapter.CourseViewHolder> {

    private List<Course> courses = new ArrayList<>();
    private OnItemClickListener listener;

    public interface OnItemClickListener {
        void onSelect(Course course);

        void onLongClick(Course course);
    }

    public void setCourses(List<Course> courses) {
        this.courses = courses;
        notifyDataSetChanged();
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public CourseViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_course, parent, false);
        return new CourseViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull CourseViewHolder holder, int position) {
        Course course = courses.get(position);

        holder.tvCourseName.setText(course.name != null ? course.name : "未知课程");
        holder.tvTeacher.setText(course.teacher != null ? course.teacher : "未知教师");
        holder.tvLocation.setText(course.location != null ? course.location : "待定");
        holder.tvTime.setText(course.time != null ? course.time : "时间待定");

        // Credits chip
        holder.chipCredits.setText(course.credit + "学分");

        // Capacity info
        String capacityText = "容量: " + course.selected + "/" + course.capacity;
        holder.tvCapacity.setText(capacityText);

        // Status chip
        String status = course.getStatus();
        holder.chipStatus.setText(status);

        // Set status chip color based on availability
        if (course.isSelected) {
            holder.chipStatus.setChipBackgroundColorResource(android.R.color.holo_blue_light);
            holder.chipStatus.setText("已选");
        } else if ("可选".equals(status)) {
            holder.chipStatus.setChipBackgroundColorResource(android.R.color.holo_green_light);
        } else if ("已满".equals(status)) {
            holder.chipStatus.setChipBackgroundColorResource(android.R.color.holo_red_light);
        } else {
            holder.chipStatus.setChipBackgroundColorResource(android.R.color.darker_gray);
        }

        // Action button
        if (course.isSelected) {
            holder.btnSelect.setText("已选");
            holder.btnSelect.setEnabled(false);
        } else {
            holder.btnSelect.setText("选课");
            holder.btnSelect.setEnabled(true);
        }

        holder.btnSelect.setOnClickListener(v -> {
            if (listener != null) {
                listener.onSelect(course);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (listener != null) {
                listener.onLongClick(course);
                return true;
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return courses.size();
    }

    static class CourseViewHolder extends RecyclerView.ViewHolder {
        TextView tvCourseName, tvTeacher, tvLocation, tvTime, tvCapacity;
        Chip chipCredits, chipStatus;
        MaterialButton btnSelect;

        public CourseViewHolder(@NonNull View itemView) {
            super(itemView);
            tvCourseName = itemView.findViewById(R.id.tvCourseName);
            tvTeacher = itemView.findViewById(R.id.tvTeacher);
            tvLocation = itemView.findViewById(R.id.tvLocation);
            tvTime = itemView.findViewById(R.id.tvTime);
            tvCapacity = itemView.findViewById(R.id.tvCapacity);
            chipCredits = itemView.findViewById(R.id.chipCredits);
            chipStatus = itemView.findViewById(R.id.chipStatus);
            btnSelect = itemView.findViewById(R.id.btnSelect);
        }
    }
}
