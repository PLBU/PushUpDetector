package com.example.pushupdetector.helper;

import com.google.mlkit.vision.pose.PoseDetectorOptionsBase;
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions;

public class PreferenceHelper {
    public static PoseDetectorOptionsBase getPoseDetectorDefaultOptions() {
        AccuratePoseDetectorOptions.Builder builder =
                new AccuratePoseDetectorOptions.Builder()
                        .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE);
        builder.setPreferredHardwareConfigs(AccuratePoseDetectorOptions.CPU_GPU);
        return builder.build();
    }
}
