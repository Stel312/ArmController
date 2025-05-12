#include <stdint.h>
#include <string.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_system.h"
#include "esp_log.h"
#include "nvs_flash.h"
#include "esp_bt.h"
#include "esp_bt_main.h"
#include "esp_gap_ble_api.h"
#include "esp_bt_device.h"
#include "esp_idf_version.h"

#if ESP_IDF_VERSION >= ESP_IDF_VERSION_VAL(5, 0, 0)
// #include "esp_hidd.h" // Not needed for simple advertising
#else
#include "esp_bt_device.h"
#include "esp_gap_ble_api.h"
#endif

#define SPP_SERVER_NAME "ESP32_BLE5_ADV" // Changed name to indicate BLE 5.0
#define SPP_DEVICE_NAME "ESP32_RFCOMM_DEVICE" // Not used in this advertising-only example.
static const char *TAG = "BLE5_EXT_ADV_DEMO";

// Extended advertising parameters - BLE 5.0
esp_ble_gap_ext_adv_params_t ext_adv_params = {
    .type = ESP_BLE_GAP_SET_EXT_ADV_PROP_CONNECTABLE, // Non-connectable for this example. Use CONNECTABLE for connections
    .interval_min = 0x40,                                     // Minimum advertising interval (20 ms)
    .interval_max = 0x60,                                     // Maximum advertising interval (40 ms)
    .channel_map = ADV_CHNL_ALL,
    .own_addr_type = BLE_ADDR_TYPE_PUBLIC,
    .peer_addr_type = BLE_ADDR_TYPE_PUBLIC,
    .peer_addr = {0},
    .filter_policy = ADV_FILTER_ALLOW_SCAN_ANY_CON_ANY,
    .primary_phy = ESP_BLE_GAP_PRI_PHY_1M,
    .secondary_phy = ESP_BLE_GAP_PHY_1M,
    .sid = 0,
    .scan_req_notif = false,
};

esp_ble_gap_ext_adv_t esp_adv = {
    .instance = 0,
    .duration = 0, // Continuous advertising, set to 0
    .max_events = 0,
};

// Advertising data -  BLE 5.0.  Maximum size is 255 bytes.
static uint8_t adv_data[] = {
    0x02, 0x01, 0x06,         // Flags: LE General Discoverable Mode
    0x03, 0x03, 0xFF, 0xFF,     // Service UUID: Complete List, Replace FFFF with your service UUID
    0x0C, 0x09, 'E', 'S', 'P', '3', '2', ' ', 'B', 'L', 'E', '5', '0', // Complete Local Name,  Changed to BLE50
    // Add more data here, up to 255 bytes total.
    0x05, 0xFF, 0x01, 0x02, 0x03, 0x04 // Example Manufacturer Specific Data
};

// Scan response data (Optional, can be used to send more data, if needed)
static uint8_t scan_rsp_data[] = {
    0x08, 0x09, 'E', 'S', 'P', '3', '2', ' ', 'S', 'R' // Shortened Local Name in Scan Response
};

static void gap_event_handler(esp_gap_ble_cb_event_t event, esp_ble_gap_cb_param_t *param)
{
    esp_err_t ret;
    switch (event)
    {
    case ESP_GAP_BLE_ADV_DATA_SET_COMPLETE_EVT:
        if (param->adv_data_cmpl.status != ESP_BT_STATUS_SUCCESS)
        {
            ESP_LOGE(TAG, "Failed to set advertising data: %s", esp_err_to_name(param->adv_data_cmpl.status));
        }
        else
        {
            ESP_LOGI(TAG, "Advertising data set successfully");
        }
        break;
    case ESP_GAP_BLE_SCAN_RSP_DATA_SET_COMPLETE_EVT:
        if (param->scan_rsp_data_cmpl.status != ESP_BT_STATUS_SUCCESS)
        {
            ESP_LOGE(TAG, "Failed to set scan response data: %s", esp_err_to_name(param->scan_rsp_data_cmpl.status));
        }
        else
        {
            ESP_LOGI(TAG, "Scan response data set successfully");
        }
        break;
    case ESP_GAP_BLE_ADV_START_COMPLETE_EVT:
        if (param->adv_start_cmpl.status != ESP_BT_STATUS_SUCCESS)
        {
            ESP_LOGE(TAG, "Failed to start advertising: %s", esp_err_to_name(param->adv_start_cmpl.status));
        }
        else
        {
            ESP_LOGI(TAG, "Started advertising");
        }
        break;
    case ESP_GAP_BLE_ADV_STOP_COMPLETE_EVT:
        if (param->adv_stop_cmpl.status != ESP_BT_STATUS_SUCCESS)
        {
            ESP_LOGE(TAG, "Failed to stop advertising %s\n", esp_err_to_name(param->adv_stop_cmpl.status));
        }
        else
        {
            ESP_LOGI(TAG, "Stopped advertising\n");
        }
        break;
    default:
        break;
    }
}

void app_main(void)
{
    esp_err_t ret;

    // Initialize NVS
    ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND)
    {
        ESP_LOGI(TAG, "NVS partition was truncated, erasing...");
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    ESP_ERROR_CHECK(ret);

    // Enable more verbose logging for Bluetooth-related components
    esp_log_level_set("BT_BTM", ESP_LOG_VERBOSE);
    esp_log_level_set("controller", ESP_LOG_VERBOSE);
    esp_log_level_set(TAG, ESP_LOG_VERBOSE);

    // Initialize BT controller
    esp_bt_controller_config_t bt_cfg = BT_CONTROLLER_INIT_CONFIG_DEFAULT();
    bt_cfg.bluetooth_mode = ESP_BT_MODE_BLE; // Set to BLE mode.
    ret = esp_bt_controller_init(&bt_cfg);
    if (ret)
    {
        ESP_LOGE(TAG, "Failed to initialize BT controller: %s", esp_err_to_name(ret));
        return;
    }

    // Enable BT controller
    ret = esp_bt_controller_enable(ESP_BT_MODE_BLE);
    if (ret)
    {
        ESP_LOGE(TAG, "Failed to enable BT controller: %s", esp_err_to_name(ret));
        esp_bt_controller_deinit();
        return;
    }

    // Initialize Bluedroid
    ret = esp_bluedroid_init();
    if (ret)
    {
        ESP_LOGE(TAG, "Failed to initialize Bluedroid: %s", esp_err_to_name(ret));
        esp_bt_controller_disable();
        esp_bt_controller_deinit();
        return;
    }

    // Enable Bluedroid
    ret = esp_bluedroid_enable();
    if (ret)
    {
        ESP_LOGE(TAG, "Failed to enable Bluedroid: %s", esp_err_to_name(ret));
        esp_bluedroid_deinit();
        esp_bt_controller_disable();
        esp_bt_controller_deinit();
        return;
    }

    ESP_LOGI(TAG, "Bluetooth initialized in BLE Mode");

    // Register GAP callback
    ret = esp_ble_gap_register_callback(gap_event_handler);
    if (ret)
    {
        ESP_LOGE(TAG, "Failed to register GAP callback: %s", esp_err_to_name(ret));
        esp_bluedroid_disable();
        esp_bluedroid_deinit();
        esp_bt_controller_disable();
        esp_bt_controller_deinit();
        return;
    }

    // Set device name
    ret = esp_ble_gap_set_device_name(SPP_SERVER_NAME); // Use the BLE 5.0 name
    if (ret != ESP_OK)
    {
        ESP_LOGE(TAG, "Failed to set device name: %s", esp_err_to_name(ret));
        return;
    }
    ESP_LOGI(TAG, "Device name set to \"%s\"", SPP_SERVER_NAME);

    // Set the advertising data. Important to do this *before* setting adv params
    ret = esp_ble_gap_config_adv_data_raw(adv_data, sizeof(adv_data));
    if (ret != ESP_OK)
    {
        ESP_LOGE(TAG, "Failed to set advertising data: %s", esp_err_to_name(ret));
        return;
    }

    // Set the scan response data.
    ret = esp_ble_gap_config_scan_rsp_data_raw(scan_rsp_data, sizeof(scan_rsp_data));
    if (ret != ESP_OK)
    {
        ESP_LOGE(TAG, "Failed to set scan response data: %s", esp_err_to_name(ret));
        return;
    }

    // *** Temporary: Try basic BLE advertising first ***
    ESP_LOGI(TAG, "Attempting basic BLE advertising...");
    esp_ble_adv_params_t adv_params = {
        .adv_int_min        = 0x20,
        .adv_int_max        = 0x40,
        .adv_type            = ADV_TYPE_NONCONN_IND,
        .own_addr_type       = BLE_ADDR_TYPE_PUBLIC,
        .channel_map         = ADV_CHNL_ALL,
        .adv_filter_policy   = ADV_FILTER_ALLOW_SCAN_ANY_CON_ANY,
    };

    ret = esp_ble_gap_start_advertising(&adv_params);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "Failed to start basic advertising: %s", esp_err_to_name(ret));
    } 
    
}