package com.example.netlockwidget;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.*;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.*;

public class NetworkUtils {
    
    // 进度回调接口
    public interface ScanProgressCallback {
        void onProgressUpdate(int current, int total);
        void onIpChecked(String ip, boolean foundDevice);
    }

    // 获取本机IP地址
    public static String getLocalIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface networkInterface = en.nextElement();
                if (networkInterface.isLoopback() || networkInterface.isVirtual() || !networkInterface.isUp()) {
                    continue;
                }
                
                for (Enumeration<InetAddress> enumIpAddr = networkInterface.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        String ip = inetAddress.getHostAddress();
                        System.out.println("Found local IP address: " + ip);
                        return ip;
                    }
                }
            }
        } catch (SocketException ex) {
            System.out.println("获取IP地址时发生异常: " + ex.toString());
        }
        System.out.println("Could not determine local IP address");
        return null;
    }

    // 发送解锁命令到指定IP的电子锁
    public static String sendUnlockCommand(String esp32Ip) {
        try {
            System.out.println("Attempting to unlock ESP32 at: " + esp32Ip);
            
            // 验证IP地址格式
            if (esp32Ip == null || esp32Ip.isEmpty()) {
                System.out.println("Invalid IP address provided");
                return "error";
            }
            
            URL url = new URL("http://" + esp32Ip + "/unlock");
            System.out.println("Created URL: " + url.toString());
            
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000); // 5秒连接超时
            connection.setReadTimeout(5000);    // 5秒读取超时
            
            // 设置请求属性，模仿浏览器行为
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) ESP32-Unlocker");
            connection.setRequestProperty("Accept", "*/*");
            connection.setRequestProperty("Cache-Control", "no-cache");
            
            System.out.println("Connection established, getting response code...");
            int responseCode = connection.getResponseCode();
            System.out.println("Response code: " + responseCode);

            if (responseCode == HttpURLConnection.HTTP_OK) {
                // 读取响应内容，即使我们不使用它
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(connection.getInputStream()));
                java.lang.StringBuilder response = new java.lang.StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                
                String responseStr = response.toString().trim();
                System.out.println("Response from ESP32: " + responseStr);
                
                System.out.println("Connection successful, unlock command sent");
                return "success";
            } else {
                System.out.println("HTTP request failed with response code: " + responseCode);
                return "failed";
            }
        } catch (java.net.UnknownHostException e) {
            System.out.println("Unknown host exception: " + e.getMessage());
            e.printStackTrace();
            return "failed"; // 无法解析主机名或IP地址
        } catch (java.net.ConnectException e) {
            System.out.println("Connection exception: " + e.getMessage());
            e.printStackTrace();
            return "failed"; // 连接被拒绝
        } catch (java.net.SocketTimeoutException e) {
            System.out.println("Socket timeout exception: " + e.getMessage());
            e.printStackTrace();
            return "failed"; // 连接超时
        } catch (Exception e) {
            System.out.println("General exception sending unlock command: " + e.getMessage());
            e.printStackTrace();
            return "error";
        }
    }

    // 检查IP地址是否是电子锁（详细验证）
    public static boolean isEsp32DeviceVerified(String ipAddress) {
        try {
            System.out.println("Performing detailed verification for ESP32 at: " + ipAddress);
            URL url = new URL("http://" + ipAddress + "/status");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(1500); // 1.5秒连接超时，更快响应
            connection.setReadTimeout(1500);    // 1.5秒读取超时，更快响应
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) ESP32-Detector");
            connection.setRequestProperty("Accept", "*/*");

            int responseCode = connection.getResponseCode();
            boolean isDevice = (responseCode == HttpURLConnection.HTTP_OK);
            
            System.out.println("Detailed verification for " + ipAddress + ", response code: " + responseCode + ", is ESP32: " + isDevice);
            connection.disconnect();
            return isDevice;
        } catch (Exception e) {
            System.out.println("Failed to verify " + ipAddress + " as ESP32, error: " + e.getMessage());
            return false;
        }
    }

    // 发现电子锁的方法，带进度回调
    public static List<String> discoverEsp32Devices(ScanProgressCallback callback) {
        List<String> deviceIps = new ArrayList<>();
        int totalChecked = 0;
        
        try {
            // 获取本地网络地址范围
            String localIpAddress = getLocalIpAddress();
            System.out.println("Local IP Address: " + localIpAddress);
            
            if (localIpAddress != null) {
                String networkPrefix = localIpAddress.substring(0, localIpAddress.lastIndexOf('.')) + ".";
                
                System.out.println("Scanning network: " + networkPrefix + "1-254");
                
                // 使用分批处理的方式进行扫描，平衡性能和稳定性
                List<String> potentialDevices = new ArrayList<>();
                
                // 将254个IP分成若干批次处理
                int batchSize = 50; // 每批处理50个IP
                for (int batchStart = 1; batchStart <= 254; batchStart += batchSize) {
                    int batchEnd = Math.min(batchStart + batchSize - 1, 254);
                    
                    // 创建固定大小的线程池用于当前批次
                    int actualBatchSize = batchEnd - batchStart + 1;
                    int threadPoolSize = Math.min(actualBatchSize, 20); // 最多20个线程
                    ExecutorService batchExecutor = Executors.newFixedThreadPool(threadPoolSize);
                    
                    try {
                        List<Future<String>> batchFutures = new ArrayList<>();
                        
                        // 提交当前批次的扫描任务
                        for (int i = batchStart; i <= batchEnd; i++) {
                            final String ip = networkPrefix + i;
                            
                            Future<String> future = batchExecutor.submit(() -> {
                                // 快速TCP连接检测
                                if (isEsp32Device(ip)) {
                                    System.out.println("Potential device found at: " + ip);
                                    return ip;
                                }
                                return null;
                            });
                            
                            batchFutures.add(future);
                        }
                        
                        // 收集当前批次的结果
                        for (int i = 0; i < batchFutures.size(); i++) {
                            Future<String> future = batchFutures.get(i);
                            String result = null;
                            try {
                                result = future.get(2, TimeUnit.SECONDS); // 设置2秒超时
                                if (result != null) {
                                    potentialDevices.add(result);
                                }
                            } catch (Exception e) {
                                System.out.println("Task execution error for IP " + (networkPrefix + (batchStart + i)) + ": " + e.getMessage());
                                result = null;
                            }
                            
                            if (callback != null) {
                                totalChecked++;
                                
                                // 为了减少UI更新频率，只在特定间隔更新进度
                                if (totalChecked % 10 == 0 || totalChecked == 254) { // 每10个或最后一个更新一次
                                    callback.onProgressUpdate(totalChecked, 254); // 总共254个IP
                                }
                                
                                callback.onIpChecked(networkPrefix + (batchStart + i), result != null);
                            }
                        }
                    } finally {
                        // 正确关闭当前批次的执行器
                        batchExecutor.shutdown();
                        try {
                            if (!batchExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                                batchExecutor.shutdownNow();
                            }
                        } catch (InterruptedException e) {
                            batchExecutor.shutdownNow();
                            Thread.currentThread().interrupt();
                        }
                    }
                }
                
                System.out.println("Found " + potentialDevices.size() + " potential electronic locks, performing detailed verification...");
                
                // 第二步：对潜在电子锁进行详细验证（也使用分批处理）
                for (String ip : potentialDevices) {
                    System.out.println("Verifying potential electronic lock: " + ip);
                    if (isEsp32DeviceVerified(ip)) {
                        System.out.println("Confirmed electronic lock at: " + ip);
                        deviceIps.add(ip);
                    } else {
                        System.out.println("Device at " + ip + " is not an electronic lock or not responding properly");
                    }
                }
            } else {
                System.out.println("Could not determine local IP address");
            }
        } catch (Exception e) {
            System.out.println("Exception during device discovery: " + e.getMessage());
            e.printStackTrace();
        }
        
        System.out.println("Final result: Found " + deviceIps.size() + " electronic locks");
        return deviceIps;
    }
    
    // 原来的不带进度回调的版本，为了向后兼容
    public static List<String> discoverEsp32Devices() {
        return discoverEsp32Devices(null);
    }

    // 检查IP地址是否是电子锁
    public static boolean isEsp32Device(String ipAddress) {
        try {
            System.out.println("Attempting to connect to " + ipAddress + ":80");
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(ipAddress, 80), 300); // 300ms超时，更快的响应
            socket.close();
            System.out.println("Successfully connected to " + ipAddress + ":80");
            return true;
        } catch (IOException e) {
            System.out.println("Failed to connect to " + ipAddress + ":80, error: " + e.getMessage());
            return false;
        } catch (Exception e) {
            System.out.println("Unexpected error connecting to " + ipAddress + ":80, error: " + e.getMessage());
            return false;
        }
    }
    
    // 向所有发现的电子锁发送解锁命令
    public static List<String> sendUnlockToAllDevices() {
        System.out.println("Starting device discovery...");
        List<String> results = new ArrayList<>();
        List<String> deviceIps = discoverEsp32Devices();
        
        System.out.println("Discovery complete. Found " + deviceIps.size() + " devices.");

        if (deviceIps.isEmpty()) {
            System.out.println("No electronic locks found on the network");
            results.add("failed"); // 没有找到设备
            return results;
        }
        
        System.out.println("Found " + deviceIps.size() + " electronic lock(s). Sending unlock commands...");
        
        // 向所有发现的设备发送解锁命令
        for (String ip : deviceIps) {
            System.out.println("Sending unlock command to: " + ip);
            String result = sendUnlockCommand(ip);
            results.add(result);
            System.out.println("Unlock command result for " + ip + ": " + result);
        }
        
        return results;
    }
}