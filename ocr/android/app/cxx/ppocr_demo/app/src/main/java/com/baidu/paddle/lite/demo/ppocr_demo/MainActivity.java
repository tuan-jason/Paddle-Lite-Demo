package com.baidu.paddle.lite.demo.ppocr_demo;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.FileProvider;
import androidx.core.content.ContextCompat;

import com.baidu.paddle.lite.demo.common.Utils;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import com.baidu.paddle.lite.demo.common.BitmapUtils;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_GALLERY = 1;

    PreviewView pvPreview;
    TextView tvStatus;
    ImageButton btnSwitch;
    ImageButton btnShutter;
    ImageButton btnGallery;

    // CameraX
    ImageCapture imageCapture;
    ExecutorService cameraExecutor;
    int lensFacing = CameraSelector.LENS_FACING_BACK;

    // Model settings
    protected String detModelPath = "ch_ppocr_mobile_v2.0_det_slim_opt.nb";
    protected String recModelPath = "ch_ppocr_mobile_v2.0_rec_slim_opt.nb";
    //protected String recModelPath = "japan_mobile_v2.0_rec_opt.nb";
    protected String clsModelPath = "ch_ppocr_mobile_v2.0_cls_slim_opt.nb";
    protected String labelPath = "ppocr_keys_v1.txt";
    //protected String labelPath = "japan_dict.txt";
    protected String configPath = "config.txt";
    protected int cpuThreadNum = 1;
    protected String cpuPowerMode = "LITE_POWER_HIGH";

    Native predictor = new Native();

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) getSupportActionBar().hide();
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.activity_main);
        initView();

        cameraExecutor = Executors.newSingleThreadExecutor();

        if (checkAllPermissions()) {
            startCamera();
        } else {
            requestAllPermissions();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkRun();
    }

    @Override
    protected void onDestroy() {
        if (predictor != null) predictor.release();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        super.onDestroy();
    }

    // ── Click handling ────────────────────────────────────────────────────────

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.btn_switch:
                switchCamera();
                break;
            case R.id.btn_shutter:
                Log.d(TAG, "btn Shutter clicked");
                takePicture();
                break;
            case R.id.btn_gallery:
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("image/*");
                startActivityForResult(Intent.createChooser(intent, "Select image"), REQUEST_GALLERY);
                break;
        }
    }

    // ── CameraX ──────────────────────────────────────────────────────────────

    private void startCamera() {
        Log.d(TAG, "startCamera");
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                bindCameraUseCases(future.get());
            } catch (Exception e) {
                Log.e(TAG, "Camera bind failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases(ProcessCameraProvider cameraProvider) {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(pvPreview.getSurfaceProvider());

        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build();

        CameraSelector selector = new CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build();

        cameraProvider.unbindAll();
        cameraProvider.bindToLifecycle(this, selector, preview, imageCapture);
    }

    private void switchCamera() {
        lensFacing = (lensFacing == CameraSelector.LENS_FACING_BACK)
                ? CameraSelector.LENS_FACING_FRONT
                : CameraSelector.LENS_FACING_BACK;
        startCamera();
    }

    // ── onTextureChanged (CameraSurfaceView live OCR) — disabled ─────────────
    // Replaced by CameraX; live OCR overlay removed (see architecture.md AD-3).
    /*
    @Override
    public boolean onTextureChanged(int inTextureId, int outTextureId, int textureWidth, int textureHeight) {
        String savedImagePath = new File(MainActivity.this.getExternalFilesDir(null), "japanese1.png").getAbsolutePath();
        boolean modified = predictor.process(inTextureId, outTextureId, textureWidth, textureHeight, savedImagePath);
        lastFrameIndex++;
        if (lastFrameIndex >= 30) {
            final int fps = (int) (lastFrameIndex * 1e9 / (System.nanoTime() - lastFrameTime));
            runOnUiThread(() -> tvStatus.setText(fps + "fps"));
            lastFrameIndex = 0;
            lastFrameTime = System.nanoTime();
        }
        return modified;
    }
    */

    // ── Capture ───────────────────────────────────────────────────────────────

    private void takePicture() {
        Log.d(TAG, "takePicture imageCapture: " + imageCapture);
        if (imageCapture == null) return;

        String timestamp = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US).format(new Date());
        final String finalPath = Utils.getDCIMDirectory() + File.separator + timestamp + ".png";
        File tempFile = new File(getCacheDir(), timestamp + "_tmp.jpg");

        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(tempFile).build();

        imageCapture.takePicture(options, cameraExecutor,
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults results) {
                        Bitmap raw = BitmapFactory.decodeFile(tempFile.getAbsolutePath());
                        if (raw == null) {
                            tempFile.delete();
                            return;
                        }
                        // Read EXIF *before* deleting the temp file, then apply rotation.
                        Bitmap oriented = applyExifRotation(raw, tempFile.getAbsolutePath());
                        tempFile.delete();
                        if (oriented != raw) raw.recycle();
                        Bitmap compressed = compressToMax(oriented, 1024, 1024);
                        if (compressed != oriented) oriented.recycle();
                        saveAsPng(compressed, finalPath);
                        runOnUiThread(() ->
                                Toast.makeText(MainActivity.this,
                                        "Saved to " + finalPath, Toast.LENGTH_SHORT).show());
                        Log.i(TAG, "onImageSaved to: " + finalPath);
                        uploadAndDraw(compressed, finalPath);
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException e) {
                        Log.e(TAG, "Capture failed", e);
                    }
                });
    }

    private Bitmap compressToMax(Bitmap src, int maxW, int maxH) {
        float scale = Math.min((float) maxW / src.getWidth(), (float) maxH / src.getHeight());
        if (scale >= 1f) return src;
        return Bitmap.createScaledBitmap(src,
                (int) (src.getWidth() * scale), (int) (src.getHeight() * scale), true);
    }

    private void saveAsPng(Bitmap bitmap, String path) {
        try {
            File f = new File(path);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            FileOutputStream fos = new FileOutputStream(f);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();
        } catch (Exception e) {
            Log.e(TAG, "saveAsPng failed", e);
        }
    }

    private static Bitmap applyExifRotation(Bitmap bitmap, String filePath) {
        int degrees = 0;
        try {
            ExifInterface exif = new ExifInterface(filePath);
            int orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:  degrees = 90;  break;
                case ExifInterface.ORIENTATION_ROTATE_180: degrees = 180; break;
                case ExifInterface.ORIENTATION_ROTATE_270: degrees = 270; break;
                default: break;
            }
        } catch (IOException e) {
            return bitmap;
        }
        if (degrees == 0) return bitmap;
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        return Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    // ── Upload & draw ─────────────────────────────────────────────────────────

    private void uploadAndDraw(final Bitmap bitmap, final String savedPath) {
        File file = new File(savedPath);
        RequestBody body = RequestBody.create(MediaType.parse("image/png"), file);
        MultipartBody.Part part = MultipartBody.Part.createFormData("file", file.getName(), body);

        Log.d(TAG, "Executing predict API on picked image");
        ApiClient.INSTANCE.getService().predict(part).enqueue(new Callback<DetectionResponse>() {
            @Override
            public void onResponse(@NonNull Call<DetectionResponse> call,
                                   @NonNull Response<DetectionResponse> response) {
                if (!response.isSuccessful() || response.body() == null) {
                    Log.e(TAG, "Predict response error: " + response.code());
                    return;
                }
                String outputPath = outputPathFor(savedPath);
                BoxRenderer.INSTANCE.drawAndSave(bitmap, response.body().getDetections(), outputPath);
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this,
                            "Output saved to " + outputPath, Toast.LENGTH_LONG).show();
                    openImageFullScreen(outputPath);
                });
            }

            @Override
            public void onFailure(@NonNull Call<DetectionResponse> call, @NonNull Throwable t) {
                Log.e(TAG, "Upload failed", t);
            }
        });
    }

    /** Inserts "_output" before the last '.' in the filename. */
    private String outputPathFor(String inputPath) {
        int dot = inputPath.lastIndexOf('.');
        return (dot < 0) ? inputPath + "_output"
                : inputPath.substring(0, dot) + "_output" + inputPath.substring(dot);
    }

    // ── View init ─────────────────────────────────────────────────────────────

    public void initView() {
        pvPreview = findViewById(R.id.pv_preview);
        tvStatus = findViewById(R.id.tv_status);
        btnSwitch = findViewById(R.id.btn_switch);
        btnSwitch.setOnClickListener(this);
        btnShutter = findViewById(R.id.btn_shutter);
        btnShutter.setOnClickListener(this);
        btnGallery = findViewById(R.id.btn_gallery);
        btnGallery.setOnClickListener(this);
    }

    // ── OCR model init (gallery path) ─────────────────────────────────────────

    public void checkRun() {
        try {
            Utils.copyAssets(this, labelPath);
            String labelRealDir = new File(this.getExternalFilesDir(null), labelPath).getAbsolutePath();

            Utils.copyAssets(this, configPath);
            String configRealDir = new File(this.getExternalFilesDir(null), configPath).getAbsolutePath();

            Utils.copyAssets(this, "japanese1.png");

            Utils.copyAssets(this, detModelPath);
            String detRealModelDir = new File(this.getExternalFilesDir(null), detModelPath).getAbsolutePath();

            Utils.copyAssets(this, clsModelPath);
            String clsRealModelDir = new File(this.getExternalFilesDir(null), clsModelPath).getAbsolutePath();

            Utils.copyAssets(this, recModelPath);
            String recRealModelDir = new File(this.getExternalFilesDir(null), recModelPath).getAbsolutePath();

            predictor.init(this, detRealModelDir, clsRealModelDir, recRealModelDir,
                    configRealDir, labelRealDir, cpuThreadNum, cpuPowerMode);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    // ── Gallery OCR (Phase 1 + 2) — unchanged ────────────────────────────────

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_GALLERY || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            final Bitmap bitmap = BitmapUtils.decodeBitmapWithOrientation(getContentResolver(), uri);
            if (bitmap == null) return;
            final String outPath = Utils.getDCIMDirectory() + File.separator
                    + new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss").format(new Date()) + "_ocr.jpg";
            ExecutorService executor = Executors.newSingleThreadExecutor();
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        long startTs = SystemClock.elapsedRealtime();
                        String json = predictor.processBitmap(bitmap, outPath);
                        final OcrResult[] results = OcrResultParser.parse(json);
                        Log.v("tuancoltech", "results.size: " + results.length);
                        for (int i = 0; i < results.length; i++) {
                            Log.d("tuancoltech", "line " + i + ": " + results[i].text + " (" + results[i].score + ") "
                                    + formatOcrResultBox(results[i]));
                        }

                        // Phase 2: detect question areas and render boxes
                        List<QuestionRegion> regions =
                                new SignalFusionQuestionDetector().detect(results, bitmap);
                        Log.v(TAG, "question regions: " + regions.size());
                        for (QuestionRegion region : regions) {
                            Log.d(TAG, "Q" + (region.getIndex() + 1)
                                    + " bounds: " + region.getBounds()
                                    + " confidence: " + region.getConfidence());
                        }

                        if (regions.isEmpty()) {
                            runOnUiThread(() -> Toast.makeText(MainActivity.this, "No question found!", Toast.LENGTH_LONG).show());
                            return;
                        }
                        long detectionTime = SystemClock.elapsedRealtime() - startTs;
                        QuestionBoxRenderer.INSTANCE.render(bitmap, regions, outPath, detectionTime);
                        Log.v(TAG, "outputPath: " + outPath + "\nExisting: " + (new File(outPath).exists()) + "\nSize: " + (new File(outPath).exists()) + " bytes");

                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "Result saved to " + outPath, Toast.LENGTH_LONG).show();
                            openImageFullScreen(outPath);
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "Gallery OCR failed on background thread", e);
                    }
                }
            });
            executor.shutdown();
        } catch (Exception e) {
            Log.e(TAG, "Gallery OCR failed", e);
        }
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean cameraGranted = false;
        boolean storageGranted = Build.VERSION.SDK_INT > Build.VERSION_CODES.Q;

        for (int i = 0; i < permissions.length && i < grantResults.length; i++) {
            if (Manifest.permission.CAMERA.equals(permissions[i])) {
                cameraGranted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
            } else if (Manifest.permission.WRITE_EXTERNAL_STORAGE.equals(permissions[i])) {
                storageGranted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
            }
        }

        if (cameraGranted && storageGranted) {
            startCamera();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("Permission denied")
                    .setMessage("Click to force quit the app, then open Settings->Apps & " +
                            "notifications->Target App->Permissions to grant all permissions.")
                    .setCancelable(false)
                    .setPositiveButton("Exit", (dialog, which) -> finish())
                    .show();
        }
    }

    private void requestAllPermissions() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.CAMERA}, 0);
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, 0);
        }
    }

    private boolean checkAllPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void openImageFullScreen(String path) {
        try {
            Uri uri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    new File(path));
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "image/png");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "No image viewer found", e);
            Toast.makeText(this, "No image viewer app found on this device", Toast.LENGTH_SHORT).show();
        }
    }

    private String formatOcrResultBox(OcrResult result) {
        if (result == null || result.box == null || result.box.length < 4) {
            return "TL: (N/A,N/A) TR: (N/A,N/A) BR: (N/A,N/A), BL: (N/A,N/A)";
        }
        return "TL: " + formatCorner(result.box[0])
                + " TR: " + formatCorner(result.box[1])
                + " BR: " + formatCorner(result.box[2])
                + ", BL: " + formatCorner(result.box[3]);
    }

    private String formatCorner(int[] point) {
        if (point == null || point.length < 2) return "(N/A,N/A)";
        return "(" + point[0] + "," + point[1] + ")";
    }
}
