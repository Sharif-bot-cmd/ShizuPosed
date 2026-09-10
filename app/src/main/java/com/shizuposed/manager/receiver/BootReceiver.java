package com.shizuposed.manager.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.shizuposed.manager.service.ShizuPosedService;

public class BootReceiver extends BroadcastReceiver {
    
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) ||
            Intent.ACTION_REBOOT.equals(intent.getAction())) {
            
            // Check if auto-start is enabled
            SharedPreferences prefs = context.getSharedPreferences("shizuposed_settings", Context.MODE_PRIVATE);
            boolean autoStart = prefs.getBoolean("auto_start", false);
            
            if (autoStart) {
                try {
                    Intent serviceIntent = new Intent(context, ShizuPosedService.class);
                    context.startService(serviceIntent);
                    android.util.Log.i("BootReceiver", "Service auto-started on boot");
                } catch (Exception e) {
                    android.util.Log.e("BootReceiver", "Failed to start service: " + e.getMessage());
                }
            }
        }
    }
}
