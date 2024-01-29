package com.example.pushupdetector.helper;

import com.google.mlkit.vision.pose.PoseDetectorOptionsBase;
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions;
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions;

public class PreferenceHelper {
    public static PoseDetectorOptionsBase getPoseDetectorDefaultOptions() {
        PoseDetectorOptions.Builder builder =
                new PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE);

        return builder.build();
    }
}
