package com.example.pushupdetector;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Bundle;

import com.example.pushupdetector.databinding.ActivityCompleteBinding;

public class CompleteActivity extends AppCompatActivity {
    private ActivityCompleteBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCompleteBinding.inflate(getLayoutInflater());

        setContentView(binding.getRoot());

        initComponents();
        subscribeListeners();
    }

    private void initComponents() {
        Bundle b = getIntent().getExtras();
        int reps = 0;

        if (b != null) {
            reps = b.getInt("REPS",0);
        }

        binding.tvRepsValue.setText(String.valueOf(reps));
    }

    private void subscribeListeners() {
        binding.btnRestart.setOnClickListener(v -> {
            Intent goToMain = new Intent(this, MainActivity.class);

            startActivity(goToMain);
            finish();
        });
    }
}