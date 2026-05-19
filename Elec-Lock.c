#include <gtk/gtk.h>
#include <glib.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <errno.h>
#include <termios.h>
#include <unistd.h>
#include <string.h>
#include <stdlib.h>
#include <dirent.h>

#define MAX_DEVICES 100
#define BUFFER_SIZE 256

typedef struct {
    GtkStatusIcon *status_icon;
    char selected_port[256];
    int serial_fd;
} AppData;

static AppData app_data = {NULL, "", -1};

// 打开串口连接
int open_serial_port(const char* port) {
    int fd = open(port, O_RDWR | O_NOCTTY);
    if (fd == -1) {
        g_print("Error opening %s: %s\n", port, strerror(errno));
        return -1;
    }

    struct termios tty;
    memset(&tty, 0, sizeof(tty));

    if (tcgetattr(fd, &tty) != 0) {
        g_print("Error getting attributes %s: %s\n", port, strerror(errno));
        close(fd);
        return -1;
    }

    cfsetospeed(&tty, B115200);
    cfsetispeed(&tty, B115200);

    tty.c_cflag |= (CLOCAL | CREAD);
    tty.c_cflag &= ~CSIZE;
    tty.c_cflag |= CS8;
    tty.c_cflag &= ~PARENB;
    tty.c_cflag &= ~CSTOPB;
    tty.c_cflag &= ~CRTSCTS;

    tty.c_lflag &= ~ICANON;
    tty.c_lflag &= ~ECHO;
    tty.c_lflag &= ~ECHOE;
    tty.c_lflag &= ~ISIG;

    tty.c_iflag &= ~(IXON | IXOFF | IXANY);
    tty.c_oflag &= ~OPOST;

    tty.c_cc[VMIN] = 0;
    tty.c_cc[VTIME] = 10;

    if (tcsetattr(fd, TCSANOW, &tty) != 0) {
        g_print("Error setting attributes %s: %s\n", port, strerror(errno));
        close(fd);
        return -1;
    }

    return fd;
}

// 发送命令到串口
gboolean send_command_to_serial(const char* command) {
    if (app_data.serial_fd == -1) {
        g_print("No serial port opened\n");
        return FALSE;
    }

    ssize_t bytes_written = write(app_data.serial_fd, command, strlen(command));
    if (bytes_written < 0) {
        g_print("Error writing to serial: %s\n", strerror(errno));
        return FALSE;
    }
    
    tcdrain(app_data.serial_fd); // Wait for transmission to complete
    return TRUE;
}

// 获取可用的串口设备
char** get_available_serial_ports(int* count) {
    char** ports = malloc(MAX_DEVICES * sizeof(char*));
    *count = 0;

    DIR* dir = opendir("/dev");
    if (dir) {
        struct dirent* entry;
        while ((entry = readdir(dir)) != NULL && *count < MAX_DEVICES) {
            if (strstr(entry->d_name, "ttyUSB") || 
                strstr(entry->d_name, "ttyACM") || 
                strstr(entry->d_name, "ttyS")) {
                
                ports[*count] = malloc(256);
                snprintf(ports[*count], 248, "/dev/%s", entry->d_name);  // 修复缓冲区溢出警告
                (*count)++;
            }
        }
        closedir(dir);
    }

    return ports;
}

// 左键点击事件 - 发送开锁命令
static void on_left_click(GtkStatusIcon *status_icon, gpointer user_data) {
    g_print("Left click detected - sending unlock command\n");
    
    if (strlen(app_data.selected_port) > 0) {
        if (app_data.serial_fd == -1) {
            app_data.serial_fd = open_serial_port(app_data.selected_port);
        }
        
        if (app_data.serial_fd != -1) {
            send_command_to_serial("ON\n");
        } else {
            g_print("Failed to open serial port: %s\n", app_data.selected_port);
        }
    } else {
        g_print("No serial port selected\n");
    }
}

// 串口选择菜单项的回调函数
static void on_port_selected(GtkMenuItem *menu_item, gpointer user_data) {
    const gchar *selected_port = gtk_menu_item_get_label(GTK_MENU_ITEM(menu_item));
    strcpy(app_data.selected_port, selected_port);
    g_print("Selected port: %s\n", selected_port);
    
    // 关闭之前的串口连接
    if (app_data.serial_fd != -1) {
        close(app_data.serial_fd);
        app_data.serial_fd = -1;
    }
    
    // 尝试打开新选择的串口
    app_data.serial_fd = open_serial_port(selected_port);
}

// 右键点击事件 - 显示串口选择菜单
static void on_right_click(GtkStatusIcon *status_icon, guint button, guint activate_time, gpointer user_data) {
    GtkWidget *menu, *item;
    int port_count;
    char** ports = get_available_serial_ports(&port_count);

    menu = gtk_menu_new();

    // 添加串口选择项
    for (int i = 0; i < port_count; i++) {
        item = gtk_menu_item_new_with_label(ports[i]);
        gtk_menu_shell_append(GTK_MENU_SHELL(menu), item);
        
        // 连接信号处理函数
        g_signal_connect(item, "activate", G_CALLBACK(on_port_selected), NULL);
    }

    if (port_count == 0) {
        item = gtk_menu_item_new_with_label("No serial devices found");
        gtk_menu_shell_append(GTK_MENU_SHELL(menu), item);
    }

    // 添加退出菜单项
    GtkWidget *separator = gtk_separator_menu_item_new();
    gtk_menu_shell_append(GTK_MENU_SHELL(menu), separator);
    
    GtkWidget *quit_item = gtk_menu_item_new_with_label("Quit");
    g_signal_connect(quit_item, "activate", G_CALLBACK(gtk_main_quit), NULL);
    gtk_menu_shell_append(GTK_MENU_SHELL(menu), quit_item);

    gtk_widget_show_all(menu);
    gtk_menu_popup_at_pointer(GTK_MENU(menu), NULL);
    
    // 释放端口列表内存
    for (int i = 0; i < port_count; i++) {
        free(ports[i]);
    }
    free(ports);
}

// 点击事件处理函数
static gboolean status_icon_on_button_press(GtkStatusIcon *status_icon, GdkEventButton *event, gpointer user_data) {
    if (event->button == 1) { // 左键
        on_left_click(status_icon, user_data);
    } else if (event->button == 3) { // 右键
        on_right_click(status_icon, event->button, event->time, user_data);
    }
    return TRUE;
}

int main(int argc, char *argv[]) {
    gtk_init(&argc, &argv);

    // 创建状态图标 - 使用您指定的系统锁定屏幕图标
    app_data.status_icon = gtk_status_icon_new_from_icon_name("system-lock-screen-symbolic"); // 使用系统锁定屏幕图标
    gtk_status_icon_set_visible(app_data.status_icon, TRUE);
    gtk_status_icon_set_has_tooltip(app_data.status_icon, TRUE);
    gtk_status_icon_set_tooltip_text(app_data.status_icon, "Lock Control");

    // 连接信号处理函数
    g_signal_connect(G_OBJECT(app_data.status_icon), "button-press-event",
                     G_CALLBACK(status_icon_on_button_press), NULL);

    // 启动主循环
    gtk_main();

    return 0;
}