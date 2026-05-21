// 包含必要的头文件
#include <WiFi.h>
#include <WiFiClient.h>
extern "C" {
  #include "driver/ledc.h"
}

// WiFi配置
const char* ssid = "*********";
const char* password = "*********";

// 定义PWM引脚（ESP32-C3的A0通常对应GPIO0）
#define PWM_PIN 0  // GPIO0 对应 A0

// PWM参数
#define LEDC_TIMER              LEDC_TIMER_0
#define LEDC_MODE               LEDC_LOW_SPEED_MODE  // ESP32-C3使用低速模式
#define LEDC_CHANNEL            LEDC_CHANNEL_0
#define LEDC_FREQUENCY          (1000) // 1kHz PWM frequency
#define LEDC_RESOLUTION         LEDC_TIMER_8_BIT     // 8-bit resolution
#define MAX_DUTY_CYCLE          (255)  // 2^8 - 1

// 控制变量
bool mosEnabled = false;
unsigned long startTime = 0;
const unsigned long duration = 400; // 0.4秒

// Web服务器
WiFiServer server(80);

void setup() {
  Serial.begin(115200);
  
  // 连接WiFi
  WiFi.begin(ssid, password);
  Serial.println("Connecting to WiFi...");
  while(WiFi.status() != WL_CONNECTED) {
    delay(1000);
    Serial.print(".");
  }
  Serial.println();
  Serial.println("WiFi connected!");
  Serial.print("IP address: ");
  Serial.println(WiFi.localIP());
  
  // 配置GPIO引脚模式
  pinMode(PWM_PIN, OUTPUT);
  digitalWrite(PWM_PIN, LOW);  // 初始状态设为低电平
  
  // 准备LED控制结构体 - 按照正确的字段顺序初始化
  ledc_timer_config_t ledc_timer = {};
  ledc_timer.speed_mode = LEDC_MODE;
  ledc_timer.timer_num = LEDC_TIMER;
  ledc_timer.duty_resolution = LEDC_RESOLUTION;
  ledc_timer.freq_hz = LEDC_FREQUENCY;
  ledc_timer_config(&ledc_timer);

  ledc_channel_config_t ledc_channel = {};
  ledc_channel.gpio_num = PWM_PIN;
  ledc_channel.speed_mode = LEDC_MODE;
  ledc_channel.channel = LEDC_CHANNEL;
  ledc_channel.timer_sel = LEDC_TIMER;
  ledc_channel.duty = 0;  // 初始占空比为0%
  ledc_channel.hpoint = 0;
  ledc_channel_config(&ledc_channel);
  
  server.begin();
  Serial.println("HTTP server started");
}

void loop() {
  // 检查串口输入
  if (Serial.available()) {
    String command = Serial.readStringUntil('\n');
    command.trim();
    
    if (command == "ON" || command == "on") {
      activateMOS();
    } else if (command == "OFF" || command == "off") {
      deactivateMOS();
    }
  }

  // 处理HTTP请求
  WiFiClient client = server.available();
  if (client) {
    // 等待客户端发送数据
    while(client.connected() && !client.available()){
      delay(1);
    }
    
    String request = client.readStringUntil('\r');
    client.flush();
    
    if (request.indexOf("/unlock") != -1) {
      activateMOS();
      client.println("HTTP/1.1 200 OK");
      client.println("Content-Type: text/html");
      client.println("Connection: close");
      client.println();
      client.println("<!DOCTYPE html><html><head><title>ESP32 Unlock</title></head><body><h1>Unlock command sent!</h1><p>MOS activated for 300ms.</p></body></html>");
    } else if (request.indexOf("/status") != -1) {
      client.println("HTTP/1.1 200 OK");
      client.println("Content-Type: text/html");
      client.println("Connection: close");
      client.println();
      client.println("<!DOCTYPE html><html><head><title>ESP32 Status</title></head><body><h1>Status: ");
      client.println(mosEnabled ? "Active" : "Inactive");
      client.println("</h1><p>Current IP: ");
      client.println(WiFi.localIP().toString());
      client.println("</p></body></html>");
    } else {
      client.println("HTTP/1.1 404 Not Found");
      client.println("Content-Type: text/html");
      client.println("Connection: close");
      client.println();
      client.println("<!DOCTYPE html><html><head><title>ESP32 Not Found</title></head><body><h1>404 - Not Found</h1></body></html>");
    }
    
    delay(1);
    client.stop();
  }

  // 如果MOS开关已激活，检查是否达到持续时间
  if (mosEnabled && (millis() - startTime >= duration)) {
    deactivateMOS();
  }
}

void activateMOS() {
  // 设置PWM为100%占空比
  ledc_set_duty(LEDC_MODE, LEDC_CHANNEL, MAX_DUTY_CYCLE);
  ledc_update_duty(LEDC_MODE, LEDC_CHANNEL);
  mosEnabled = true;
  startTime = millis(); // 记录开始时间
}

void deactivateMOS() {
  // 设置PWM为0%占空比（关闭状态）
  ledc_set_duty(LEDC_MODE, LEDC_CHANNEL, 0);
  ledc_update_duty(LEDC_MODE, LEDC_CHANNEL);
  mosEnabled = false;
}
