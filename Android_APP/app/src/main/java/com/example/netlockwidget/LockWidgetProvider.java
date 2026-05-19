package com.example.netlockwidget;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.widget.RemoteViews;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;

import java.util.List;

public class LockWidgetProvider extends AppWidgetProvider {

    public static final String UNLOCK_ACTION = "com.example.netlockwidget.UNLOCK_ACTION";
    private static final String PREFS_NAME = "LockWidgetPrefs";
    private static final String PREF_SELECTED_DEVICE = "selected_device";
    private static final String PREF_LAST_USED_DEVICE = "last_used_device"; // 新增：记住最后一次使用的设备
    private static final String PREF_WIDGET_ALPHA = "widget_alpha"; // 新增：小部件透明度
    private static final float DEFAULT_WIDGET_ALPHA = 1.0f; // 默认不透明

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        // 更新所有实例的小部件
        for (int appWidgetId : appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        
        if (UNLOCK_ACTION.equals(intent.getAction())) {
            handleUnlockAction(context);
        }
    }

    private void updateAppWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_layout);

        // 设置解锁按钮的点击意图
        Intent unlockIntent = new Intent(context, LockWidgetProvider.class);
        unlockIntent.setAction(UNLOCK_ACTION);
        
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ?
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE :
            PendingIntent.FLAG_UPDATE_CURRENT;
            
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
            context, 0, unlockIntent, flags
        );
        
        views.setOnClickPendingIntent(R.id.lock_button, pendingIntent);

        // 设置透明度 - 使用颜色滤镜代替setAlpha（因为RemoteViews中setAlpha可能不支持）
        float alpha = getWidgetAlpha(context);
        int alphaValue = (int) (alpha * 255);  // 转换为0-255范围
        views.setInt(R.id.lock_button, "setImageAlpha", alphaValue);

        appWidgetManager.updateAppWidget(appWidgetId, views);
    }

    private void handleUnlockAction(Context context) {
        // 在后台线程执行网络请求
        new Thread(() -> {
            try {
                System.out.println("Starting unlock action...");
                
                // 从SharedPreferences获取选定的设备IP
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                String selectedDeviceIp = prefs.getString(PREF_SELECTED_DEVICE, "");
                
                System.out.println("Retrieved selected device IP from preferences: " + selectedDeviceIp);
                
                String deviceToUse;
                if (!selectedDeviceIp.isEmpty()) {
                    // 如果有选定设备，使用选定的设备
                    deviceToUse = selectedDeviceIp;
                } else {
                    // 如果没有选定设备，尝试使用最后使用的设备
                    String lastUsedDevice = prefs.getString(PREF_LAST_USED_DEVICE, "");
                    if (!lastUsedDevice.isEmpty()) {
                        System.out.println("Using last used device: " + lastUsedDevice);
                        deviceToUse = lastUsedDevice;
                    } else {
                        System.out.println("No selected or last used device, scanning for devices...");
                        // 如果两者都没有，则扫描所有设备
                        java.util.List<String> results = NetworkUtils.sendUnlockToAllDevices();
                        
                        // 检查结果并决定通知内容
                        String overallResult;
                        if (results.contains("success")) {
                            // 如果有任何一个设备成功解锁，则视为成功
                            overallResult = "success";
                            System.out.println("At least one device unlocked successfully");
                        } else if (results.contains("failed") || results.contains("error")) {
                            // 如果有失败或错误，则视为失败
                            overallResult = "failed";
                            System.out.println("All devices failed to unlock");
                        } else {
                            // 其他情况也视为失败
                            overallResult = "failed";
                            System.out.println("No devices found or unknown result");
                        }
                        
                        // 创建通知渠道并显示通知
                        NotificationHelper.createNotificationChannel(context);
                        NotificationHelper.showUnlockNotification(context, overallResult);
                        
                        System.out.println("Notification shown with result: " + overallResult);
                        return; // 结束方法，因为我们已经处理了扫描所有设备的情况
                    }
                }
                
                // 使用选定或最后使用的设备发送解锁命令
                System.out.println("Sending unlock command to device: " + deviceToUse);
                String result = NetworkUtils.sendUnlockCommand(deviceToUse);
                
                System.out.println("Unlock command result: " + result);
                
                // 如果解锁成功，保存为最后使用的设备
                if ("success".equals(result)) {
                    setLastUsedDevice(context, deviceToUse);
                    System.out.println("Saved device as last used: " + deviceToUse);
                }
                
                // 创建通知渠道并显示通知
                NotificationHelper.createNotificationChannel(context);
                NotificationHelper.showUnlockNotification(context, result);
                
                System.out.println("Notification shown with result: " + result);
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println("Exception in handleUnlockAction: " + e.getMessage());
                // 显示错误通知
                NotificationHelper.createNotificationChannel(context);
                NotificationHelper.showUnlockNotification(context, "error");
            }
        }).start();
    }
    
    // 供MainActivity调用，保存选定的设备
    public static void setSelectedDevice(Context context, String deviceIp) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(PREF_SELECTED_DEVICE, deviceIp);
        editor.apply();
    }
    
    // 供MainActivity调用，获取选定的设备
    public static String getSelectedDevice(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(PREF_SELECTED_DEVICE, "");
    }
    
    // 保存最后使用的设备（当成功解锁后调用）
    public static void setLastUsedDevice(Context context, String deviceIp) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(PREF_LAST_USED_DEVICE, deviceIp);
        editor.apply();
    }
    
    // 获取最后使用的设备
    public static String getLastUsedDevice(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(PREF_LAST_USED_DEVICE, "");
    }
    
    // 获取小部件透明度
    public static float getWidgetAlpha(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getFloat(PREF_WIDGET_ALPHA, DEFAULT_WIDGET_ALPHA);
    }
    
    // 设置小部件透明度
    public static void setWidgetAlpha(Context context, float alpha) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putFloat(PREF_WIDGET_ALPHA, alpha);
        editor.apply();
    }
}