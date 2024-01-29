package com.example.pushupdetector;

import static androidx.constraintlayout.helper.widget.MotionEffect.TAG;

import static com.example.pushupdetector.posedetector.classification.PoseClassifierProcessor.backgroundHandler;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraInfoUnavailableException;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ViewPort;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import com.example.pushupdetector.databinding.ActivityMainBinding;
import com.example.pushupdetector.databinding.BottomsheetTutorialBinding;
import com.example.pushupdetector.helper.GraphicOverlay;
import com.example.pushupdetector.helper.PreferenceHelper;
import com.example.pushupdetector.posedetector.PoseDetectorProcessor;
import com.example.pushupdetector.posedetector.classification.PoseClassifierProcessor;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.pose.PoseDetectorOptionsBase;

import java.util.Objects;
import java.util.concurrent.ExecutionException;

public class MainActivity extends AppCompatActivity {
    private ActivityMainBinding binding;

    @Nullable
    private ProcessCameraProvider cameraProvider;
    @Nullable
    private Preview previewUseCase;
    @Nullable
    private ImageAnalysis analysisUseCase;
    @Nullable
    private PoseDetectorProcessor imageProcessor;

    private boolean needUpdateGraphicOverlayImageSourceInfo;
    private CameraSelector cameraSelector;
    private int lensFacing = CameraSelector.LENS_FACING_FRONT;

    private BottomSheetBehavior<View> bottomSheetBehavior;

    public static Handler mainHandler;

    private final ActivityResultLauncher<String> requestPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            granted -> {
                if (!granted) {
                    Toast.makeText(this, "Please give camera access", Toast.LENGTH_LONG).show();
                } else {
                    onPermissionGranted();
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());

        bottomSheetBehavior = BottomSheetBehavior.from(binding.btmSheetTutor.getRoot());
        bottomSheetBehavior.setPeekHeight(100);

        mainHandler = new Handler(getMainLooper()) {
            @Override
            public void handleMessage(@NonNull Message msg) {
                if (msg.arg1 == MessageType.FINISH.ordinal()) {
                    int reps = (int) msg.obj;

                    Intent goToComplete = new Intent(getApplicationContext(), CompleteActivity.class);
                    goToComplete.putExtra("REPS", reps);

                    startActivity(goToComplete);
                    finish();
                }
            }
        };

        setContentView(binding.getRoot());

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA);
        } else {
            onPermissionGranted();
        }
    }

    private void onPermissionGranted() {
        binding.tvCamAccess.setVisibility(View.GONE);
        setCameraProvider();
        subscribeListeners();
        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    private void subscribeListeners() {
        binding.getRoot().setOnClickListener(v -> {
            if (bottomSheetBehavior.getState() == BottomSheetBehavior.STATE_EXPANDED) {
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            }
        });

        binding.btmSheetTutor.btnStart.setOnClickListener(v -> {
            bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            binding.getRoot().setOnClickListener(null);
            binding.cl.setVisibility(View.GONE);
            binding.btnFinish.setVisibility(View.VISIBLE);
            bindAllCameraUseCases();
        });

        binding.btnFinish.setOnClickListener(v -> {
            Message toBackground = new Message();
            toBackground.obj = PoseClassifierProcessor.TAG;
            backgroundHandler.sendMessage(toBackground);
        });

        binding.btmSheetTutor.getRoot().setOnClickListener(v -> {
            if (bottomSheetBehavior.getState() == BottomSheetBehavior.STATE_COLLAPSED) {
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        });

        binding.btnFlip.setOnClickListener(v -> {
            if (cameraProvider == null) {
                return;
            }

            int newLensFacing =
                    lensFacing == CameraSelector.LENS_FACING_FRONT
                            ? CameraSelector.LENS_FACING_BACK
                            : CameraSelector.LENS_FACING_FRONT;
            CameraSelector newCameraSelector =
                    new CameraSelector.Builder().requireLensFacing(newLensFacing).build();

            try {
                if (cameraProvider.hasCamera(newCameraSelector)) {
                    lensFacing = newLensFacing;
                    cameraSelector = newCameraSelector;
                    if (previewUseCase != null) {
                        if (analysisUseCase == null) {
                            bindPreviewUseCase();
                        } else {
                            bindAllCameraUseCases();
                        }
                    }

                    return;
                }
            } catch (CameraInfoUnavailableException e) {
                // Falls through
            }

            Toast.makeText(
                            getApplicationContext(),
                            "This device does not have lens with facing: " + newLensFacing,
                            Toast.LENGTH_SHORT)
                    .show();
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (previewUseCase != null) {
            if (analysisUseCase == null) {
                bindPreviewUseCase();
            } else {
                bindAllCameraUseCases();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        reset();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        reset();
    }

    private void reset() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            cameraProvider = null;
        }
        if (previewUseCase != null) {
            previewUseCase = null;
        }
        if (analysisUseCase != null) {
            analysisUseCase.clearAnalyzer();
            analysisUseCase = null;
        }
        if (imageProcessor != null) {
            imageProcessor.stop();
        }
    }

    private void bindPreviewUseCase() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            cameraSelector = new CameraSelector.Builder()
                    .requireLensFacing(lensFacing).build();

            setPreviewUseCase();

            ViewPort viewPort = binding.previewView.getViewPort();
            if (viewPort != null && previewUseCase !=  null) {
                cameraProvider.bindToLifecycle(this, cameraSelector, previewUseCase);
            }
        }
    }

    private void bindAllCameraUseCases() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
            cameraSelector = new CameraSelector.Builder()
                    .requireLensFacing(lensFacing).build();

            setPreviewUseCase();
            setAnalysisUseCase();

            ViewPort viewPort = binding.previewView.getViewPort();
            if (viewPort != null && previewUseCase != null && analysisUseCase != null) {
                UseCaseGroup useCaseGroup = new UseCaseGroup.Builder()
                        .addUseCase(previewUseCase)
                        .addUseCase(analysisUseCase)
                        .setViewPort(viewPort)
                        .build();

                cameraProvider.bindToLifecycle(this, cameraSelector, useCaseGroup);
            }
        }
    }

    private void setCameraProvider() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                this.cameraProvider = cameraProviderFuture.get();
                bindPreviewUseCase();
            } catch (ExecutionException | InterruptedException e) {
                // No errors need to be handled for this Future
                // This should never be reached
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void setPreviewUseCase() {
        if (cameraProvider == null) {
            return;
        }
        if (previewUseCase != null) {
            cameraProvider.unbind(previewUseCase);
        }

        Preview.Builder builder = new Preview.Builder();
        previewUseCase = builder.build();
        previewUseCase.setSurfaceProvider(binding.previewView.getSurfaceProvider());
    }

    private void setAnalysisUseCase() {
        if (cameraProvider == null) {
            return;
        }
        if (analysisUseCase != null) {
            cameraProvider.unbind(analysisUseCase);
        }
        if (imageProcessor != null) {
            imageProcessor.stop();
        }

        PoseDetectorOptionsBase poseDetectorOptions = PreferenceHelper.getPoseDetectorDefaultOptions();
        boolean shouldShowInFrameLikelihood = false;
        boolean visualizeZ = true;
        boolean rescaleZ = true;
        boolean runClassification = true;
        imageProcessor =
                new PoseDetectorProcessor(
                        this,
                        poseDetectorOptions,
                        shouldShowInFrameLikelihood,
                        visualizeZ,
                        rescaleZ,
                        runClassification,
                        true);

        ImageAnalysis.Builder builder = new ImageAnalysis.Builder();
        analysisUseCase = builder.build();

        needUpdateGraphicOverlayImageSourceInfo = true;
        analysisUseCase.setAnalyzer(
                // imageProcessor.processImageProxy will use another thread to run the detection underneath,
                // thus we can just runs the analyzer itself on main thread.
                ContextCompat.getMainExecutor(this),
                imageProxy -> {
                    if (needUpdateGraphicOverlayImageSourceInfo) {
                        boolean isImageFlipped = cameraSelector.getLensFacing() == CameraSelector.LENS_FACING_FRONT;
                        int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();
                        if (rotationDegrees == 0 || rotationDegrees == 180) {
                            binding.graphicOverlay.setImageSourceInfo(
                                    imageProxy.getWidth(), imageProxy.getHeight(), isImageFlipped);
                        } else {
                            binding.graphicOverlay.setImageSourceInfo(
                                    imageProxy.getHeight(), imageProxy.getWidth(), isImageFlipped);
                        }
                        needUpdateGraphicOverlayImageSourceInfo = false;
                    }

                    try {
                        imageProcessor.processImageProxy(imageProxy, binding.graphicOverlay);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to process image. Error: " + e.getLocalizedMessage());
                        Toast.makeText(getApplicationContext(), e.getLocalizedMessage(), Toast.LENGTH_SHORT)
                                .show();
                    }
                });
    }
}