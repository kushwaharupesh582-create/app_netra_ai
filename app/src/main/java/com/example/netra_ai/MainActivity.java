package com.example.netra_ai;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.telephony.SmsManager;
import android.util.Log;
import android.util.Size;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.content.Intent;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.ImageLabel;
import com.google.mlkit.vision.label.ImageLabeler;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;
import com.google.mlkit.vision.objects.DetectedObject;
import com.google.mlkit.vision.objects.ObjectDetection;
import com.google.mlkit.vision.objects.ObjectDetector;
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions;

import org.json.JSONObject;

import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.socket.client.IO;
import io.socket.client.Socket;

public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final String TAG = "NETRA_AI";
    private static final String EMERGENCY_CONTACT = "+919876543210"; // TODO: Update with real number

    private PreviewView viewFinder;
    private TextView tvStatus, tvWarning;
    private TextToSpeech tts;
    private ObjectDetector objectDetector;
    private ImageLabeler imageLabeler;
    private FusedLocationProviderClient fusedLocationClient;
    private Socket mSocket;
    private Location currentLocation;
    private long lastWarningTime = 0;
    private String lastWarningPosition = ""; // Track what we said last time
    private ExecutorService cameraExecutor;

    // Custom Background Lifecycle to keep CameraX running forever
    private LifecycleRegistry backgroundLifecycleRegistry;
    private final LifecycleOwner backgroundLifecycleOwner = new LifecycleOwner() {
        @NonNull
        @Override
        public Lifecycle getLifecycle() {
            return backgroundLifecycleRegistry;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // Start our custom Background Lifecycle
        backgroundLifecycleRegistry = new LifecycleRegistry(backgroundLifecycleOwner);
        backgroundLifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);

        viewFinder = findViewById(R.id.viewFinder);
        tvStatus = findViewById(R.id.tvStatus);
        tvWarning = findViewById(R.id.tvWarning);
        Button btnSOS = findViewById(R.id.btnSOS);

        // 1. Initialize TTS (Text-To-Speech)
        tts = new TextToSpeech(this, this);

        // 2. Initialize ML Kit Object Detector
        ObjectDetectorOptions options = new ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                .enableClassification() // Helps identify what the object is
                .build();
        objectDetector = ObjectDetection.getClient(options);
        
        // 2.1 Initialize Advanced Image Labeler (For Vehicles, People, Water, Trash, etc)
        imageLabeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS);

        // 3. Initialize Location Client
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        // 4. Initialize Socket.IO connection
        initSocketConnection();

        // 5. Setup SOS Button
        btnSOS.setOnClickListener(v -> triggerSOS());

        // 6. Initialize Background Executor for Camera
        cameraExecutor = Executors.newSingleThreadExecutor();

        checkPermissions();
    }

    private void checkPermissions() {
        String[] permissions = {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.SEND_SMS
        };

        boolean allGranted = true;
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (!allGranted) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
        } else {
            startSystem();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int res : grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                startSystem();
            } else {
                Toast.makeText(this, "Permissions are required for App to work.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startSystem() {
        tvStatus.setText("System Active");
        
        // Start Foreground Service so Android doesn't kill the app in background
        Intent serviceIntent = new Intent(this, KeepAliveService.class);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        startCamera();
        startLocationTracking();
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                // Image Analysis for Object Detection (Ultra-low latency configuration)
                ResolutionSelector resolutionSelector = new ResolutionSelector.Builder()
                        .setResolutionStrategy(new ResolutionStrategy(new android.util.Size(480, 360), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER))
                        .build();

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
                        // Fastest processing, drops all old frames instantly if one is being processed
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setImageQueueDepth(1) // Only keep 1 frame in memory at a time
                        .build();

                // Run image processing on background thread, NOT main thread!
                imageAnalysis.setAnalyzer(cameraExecutor, this::processImageProxy);

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.unbindAll();
                
                // Binding to backgroundLifecycleOwner with both Preview and ImageAnalysis
                cameraProvider.bindToLifecycle(backgroundLifecycleOwner, cameraSelector, preview, imageAnalysis);

                speakOut("Vision Engine Active. Scanning surroundings.");
            } catch (Exception exc) {
                Log.e(TAG, "Camera bind failed", exc);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void processImageProxy(ImageProxy imageProxy) {
        if (imageProxy.getImage() == null) return;

        InputImage image = InputImage.fromMediaImage(imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());

        // Fast Single-Pipeline: Pure Bounding Box Object Detection for 3-Zone Navigation
        objectDetector.process(image)
                .addOnSuccessListener(detectedObjects -> {
                    processDetectedObjects(detectedObjects, imageProxy.getWidth(), imageProxy.getHeight());
                })
                .addOnFailureListener(e -> Log.e(TAG, "Object detection failed", e))
                .addOnCompleteListener(task -> imageProxy.close());
    }

    private void processDetectedObjects(List<DetectedObject> detectedObjects, int imageWidth, int imageHeight) {
        OverlayView overlayView = findViewById(R.id.overlayView);

        if (detectedObjects == null || detectedObjects.isEmpty()) {
            if (overlayView != null) {
                overlayView.setResults(null, imageWidth, imageHeight);
            }
            runOnUiThread(() -> {
                tvWarning.setText("Clear Path Ahead");
                tvWarning.setTextColor(0xFF00FF00); // Green
            });
            lastWarningPosition = ""; 
            return;
        }

        // Draw Real-time Bounding Boxes on Overlay
        if (overlayView != null) {
            overlayView.setResults(detectedObjects, imageWidth, imageHeight);
        }

        // 1. Find the largest / most prominent obstacle in the frame (Closest to user)
        DetectedObject primaryObject = null;
        int maxArea = 0;

        for (DetectedObject obj : detectedObjects) {
            int area = obj.getBoundingBox().width() * obj.getBoundingBox().height();
            if (area > maxArea) {
                maxArea = area;
                primaryObject = obj;
            }
        }

        if (primaryObject == null) return;

        // 2. Identify Object Name (Recognized Object vs. Fallback "Obstacle")
        String objectName = "Obstacle";
        if (!primaryObject.getLabels().isEmpty()) {
            String label = primaryObject.getLabels().get(0).getText().toLowerCase();
            if (label.contains("vehicle") || label.contains("car") || label.contains("bus") || label.contains("bike")) {
                objectName = "Vehicle";
            } else if (label.contains("person") || label.contains("human") || label.contains("man") || label.contains("woman")) {
                objectName = "Person";
            } else if (label.contains("home") || label.contains("furniture") || label.contains("chair") || label.contains("table")) {
                objectName = "Furniture";
            } else if (label.contains("plant")) {
                objectName = "Plant";
            } else if (label.contains("food")) {
                objectName = "Food Item";
            }
        }

        // 3. 2D Spatial Grid: Horizontal Positioning (Left / Center / Right)
        int centerX = primaryObject.getBoundingBox().centerX();
        String horizPos;
        float pan = 0.0f; // Spatial Audio Pan (-1.0 = Left, 0.0 = Center, 1.0 = Right)

        if (centerX < imageWidth * 0.30) {
            horizPos = "Left";
            pan = -1.0f; // Dolby Atmos Hard Left
        } else if (centerX > imageWidth * 0.70) {
            horizPos = "Right";
            pan = 1.0f; // Dolby Atmos Hard Right
        } else {
            horizPos = "Ahead";
            pan = 0.0f; // Dolby Atmos Center
        }

        // 4. 2D Spatial Grid: Vertical Positioning (Top / Center / Bottom)
        int centerY = primaryObject.getBoundingBox().centerY();
        String vertPos = "";

        if (centerY < imageHeight * 0.30) {
            vertPos = "Top";
        } else if (centerY > imageHeight * 0.70) {
            vertPos = "Bottom";
        }

        // 5. Build Natural Spoken Warning & Display Text for 4 Directions
        String warningSpeech;
        String displayText;

        if (vertPos.equals("Top")) {
            warningSpeech = horizPos.equals("Ahead") ? objectName + " Above directly Ahead" : objectName + " Above on your " + horizPos;
            displayText = objectName + " Top " + horizPos;
        } else if (vertPos.equals("Bottom")) {
            warningSpeech = horizPos.equals("Ahead") ? objectName + " Below directly Ahead" : objectName + " Below on your " + horizPos;
            displayText = objectName + " Bottom " + horizPos;
        } else {
            warningSpeech = horizPos.equals("Ahead") ? objectName + " directly Ahead" : objectName + " on your " + horizPos;
            displayText = objectName + " " + horizPos;
        }

        // 6. Anti-Jitter Cooldown check (2.5 seconds minimum gap to prevent voice stuttering)
        long timeSinceLastWarning = System.currentTimeMillis() - lastWarningTime;
        boolean stateChanged = !displayText.equals(lastWarningPosition);

        boolean shouldSpeak;
        if (timeSinceLastWarning < 2500) {
            shouldSpeak = false;
        } else {
            shouldSpeak = stateChanged || (timeSinceLastWarning > 4500);
        }

        if (shouldSpeak) {
            final String finalDisplayText = displayText;
            runOnUiThread(() -> {
                tvWarning.setText(finalDisplayText);
                tvWarning.setTextColor(0xFFFF0000); // Red
            });

            speakOut(warningSpeech, pan);
            lastWarningPosition = displayText;
            lastWarningTime = System.currentTimeMillis();

            // Sync Hazard Location to Node.js Backend & Guardians
            if (mSocket != null && mSocket.connected() && currentLocation != null) {
                try {
                    JSONObject hazardData = new JSONObject();
                    hazardData.put("lat", currentLocation.getLatitude());
                    hazardData.put("lng", currentLocation.getLongitude());
                    hazardData.put("type", displayText);
                    hazardData.put("position", displayText);
                    mSocket.emit("hazard_detected", hazardData);
                } catch (Exception e) {
                    Log.e(TAG, "Socket emission failed", e);
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void startLocationTracking() {
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
                .setMinUpdateIntervalMillis(2000)
                .build();

        fusedLocationClient.requestLocationUpdates(locationRequest, new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                super.onLocationResult(locationResult);
                currentLocation = locationResult.getLastLocation();
                
                // Send live GPS data to Node.js / Socket.io Backend
                if (mSocket != null && mSocket.connected() && currentLocation != null) {
                    try {
                        JSONObject locationData = new JSONObject();
                        locationData.put("lat", currentLocation.getLatitude());
                        locationData.put("lng", currentLocation.getLongitude());
                        mSocket.emit("live_location", locationData);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }, Looper.getMainLooper());
    }

    private void initSocketConnection() {
        try {
            // Updated with your laptop's Local WiFi IP Address
            mSocket = IO.socket("http://172.15.43.233:3000"); 
            mSocket.connect();
            mSocket.on(Socket.EVENT_CONNECT, args -> Log.d(TAG, "Socket connected to Node.js backend"));
            
            // Listen for Crowd-sourced Hazard Warnings from backend
            mSocket.on("community_hazard_warning", args -> {
                if (args.length > 0) {
                    String warningMsg = args[0].toString();
                    runOnUiThread(() -> speakOut("Community Alert: " + warningMsg));
                }
            });
            
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
    }

    private void triggerSOS() {
        speakOut("SOS Activated. Notifying family.");
        tvWarning.setText("SOS Sent!");
        tvWarning.setTextColor(0xFFFF0000); // Red

        String googleMapsLink = "Unknown location";
        if (currentLocation != null) {
            googleMapsLink = "http://maps.google.com/?q=" + currentLocation.getLatitude() + "," + currentLocation.getLongitude();
        }

        String smsBody = "EMERGENCY! I need help. My current location is: " + googleMapsLink;

        // 1. ALWAYS emit to Socket.io Server (Works over WiFi without SIM card!)
        if (mSocket != null) {
            mSocket.emit("panic_sos", smsBody);
            Log.d(TAG, "SOS Emitted via Socket.io");
            Toast.makeText(this, "SOS Alert Sent to Server!", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Server not connected!", Toast.LENGTH_SHORT).show();
        }

        // 2. Try sending SMS separately (if SIM card is available)
        try {
            SmsManager smsManager = SmsManager.getDefault();
            smsManager.sendTextMessage(EMERGENCY_CONTACT, null, smsBody, null, null);
            Toast.makeText(this, "SMS Sent to Family", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "SMS sending failed (Normal if device has no SIM): " + e.getMessage());
        }
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int result = tts.setLanguage(Locale.US); // Use Local language if needed
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "TTS Language not supported");
            }
        }
    }

    private void speakOut(String text) {
        speakOut(text, 0.0f); // Default to center if no direction provided
    }

    private void speakOut(String text, float pan) {
        if (tts != null) {
            Bundle params = new Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, pan); // This creates the Dolby Atmos Spatial Audio Effect
            // Use QUEUE_FLUSH instead of QUEUE_ADD to instantly cut off old text and say the new one (Zero delay!)
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "NETRA_TTS");
        }
    }

    @Override
    protected void onDestroy() {
        // Destroy our custom lifecycle
        if (backgroundLifecycleRegistry != null) {
            backgroundLifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
        }
        // Stop foreground service
        stopService(new Intent(this, KeepAliveService.class));
        
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (mSocket != null) {
            mSocket.disconnect();
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        super.onDestroy();
    }
}