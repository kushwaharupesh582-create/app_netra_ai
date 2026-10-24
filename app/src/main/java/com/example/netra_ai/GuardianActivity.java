package com.example.netra_ai;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import org.json.JSONObject;

import java.net.URISyntaxException;

import io.socket.client.IO;
import io.socket.client.Socket;

public class GuardianActivity extends AppCompatActivity {

    private static final String TAG = "NETRA_GUARDIAN";
    private Socket mSocket;
    private TextView tvGuardianHazard;
    private CardView cardSOS;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_guardian);

        tvGuardianHazard = findViewById(R.id.tvGuardianHazard);
        cardSOS = findViewById(R.id.cardSOS);

        initSocketConnection();
    }

    private void initSocketConnection() {
        try {
            // Updated with your laptop's Local WiFi IP Address
            mSocket = IO.socket("http://172.15.43.233:3000");
            mSocket.connect();
            mSocket.on(Socket.EVENT_CONNECT, args -> Log.d(TAG, "Guardian Socket connected"));

            // Listen for Hazard warnings from the visually impaired user's device
            mSocket.on("hazard_detected", args -> {
                if (args.length > 0) {
                    try {
                        JSONObject hazard = (JSONObject) args[0];
                        String type = hazard.getString("type");
                        runOnUiThread(() -> tvGuardianHazard.setText(type));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });

            // Listen for SOS panics
            mSocket.on("panic_sos", args -> runOnUiThread(() -> {
                cardSOS.setVisibility(View.VISIBLE);
                playLoudSiren(); // Trigger actual loud alarm on Guardian's phone!
            }));

        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
    }

    private void playLoudSiren() {
        try {
            android.media.RingtoneManager.getRingtone(getApplicationContext(), 
                android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)).play();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onDestroy() {
        if (mSocket != null) {
            mSocket.disconnect();
        }
        super.onDestroy();
    }
}