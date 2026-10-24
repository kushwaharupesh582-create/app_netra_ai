package com.example.netra_ai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;

import androidx.core.app.NotificationCompat;

public class KeepAliveService extends Service {

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = new NotificationCompat.Builder(this, "NETRA_CHANNEL")
                .setContentTitle("NETRA AI is Active")
                .setContentText("Autonomous vision and safety is running in background.")
                .setSmallIcon(R.drawable.ic_netra_logo)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        // Start Foreground Service to keep app alive
        startForeground(1, notification);

        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    "NETRA_CHANNEL",
                    "NETRA AI Background Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // We don't need bound service for this
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.e("NETRA_AI", "App swiped from recents! Trying to keep alive...");
        
        // Android tries to kill the service when app is swiped. 
        // We set up an AlarmManager to forcefully wake the service back up in 1 second!
        Intent restartServiceIntent = new Intent(getApplicationContext(), this.getClass());
        restartServiceIntent.setPackage(getPackageName());
        
        PendingIntent restartServicePendingIntent = PendingIntent.getService(
                getApplicationContext(), 1, restartServiceIntent,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
                
        AlarmManager alarmService = (AlarmManager) getApplicationContext().getSystemService(Context.ALARM_SERVICE);
        if (alarmService != null) {
            alarmService.set(AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime() + 1000,
                    restartServicePendingIntent);
        }
        
        super.onTaskRemoved(rootIntent);
    }
}