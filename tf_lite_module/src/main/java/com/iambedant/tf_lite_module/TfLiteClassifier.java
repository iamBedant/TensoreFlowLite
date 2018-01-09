package com.iambedant.tf_lite_module;

import android.app.Activity;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Created by @iamBedant on 09/01/18.
 */

public class TfLiteClassifier {

    public static int DIM_IMG_SIZE_X;
    public static int DIM_IMG_SIZE_Y;

    private static String TAG;
    /**
     * Name of the model file stored in Assets.
     */
    private static String MODEL_PATH;

    /**
     * Name of the label file stored in Assets.
     */
    private static String LABEL_PATH = "labels.txt";

    /**
     * Number of results to show in the UI.
     */
    private static int RESULTS_TO_SHOW;
    /**
     * Dimensions of inputs.
     */
    private static int DIM_BATCH_SIZE = 1;
    private static int DIM_PIXEL_SIZE;
    /* Preallocated buffers for storing image data in. */
    private int[] intValues ;

    /**
     * An instance of the driver class to run model inference with Tensorflow Lite.
     */
    private Interpreter tflite;

    /**
     * Labels corresponding to the output of the vision model.
     */
    private List<String> labelList;

    /**
     * A ByteBuffer to hold image data, to be feed into Tensorflow Lite as inputs.
     */
    private ByteBuffer imgData = null;

    /**
     * An array to hold inference results, to be feed into Tensorflow Lite as outputs.
     */
    private byte[][] labelProbArray = null;

    private PriorityQueue<Map.Entry<String, Float>> sortedLabels;


    public TfLiteClassifier(TfLiteClassifierBuilder builder) {
        this.DIM_IMG_SIZE_X = builder.DIM_IMG_SIZE_X;
        this.DIM_IMG_SIZE_Y = builder.DIM_IMG_SIZE_Y;
        this.MODEL_PATH = builder.MODEL_PATH;
        this.LABEL_PATH = builder.LABEL_PATH;
        this.RESULTS_TO_SHOW = builder.RESULTS_TO_SHOW;
        this.DIM_PIXEL_SIZE = builder.DIM_PIXEL_SIZE;
        try {
            initialiseData(builder.context);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void initialiseData(Activity activity) throws IOException {
        intValues = new int[DIM_IMG_SIZE_X * DIM_IMG_SIZE_Y];
        sortedLabels = new PriorityQueue<>(
                RESULTS_TO_SHOW,
                new Comparator<Map.Entry<String, Float>>() {
                    @Override
                    public int compare(Map.Entry<String, Float> o1, Map.Entry<String, Float> o2) {
                        return (o1.getValue()).compareTo(o2.getValue());
                    }
                });
        tflite = new Interpreter(loadModelFile(activity));
        labelList = loadLabelList(activity);
        imgData =
                ByteBuffer.allocateDirect(
                        DIM_BATCH_SIZE * DIM_IMG_SIZE_X * DIM_IMG_SIZE_Y * DIM_PIXEL_SIZE);
        imgData.order(ByteOrder.nativeOrder());
        labelProbArray = new byte[1][labelList.size()];



    }

    /**
     * Reads label list from Assets.
     */
    private List<String> loadLabelList(Activity activity) throws IOException {
        List<String> labelList = new ArrayList<String>();
        BufferedReader reader =
                new BufferedReader(new InputStreamReader(activity.getAssets().open(LABEL_PATH)));
        String line;
        while ((line = reader.readLine()) != null) {
            labelList.add(line);
        }
        reader.close();
        return labelList;
    }


    /**
     * Memory-map the model file in Assets.
     */
    private MappedByteBuffer loadModelFile(Activity activity) throws IOException {
        AssetFileDescriptor fileDescriptor = activity.getAssets().openFd(MODEL_PATH);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    /**
     * Writes Image data into a {@code ByteBuffer}.
     */
    private void convertBitmapToByteBuffer(Bitmap bitmap) {
        if (imgData == null) {
            return;
        }
        imgData.rewind();
        bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        // Convert the image to floating point.
        int pixel = 0;
        long startTime = SystemClock.uptimeMillis();
        for (int i = 0; i < DIM_IMG_SIZE_X; ++i) {
            for (int j = 0; j < DIM_IMG_SIZE_Y; ++j) {
                final int val = intValues[pixel++];
                imgData.put((byte) ((val >> 16) & 0xFF));
                imgData.put((byte) ((val >> 8) & 0xFF));
                imgData.put((byte) (val & 0xFF));
            }
        }
        long endTime = SystemClock.uptimeMillis();
        Log.d(TAG, "Timecost to put values into ByteBuffer: " + Long.toString(endTime - startTime));
    }

    public List<Predictions> classifyFrameToList(Bitmap bitmap) {
        if (tflite == null) {
            Log.e(TAG, "Image classifier has not been initialized; Skipped.");
            return new ArrayList<>();
        }
        convertBitmapToByteBuffer(bitmap);
        // Here's where the magic happens!!!
        long startTime = SystemClock.uptimeMillis();
        tflite.run(imgData, labelProbArray);
        long endTime = SystemClock.uptimeMillis();


        Log.d(TAG, "Timecost to run model inference: " + Long.toString(endTime - startTime));
        return returnTopKLabels();
    }


    private List<Predictions> returnTopKLabels() {

        List<Predictions> list = new ArrayList<>();


        for (int i = 0; i < labelList.size(); ++i) {
            sortedLabels.add(
                    new AbstractMap.SimpleEntry<>(labelList.get(i), (labelProbArray[0][i] & 0xff) / 255.0f));
            if (sortedLabels.size() > RESULTS_TO_SHOW) {
                sortedLabels.poll();
            }
        }
        final int size = sortedLabels.size();
        for (int i = 0; i < size; ++i) {
            Map.Entry<String, Float> label = sortedLabels.poll();
            list.add(new Predictions(label.getKey(), String.format("%.1f", label.getValue() * 100)));
        }
        return list;
    }

    public void close() {
        tflite.close();
        tflite = null;
    }

    public static class TfLiteClassifierBuilder {
        public int DIM_IMG_SIZE_X = 224;
        public int DIM_IMG_SIZE_Y = 224;

        private String MODEL_PATH;
        private String LABEL_PATH;
        private int RESULTS_TO_SHOW = 3;
        private int DIM_PIXEL_SIZE = 3;
        private Activity context;

        public TfLiteClassifierBuilder(Activity context) {
            this.context = context;
        }

        public TfLiteClassifierBuilder setImageDimention(int dimention) {
            this.DIM_IMG_SIZE_X = dimention;
            this.DIM_IMG_SIZE_Y = dimention;
            return this;
        }

        public TfLiteClassifierBuilder setModelPath(String modelPath) {
            this.MODEL_PATH = modelPath;
            return this;
        }

        public TfLiteClassifierBuilder setLabelPath(String labelPath) {
            this.LABEL_PATH = labelPath;
            return this;
        }

        public TfLiteClassifierBuilder setResultToshow(int resultCount) {
            this.RESULTS_TO_SHOW = resultCount;
            return this;
        }

        public TfLiteClassifierBuilder setColorChannel(int channel) {
            this.DIM_PIXEL_SIZE = channel;
            return this;
        }

        public TfLiteClassifier build() {
            return new TfLiteClassifier(this);
        }

    }
}
