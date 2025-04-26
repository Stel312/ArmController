#include <stdio.h>
#include <string>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_log.h"
#include "esp_bt.h"
#include "esp_bt_main.h"
#include "esp_err.h"
#include "nvs_flash.h"
#include <errno.h>
#include <unistd.h>
#include "esp_bt_defs.h"
#include "esp_gap_bt_api.h"
#include "esp_spp_api.h"
#include "esp_vfs_dev.h"
#include "driver/uart.h"
#include <stdarg.h> // Required for va_list

#define EXAMPLE_DEVICE_NAME "ESP32_RFCOMM_SERVER"
#define RFCOMM_SCN 3
#define RFCOMM_UUID "8ce255c0-200a-11e0-ac64-0800200c9a66"
esp_bt_controller_config_t bt_cfg = {
        .controller_task_stack_size = ESP_TASK_BT_CONTROLLER_STACK,
        .controller_task_prio = ESP_TASK_BT_CONTROLLER_PRIO,
        .scan_duplicate_mode = CONFIG_BTDM_SCAN_DUPL_TYPE, // Use menuconfig setting
        .scan_duplicate_type = CONFIG_BTDM_SCAN_DUPL_CACHE_SIZE, // Use menuconfig setting
        .normal_adv_size = 20, // Example value
        .mesh_adv_size = 0,    // Example value
        .send_adv_reserved_size = 1000, // Example value
        .controller_debug_flag = 0,
        .mode = ESP_BT_MODE_CLASSIC_BT, // Explicitly set Classic BT mode
        .ble_max_conn = 1,       // Example value
        .bt_max_acl_conn = 7,    // Example value
        .bt_sco_datapath = ESP_SCO_DATA_PATH_HCI, // Example value
        .auto_latency = false,   // Example value
        .bt_legacy_auth_vs_evt = false, // Example value
        .bt_max_sync_conn = 0,   // Example value
        .ble_sca = 0,            // Example value
        .pcm_role = 0,           // Example value
        .pcm_polar = 0,          // Example value
        .pcm_fsyncshp = 0,       // Example value
        .hli = false,            // Example value
        .dup_list_refresh_period = 0, // Example value
        .ble_scan_backoff = false, // Example value
        .ble_llcp_disc_flag = 0, // Example value
        .ble_aa_check = false,   // Example value
        .magic = ESP_BT_CONTROLLER_CONFIG_MAGIC_VAL,
    };
//static const char *TAG = "ESP32_RFCOMM"; // Not used, removing.  If you use, make it const char*
static int client_socket = -1;
static const esp_spp_cfg_t esp_spp_config = {
    .mode = ESP_SPP_MODE_CB,
    .enable_l2cap_ertm = false,
    // Or true, depending on your needs.tx_buffer_size = 0,     // Not used in ESP_SPP_MODE_CB
};

// Function to send a string over serial
void serial_write_string(const char *str)
{
    if (str == nullptr)
        return;
    for (size_t i = 0; str[i] != '\0'; i++)
    {
        putchar(str[i]);
    }
    putchar('\n');
    fflush(stdout);
}

// Function to send a formatted string over serial (like printf)
void serial_write_formatted(const char *format, ...)
{
    if (format == nullptr)
        return;
    char buffer[256];
    va_list args;
    va_start(args, format);
    vsnprintf(buffer, sizeof(buffer), format, args);
    va_end(args);
    serial_write_string(buffer);
}

static void esp_spp_cb(esp_spp_cb_event_t event, esp_spp_cb_param_t *param)
{
    switch (event)
    {
    case ESP_SPP_INIT_EVT:
        serial_write_string("ESP_SPP_INIT_EVT");
        esp_bt_gap_set_device_name(EXAMPLE_DEVICE_NAME);
        esp_bt_gap_set_scan_mode(ESP_BT_CONNECTABLE, ESP_BT_GENERAL_DISCOVERABLE);
        esp_spp_start_srv(ESP_SPP_SEC_AUTHENTICATE, ESP_SPP_ROLE_MASTER, 0, "EXAMPLE_DEVICE_NAME");
        break;
    case ESP_SPP_DISCOVERY_COMP_EVT:
        serial_write_string("ESP_SPP_DISCOVERY_COMP_EVT");
        break;
    case ESP_SPP_OPEN_EVT:
        serial_write_formatted("ESP_SPP_OPEN_EVT: New connection, handle=%d", param->open.handle);
        client_socket = param->open.handle;
        break;
    case ESP_SPP_CLOSE_EVT:
        serial_write_formatted("ESP_SPP_CLOSE_EVT: Connection closed, handle=%d", param->close.handle);
        if (client_socket != -1)
        {
            close(client_socket);
            client_socket = -1;
        }
        break;
    case ESP_SPP_WRITE_EVT:
        serial_write_formatted("ESP_SPP_WRITE_EVT: Write finished, status=%d, len=%d", param->write.status, param->write.len);
        break;
    case ESP_SPP_DATA_IND_EVT:
        serial_write_formatted("ESP_SPP_DATA_IND_EVT: Data received, len=%d", param->data_ind.len);
        // Send the received data over serial
        for (int i = 0; i < param->data_ind.len; i++)
        {
            putchar(param->data_ind.data[i]);
        }
        fflush(stdout); // Ensure the data is sent immediately.
        break;
    case ESP_SPP_CONG_EVT:
        serial_write_formatted("ESP_SPP_CONG_EVT: Congestion status=%d", param->cong.status);
        break;
    default:
        serial_write_formatted("ESP_SPP_CB_EVT: Unknown event=%d", event);
        break;
    }
}

extern "C" void app_main(void) // Add extern "C" here!
{
    printf("test\n");
    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND)
    {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    if (ret != ESP_OK)
    {
        serial_write_formatted("NVS initialization failed: %s", esp_err_to_name(ret));
        
        return;
    }
    printf("test\n");
    ESP_ERROR_CHECK(esp_bt_controller_mem_release(ESP_BT_MODE_BLE));
    printf("test\n");

    //esp_bt_controller_config_t bt_cfg = BT_CONTROLLER_INIT_CONFIG_DEFAULT();
    bt_cfg.mode = ESP_BT_MODE_CLASSIC_BT;
    if ((ret = esp_bt_controller_init(&bt_cfg)) != ESP_OK)
    {
        serial_write_formatted("Bluetooth controller initialization failed: %s", esp_err_to_name(ret));
        return;
    }
    serial_write_string("Bluetooth controller initialized");
    printf("test\n");
    if ((ret = esp_bt_controller_enable(ESP_BT_MODE_CLASSIC_BT)) != ESP_OK)
    {
        serial_write_formatted("Bluetooth controller enabling failed: %s", esp_err_to_name(ret));
        return;
    }
    serial_write_string("Bluetooth controller enabled");

}