

package com.example.pushupdetector.posedetector.classification;

import static com.example.pushupdetector.MainActivity.mainHandler;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.google.common.base.Preconditions;
import com.google.mlkit.vision.pose.Pose;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Accepts a stream of {@link Pose} for classification and Rep counting.
 */
public class PoseClassifierProcessor {
    public static final String TAG = "PoseClassifierProcessor";
    private static final String POSE_SAMPLES_FILE = "fitness_pose_samples.csv";

    // Specify classes for which we want rep counting.
    // These are the labels in the given {@code POSE_SAMPLES_FILE}. You can set your own class labels
    // for your pose samples.
    private static final String PUSHUPS_CLASS = "pushups_down";

    public static Handler backgroundHandler;

    private final boolean isStreamMode;

    private EMASmoothing emaSmoothing;
    private RepetitionCounter repCounter;
    private PoseClassifier poseClassifier;
    private String lastRepResult;
    private int currReps = 0;

    @WorkerThread
    public PoseClassifierProcessor(Context context, boolean isStreamMode) {
        Preconditions.checkState(Looper.myLooper() != Looper.getMainLooper());
        this.isStreamMode = isStreamMode;
        if (isStreamMode) {
            emaSmoothing = new EMASmoothing();
            lastRepResult = "";
        }

        HandlerThread handlerThread = new HandlerThread("PoseClassifierBackgroundThread");
        handlerThread.start();
        backgroundHandler = new Handler(handlerThread.getLooper()) {
            @Override
            public void handleMessage(@NonNull Message msg) {
                String message = (String) msg.obj;
                if (TAG.equals(message)) {
                    Message toMain = new Message();
                    toMain.obj = currReps;
                    mainHandler.sendMessage(toMain);
                }

            }
        };

        loadPoseSamples(context);
    }

    private void loadPoseSamples(Context context) {
        List<PoseSample> poseSamples = new ArrayList<>();
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(context.getAssets().open(POSE_SAMPLES_FILE)));
            String csvLine = reader.readLine();
            while (csvLine != null) {
                // If line is not a valid {@link PoseSample}, we'll get null and skip adding to the list.
                PoseSample poseSample = PoseSample.getPoseSample(csvLine, ",");
                if (poseSample != null) {
                    poseSamples.add(poseSample);
                }
                csvLine = reader.readLine();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error when loading pose samples.\n" + e);
        }
        poseClassifier = new PoseClassifier(poseSamples);
        repCounter = new RepetitionCounter(PUSHUPS_CLASS);
    }

    /**
     * Given a new {@link Pose} input, returns a list of formatted {@link String}s with Pose
     * classification results.
     *
     * <p>Currently it returns up to 2 strings as following:
     * 0: PoseClass : X reps
     */
    @WorkerThread
    public String getPoseResult(Pose pose) {
        Preconditions.checkState(Looper.myLooper() != Looper.getMainLooper());
        ClassificationResult classification = poseClassifier.classify(pose);

        // Feed pose to smoothing even if no pose found.
        classification = emaSmoothing.getSmoothedResult(classification);

        // Return early without updating repCounter if no pose found.
        if (pose.getAllPoseLandmarks().isEmpty()) {
            return lastRepResult;
        }

        int repsBefore = repCounter.getNumRepeats();
        int repsAfter = repCounter.addClassificationResult(classification);
        if (repsAfter > repsBefore) {
            // Play a fun beep when rep counter updates.
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP);
            lastRepResult = String.format(
                    Locale.US, "Counter : %d reps", repsAfter);
            currReps = repsAfter;
        }
        return lastRepResult;
    }
}
