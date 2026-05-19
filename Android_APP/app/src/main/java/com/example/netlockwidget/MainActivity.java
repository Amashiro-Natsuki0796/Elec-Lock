package com.example.netlockwidget;

import android.app.Activity;
import android.app.ProgressDialog;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.net.InetSocketAddress;
import java.util.Enumeration;
import java.util.List;

public class MainActivity extends Activity {

    private ListView deviceListView;
    private Button scanButton;
    private Button unlockButton;
    private Button settingsButton;  // 新增：设置按钮
    private ArrayAdapter<String> adapter;
    private List<String> discoveredDevices;
    private ProgressDialog progressDialog;
    private int selectedPosition = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initializeViews();
        setupAdapter();
        setupClickListener();
        
        // 加载最后使用的设备（如果有的话）
        loadLastUsedDevice();
    }

    private void initializeViews() {
        deviceListView = findViewById(R.id.device_list_view);
        scanButton = findViewById(R.id.scan_button);
        unlockButton = findViewById(R.id.unlock_button);
        settingsButton = findViewById(R.id.settings_button);  // 新增：初始化设置按钮
    }

    private void setupAdapter() {
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_single_choice, new java.util.ArrayList<>());
        deviceListView.setAdapter(adapter);
        deviceListView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
    }

    private void setupClickListener() {
        scanButton.setOnClickListener(v -> scanForDevices());
        
        deviceListView.setOnItemClickListener((parent, view, position, id) -> {
            // 用户选择了某个设备
            selectedPosition = position;
            // 使用adapter.getItem而不是discoveredDevices.get，以避免同步问题
            String selectedDevice = adapter.getItem(position);
            
            if (selectedDevice != null) {
                Toast.makeText(this, "已选择设备: " + selectedDevice, Toast.LENGTH_SHORT).show();
                
                // 保存选定的设备
                LockWidgetProvider.setSelectedDevice(this, selectedDevice);
                System.out.println("Saved selected device to preferences: " + selectedDevice);
                
                // 同时确保discoveredDevices列表也包含这个设备
                if (discoveredDevices == null) {
                    discoveredDevices = new java.util.ArrayList<>();
                }
                
                // 如果discoveredDevices中不包含该设备，也要添加进去
                if (!discoveredDevices.contains(selectedDevice)) {
                    discoveredDevices.add(selectedDevice);
                }
                
                // 更新selectedPosition以确保它对应于discoveredDevices中的正确索引
                selectedPosition = discoveredDevices.indexOf(selectedDevice);
            }
        });
        
        unlockButton.setOnClickListener(v -> unlockSelectedDevice());
        
        settingsButton.setOnClickListener(v -> showSettingsDialog());
    }
    
    private void showSettingsDialog() {
        // 创建一个包含SeekBar的对话框
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("小部件设置");
        
        // 创建一个包含SeekBar的布局
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 30, 50, 30);
        
        // 添加透明度标签
        TextView alphaLabel = new TextView(this);
        alphaLabel.setText("透明度设置");
        alphaLabel.setTextSize(16);
        layout.addView(alphaLabel);
        
        // 添加SeekBar
        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(100);  // 0-100%
        
        // 获取当前透明度值
        float currentAlpha = LockWidgetProvider.getWidgetAlpha(this);
        seekBar.setProgress((int)(currentAlpha * 100));  // 转换为百分比
        
        // 添加进度文本
        TextView progressText = new TextView(this);
        progressText.setText(String.format("%.0f%%", currentAlpha * 100));
        progressText.setTextSize(14);
        progressText.setGravity(android.view.Gravity.CENTER);
        
        // 设置SeekBar监听器
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float alpha = progress / 100.0f;
                progressText.setText(String.format("%.0f%%", alpha * 100));
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        layout.addView(seekBar);
        layout.addView(progressText);
        
        builder.setView(layout);
        
        // 添加确定和取消按钮
        builder.setPositiveButton("确定", (dialog, which) -> {
            int progress = seekBar.getProgress();
            float alpha = progress / 100.0f;
            
            // 保存透明度设置
            LockWidgetProvider.setWidgetAlpha(MainActivity.this, alpha);
            
            // 更新所有小部件以应用新的透明度
            updateAllWidgets();
            
            Toast.makeText(MainActivity.this, "透明度已设置为 " + String.format("%.0f%%", alpha * 100), Toast.LENGTH_SHORT).show();
        });
        
        builder.setNegativeButton("取消", (dialog, which) -> dialog.cancel());
        
        builder.show();
    }
    
    private void updateAllWidgets() {
        // 更新所有小部件以应用新的透明度
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(this);
        int[] appWidgetIds = appWidgetManager.getAppWidgetIds(new ComponentName(this, LockWidgetProvider.class));
        
        for (int appWidgetId : appWidgetIds) {
            LockWidgetProvider lockWidgetProvider = new LockWidgetProvider();
            lockWidgetProvider.onUpdate(this, appWidgetManager, new int[]{appWidgetId});
        }
    }
    
    private void loadLastUsedDevice() {
        String lastUsedDevice = LockWidgetProvider.getLastUsedDevice(this);
        if (!lastUsedDevice.isEmpty()) {
            // 检查最后使用的设备是否在列表中
            boolean deviceExists = false;
            for (int i = 0; i < adapter.getCount(); i++) {
                if (adapter.getItem(i).equals(lastUsedDevice)) {
                    deviceExists = true;
                    break;
                }
            }
            
            // 如果最后使用的设备不在列表中，添加它
            if (!deviceExists) {
                adapter.add(lastUsedDevice);
            }
            
            // 确保discoveredDevices列表也被初始化
            if (discoveredDevices == null) {
                discoveredDevices = new java.util.ArrayList<>();
            }
            
            // 如果discoveredDevices中不包含该设备，也要添加进去
            if (!discoveredDevices.contains(lastUsedDevice)) {
                discoveredDevices.add(lastUsedDevice);
            }
            
            // 设置为选中状态
            int position = adapter.getPosition(lastUsedDevice);
            if (position >= 0) {
                deviceListView.setItemChecked(position, true);
                selectedPosition = position;
                Toast.makeText(this, "使用上次设备: " + lastUsedDevice, Toast.LENGTH_SHORT).show();
                
                // 同时保存为当前选定设备
                LockWidgetProvider.setSelectedDevice(this, lastUsedDevice);
            }
        }
    }

    private void scanForDevices() {
        showProgressDialogWithProgress();

        new Thread(() -> {
            // 首先进行网络诊断
            performNetworkDiagnostics();
            
            try {
                // 使用带进度回调的扫描方法
                discoveredDevices = NetworkUtils.discoverEsp32Devices(new NetworkUtils.ScanProgressCallback() {
                    @Override
                    public void onProgressUpdate(int current, int total) {
                        // 更新进度条
                        runOnUiThread(() -> {
                            if (progressDialog != null && progressDialog.isShowing()) {
                                // 更新进度对话框的文本和进度值
                                progressDialog.setMessage("正在扫描设备... " + current + "/" + total);
                                progressDialog.setProgress(current);  // 设置实际的进度值
                            }
                        });
                    }

                    @Override
                    public void onIpChecked(String ip, boolean foundDevice) {
                        // 当找到设备时，在UI上提供即时反馈
                        if (foundDevice) {
                            runOnUiThread(() -> {
                                Toast.makeText(MainActivity.this, "发现设备: " + ip, Toast.LENGTH_SHORT).show();
                            });
                        }
                    }
                });
                
                runOnUiThread(() -> {
                    hideProgressDialog();
                    
                    if (discoveredDevices != null && !discoveredDevices.isEmpty()) {
                        // 更新列表
                        adapter.clear();
                        adapter.addAll(discoveredDevices);
                        adapter.notifyDataSetChanged();
                        
                        Toast.makeText(this, "完成扫描，发现 " + discoveredDevices.size() + " 个电子锁", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "未发现电子锁", Toast.LENGTH_SHORT).show();
                        adapter.clear();
                        adapter.notifyDataSetChanged();
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    hideProgressDialog();
                    Toast.makeText(this, "扫描失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }
    
    private void performNetworkDiagnostics() {
        System.out.println("=== NETWORK DIAGNOSTICS ===");
        
        // 打印网络接口信息
        printNetworkInterfaces();
        
        String localIp = NetworkUtils.getLocalIpAddress();
        System.out.println("Local IP Address: " + localIp);
        
        if (localIp != null) {
            String networkPrefix = localIp.substring(0, localIp.lastIndexOf('.')) + ".";
            System.out.println("Network prefix: " + networkPrefix);
            
            // 测试连接到已知的电子锁
            boolean esp32Reachable = testConnection("192.168.0.221", 80);
            System.out.println("Electronic lock (192.168.0.221) reachable: " + esp32Reachable);
        }
        
        // 测试连接到网关（通常是 .1 或 .254）
        String gatewayIp = localIp != null ? 
            localIp.substring(0, localIp.lastIndexOf('.')) + ".1" : 
            "192.168.0.1";
        boolean gatewayReachable = testConnection(gatewayIp, 80);
        System.out.println("Gateway (" + gatewayIp + ") reachable: " + gatewayReachable);
        
        System.out.println("=== END NETWORK DIAGNOSTICS ===");
    }
    
    // 打印网络接口信息
    private void printNetworkInterfaces() {
        try {
            System.out.println("Listing all network interfaces:");
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface networkInterface = en.nextElement();
                System.out.println("Interface: " + networkInterface.getName() + " (" + networkInterface.getDisplayName() + ")");
                System.out.println("  Is up: " + networkInterface.isUp());
                System.out.println("  Is loopback: " + networkInterface.isLoopback());
                System.out.println("  Is virtual: " + networkInterface.isVirtual());
                
                for (Enumeration<InetAddress> enumIpAddr = networkInterface.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    System.out.println("  Address: " + inetAddress.getHostAddress() + " (IPv4: " + (inetAddress instanceof Inet4Address) + ")");
                }
                System.out.println();
            }
        } catch (SocketException ex) {
            System.out.println("Error listing network interfaces: " + ex.toString());
        }
    }
    
    // 测试连接到特定IP和端口
    private boolean testConnection(String ipAddress, int port) {
        try {
            System.out.println("Testing connection to " + ipAddress + ":" + port);
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(ipAddress, port), 1000); // 1秒超时
            socket.close();
            System.out.println("Successfully connected to " + ipAddress + ":" + port);
            return true;
        } catch (IOException e) {
            System.out.println("Failed to connect to " + ipAddress + ":" + port + ", error: " + e.getMessage());
            return false;
        }
    }
    
    private void unlockSelectedDevice() {
        // 首先尝试使用列表中选中的设备
        int checkedPosition = deviceListView.getCheckedItemPosition();
        
        if (checkedPosition != ListView.INVALID_POSITION) {
            // 使用选中的设备
            String selectedDevice = adapter.getItem(checkedPosition);
            if (selectedDevice != null) {
                performUnlockOperation(selectedDevice);
                return;
            }
        }
        
        // 如果没有选中任何设备，尝试使用最后使用的设备
        String lastUsedDevice = LockWidgetProvider.getLastUsedDevice(this);
        if (!lastUsedDevice.isEmpty()) {
            performUnlockOperation(lastUsedDevice);
            return;
        }
        
        // 如果以上都不行，提示用户选择设备
        Toast.makeText(this, "请先选择一个设备", Toast.LENGTH_SHORT).show();
    }
    
    private void performUnlockOperation(String deviceIp) {
        System.out.println("Attempting to unlock device: " + deviceIp);
        showUnlockProgressDialog();
        
        new Thread(() -> {
            try {
                // 发送解锁命令到选定的设备
                String result = NetworkUtils.sendUnlockCommand(deviceIp);
                System.out.println("Unlock command result: " + result);
                
                // 如果解锁成功，保存为最后使用的设备
                if ("success".equals(result)) {
                    LockWidgetProvider.setLastUsedDevice(this, deviceIp);
                    System.out.println("Saved device as last used: " + deviceIp);
                }
                
                runOnUiThread(() -> {
                    hideUnlockProgressDialog();
                    
                    if ("success".equals(result)) {
                        Toast.makeText(this, "解锁成功!", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "解锁失败，请重试", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    hideUnlockProgressDialog();
                    Toast.makeText(this, "解锁过程中出现错误: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void showProgressDialog() {
        runOnUiThread(() -> {
            progressDialog = new ProgressDialog(MainActivity.this);
            progressDialog.setTitle("正在扫描设备...");
            progressDialog.setMessage("请稍候...");
            progressDialog.setCancelable(false);
            progressDialog.show();
        });
    }
    
    private void showProgressDialogWithProgress() {
        runOnUiThread(() -> {
            progressDialog = new ProgressDialog(MainActivity.this);
            progressDialog.setTitle("正在扫描设备...");
            progressDialog.setMessage("正在扫描设备... 0/254");
            progressDialog.setCancelable(false);
            progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            progressDialog.setMax(254);
            progressDialog.show();
        });
    }
    
    private void showUnlockProgressDialog() {
        runOnUiThread(() -> {
            progressDialog = new ProgressDialog(MainActivity.this);
            progressDialog.setTitle("正在发送解锁命令...");
            progressDialog.setMessage("请稍候...");
            progressDialog.setCancelable(false);
            progressDialog.show();
        });
    }

    private void hideProgressDialog() {
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
    }
    
    private void hideUnlockProgressDialog() {
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
    }
}