/*
 * Copyright 2020 Google LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.pushupdetector.posedetector.classification;

import static com.example.pushupdetector.posedetector.classification.Utils.average;
import static com.example.pushupdetector.posedetector.classification.Utils.l2Norm2D;
import static com.example.pushupdetector.posedetector.classification.Utils.multiply;
import static com.example.pushupdetector.posedetector.classification.Utils.multiplyAll;
import static com.example.pushupdetector.posedetector.classification.Utils.subtract;
import static com.example.pushupdetector.posedetector.classification.Utils.subtractAll;

import android.util.Log;

import com.google.mlkit.vision.common.PointF3D;
import com.google.mlkit.vision.pose.PoseLandmark;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Generates embedding for given list of Pose landmarks.
 */
public class PoseEmbedding {
    // Multiplier to apply to the torso to get minimal body size. Picked this by experimentation.
    private static final float TORSO_MULTIPLIER = 2.5f;
    private static final float Y_THRESHOLD = 2.5f;

    public static List<PointF3D> getPoseEmbedding(List<PointF3D> landmarks) {
        List<PointF3D> normalizedLandmarks = normalize(landmarks);
        return getEmbedding(normalizedLandmarks);
    }

    private static double getMagnitude(PointF3D point) {
        return Math.sqrt(Math.pow(point.getX(), 2) + Math.pow(point.getY(), 2) + Math.pow(point.getZ(), 2));
    }

    public static double[][] rotationMatrix(double[] axis, double angle) {
        // compute the rotation matrix for a given axis and angle
        double[] finalAxis = axis;
        axis = Arrays.stream(axis).map(a -> a / Math.sqrt(Arrays.stream(finalAxis).map(x -> x * x).sum())).toArray();
        double a = Math.cos(angle / 2.0);
        double[] bcd = Arrays.stream(axis).map(a1 -> -a1 * Math.sin(angle / 2.0)).toArray();
        double aa = a * a, bb = bcd[0] * bcd[0], cc = bcd[1] * bcd[1], dd = bcd[2] * bcd[2];
        double bc = bcd[0] * bcd[1], ad = a * bcd[2], ac = a * bcd[1], ab = a * bcd[0], bd = bcd[0] * bcd[2], cd = bcd[1] * bcd[2];
        return new double[][]{{aa + bb - cc - dd, 2 * (bc + ad), 2 * (bd - ac)},
                {2 * (bc - ad), aa + cc - bb - dd, 2 * (cd + ab)},
                {2 * (bd + ac), 2 * (cd - ab), aa + dd - bb - cc}};
    }

    private static PointF3D cross(PointF3D A, PointF3D B) {
        float x = A.getY() * B.getZ()
                - A.getZ() * B.getY();
        float y = A.getZ() * B.getX()
                - A.getX() * B.getZ();
        float z = A.getX() * B.getY()
                - A.getY() * B.getX();

        return PointF3D.from(x, y, z);
    }

    private static float dot(PointF3D A, PointF3D B) {
        return A.getX() * B.getX() + A.getY() * B.getY() + A.getZ() * B.getZ();
    }

    private static float dot(PointF3D A, double x, double y, double z) {
      return (float) (A.getX() * x + A.getY() * y + A.getZ() * z);
    }

    private static List<PointF3D> rotateAtoBwithLandmark(PointF3D B, PointF3D A, List<PointF3D> landmarks) {
        PointF3D A_unit = multiply(A, (float) (1 / getMagnitude(A)));
        PointF3D B_unit = multiply(B, (float) (1 / getMagnitude(B)));

        PointF3D axis = cross(B_unit, A_unit);
        float angle = (float) Math.acos(dot(B_unit, A_unit));

        List<PointF3D> landmarkResult = new ArrayList<>();

        for (PointF3D lm : landmarks) {
          double[] axisR = {axis.getX(), axis.getY(), axis.getZ()};
          double[][] R = rotationMatrix(axisR, angle);

          float[] coords = new float[3];

          for (int i = 0; i < 3; i++) {
            coords[i] = dot(lm, R[i][0], R[i][1], R[i][2]);
          }

          landmarkResult.add(PointF3D.from(coords[0], coords[1], coords[2]));
        }

        return landmarkResult;
    }

    private static List<PointF3D> normalize(List<PointF3D> landmarks) {
        List<PointF3D> normalizedLandmarks = new ArrayList<>(landmarks);

        // Normalize scale.
        multiplyAll(normalizedLandmarks, 1 / getPoseSize(normalizedLandmarks));

        // [STEP 0]: Check whether the body is oriented Vertically or Horizontally
        PointF3D LH_CHECK = normalizedLandmarks.get(PoseLandmark.LEFT_HIP);
        PointF3D LS_CHECK = normalizedLandmarks.get(PoseLandmark.LEFT_SHOULDER);

        // Assume it's oriented Vertically
        if (Math.abs(LS_CHECK.getY() - LH_CHECK.getY()) < Y_THRESHOLD ) return normalizedLandmarks;

        // [STEP 1]: RH to (0, 0, 0)
        PointF3D RH = normalizedLandmarks.get(PoseLandmark.RIGHT_HIP);
        subtractAll(RH, normalizedLandmarks);

        // [STEP 2]: LH to (|LH|, 0, 0)
        PointF3D LH_NEW = normalizedLandmarks.get(PoseLandmark.LEFT_HIP);
        PointF3D LH_DESIRED = PointF3D.from((float) getMagnitude(LH_NEW), 0, 0);

        if (LH_NEW.getX() != LH_DESIRED.getX() ||
                LH_NEW.getY() != LH_DESIRED.getY() ||
                LH_NEW.getZ() != LH_DESIRED.getZ()
        ) {
          normalizedLandmarks = rotateAtoBwithLandmark(LH_NEW, LH_DESIRED, normalizedLandmarks);
        }

        // [STEP 3] LS to (+, 0, ?)
        PointF3D LS = normalizedLandmarks.get(PoseLandmark.LEFT_SHOULDER);

        if (LS.getY() != 0) {
          float LS_DESIRED_Z = (float) Math.sqrt(Math.pow(LS.getY(), 2) + Math.pow(LS.getZ(), 2));
          PointF3D LS_DESIRED = PointF3D.from(LS.getX(), 0, LS_DESIRED_Z);

          normalizedLandmarks = rotateAtoBwithLandmark(LS, LS_DESIRED, normalizedLandmarks);
        }

        Log.d("LMARK Left Shoulder: ", normalizedLandmarks.get(PoseLandmark.LEFT_SHOULDER).toString());
        Log.d("LMARK Left Hip: ", normalizedLandmarks.get(PoseLandmark.LEFT_HIP).toString());
        Log.d("LMARK Right Hip: ", normalizedLandmarks.get(PoseLandmark.RIGHT_HIP).toString());

        return normalizedLandmarks;
    }

    // Translation normalization should've been done prior to calling this method.
    private static float getPoseSize(List<PointF3D> landmarks) {
        // Note: This approach uses only 2D landmarks to compute pose size as using Z wasn't helpful
        // in our experimentation but you're welcome to tweak.
        PointF3D hipsCenter = average(
                landmarks.get(PoseLandmark.LEFT_HIP), landmarks.get(PoseLandmark.RIGHT_HIP));

        PointF3D shouldersCenter = average(
                landmarks.get(PoseLandmark.LEFT_SHOULDER),
                landmarks.get(PoseLandmark.RIGHT_SHOULDER));

        float torsoSize = l2Norm2D(subtract(hipsCenter, shouldersCenter));

        float maxDistance = torsoSize * TORSO_MULTIPLIER;
        // torsoSize * TORSO_MULTIPLIER is the floor we want based on experimentation but actual size
        // can be bigger for a given pose depending on extension of limbs etc so we calculate that.
        for (PointF3D landmark : landmarks) {
            float distance = l2Norm2D(subtract(hipsCenter, landmark));
            if (distance > maxDistance) {
                maxDistance = distance;
            }
        }
        return maxDistance;
    }

    private static List<PointF3D> getEmbedding(List<PointF3D> lm) {
        List<PointF3D> embedding = new ArrayList<>();

        // We use several pairwise 3D distances to form pose embedding. These were selected
        // based on experimentation for best results with our default pose classes as captued in the
        // pose samples csv. Feel free to play with this and add or remove for your use-cases.

        // We group our distances by number of joints between the pairs.
        // One joint.
        embedding.add(subtract(
                average(lm.get(PoseLandmark.LEFT_HIP), lm.get(PoseLandmark.RIGHT_HIP)),
                average(lm.get(PoseLandmark.LEFT_SHOULDER), lm.get(PoseLandmark.RIGHT_SHOULDER))
        ));

        embedding.add(subtract(
                lm.get(PoseLandmark.LEFT_SHOULDER), lm.get(PoseLandmark.LEFT_ELBOW)));
        embedding.add(subtract(
                lm.get(PoseLandmark.RIGHT_SHOULDER), lm.get(PoseLandmark.RIGHT_ELBOW)));

        embedding.add(subtract(lm.get(PoseLandmark.LEFT_ELBOW), lm.get(PoseLandmark.LEFT_WRIST)));
        embedding.add(subtract(lm.get(PoseLandmark.RIGHT_ELBOW), lm.get(PoseLandmark.RIGHT_WRIST)));

        embedding.add(subtract(lm.get(PoseLandmark.LEFT_HIP), lm.get(PoseLandmark.LEFT_KNEE)));
        embedding.add(subtract(lm.get(PoseLandmark.RIGHT_HIP), lm.get(PoseLandmark.RIGHT_KNEE)));

        embedding.add(subtract(lm.get(PoseLandmark.LEFT_KNEE), lm.get(PoseLandmark.LEFT_ANKLE)));
        embedding.add(subtract(lm.get(PoseLandmark.RIGHT_KNEE), lm.get(PoseLandmark.RIGHT_ANKLE)));

        // Two joints.
        embedding.add(subtract(
                lm.get(PoseLandmark.LEFT_SHOULDER), lm.get(PoseLandmark.LEFT_WRIST)));
        embedding.add(subtract(
                lm.get(PoseLandmark.RIGHT_SHOULDER), lm.get(PoseLandmark.RIGHT_WRIST)));

        embedding.add(subtract(lm.get(PoseLandmark.LEFT_HIP), lm.get(PoseLandmark.LEFT_ANKLE)));
        embedding.add(subtract(lm.get(PoseLandmark.RIGHT_HIP), lm.get(PoseLandmark.RIGHT_ANKLE)));

        // Four joints.
        embedding.add(subtract(lm.get(PoseLandmark.LEFT_HIP), lm.get(PoseLandmark.LEFT_WRIST)));
        embedding.add(subtract(lm.get(PoseLandmark.RIGHT_HIP), lm.get(PoseLandmark.RIGHT_WRIST)));

        // Five joints.
        embedding.add(subtract(
                lm.get(PoseLandmark.LEFT_SHOULDER), lm.get(PoseLandmark.LEFT_ANKLE)));
        embedding.add(subtract(
                lm.get(PoseLandmark.RIGHT_SHOULDER), lm.get(PoseLandmark.RIGHT_ANKLE)));

        embedding.add(subtract(lm.get(PoseLandmark.LEFT_HIP), lm.get(PoseLandmark.LEFT_WRIST)));
        embedding.add(subtract(lm.get(PoseLandmark.RIGHT_HIP), lm.get(PoseLandmark.RIGHT_WRIST)));

        // Cross body.
        embedding.add(subtract(lm.get(PoseLandmark.LEFT_ELBOW), lm.get(PoseLandmark.RIGHT_ELBOW)));
        embedding.add(subtract(lm.get(PoseLandmark.LEFT_KNEE), lm.get(PoseLandmark.RIGHT_KNEE)));

        embedding.add(subtract(lm.get(PoseLandmark.LEFT_WRIST), lm.get(PoseLandmark.RIGHT_WRIST)));
        embedding.add(subtract(lm.get(PoseLandmark.LEFT_ANKLE), lm.get(PoseLandmark.RIGHT_ANKLE)));

        return embedding;
    }

    private PoseEmbedding() {
    }
}
