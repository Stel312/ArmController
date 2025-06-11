#include "ble_manager.h"
#include "gap_handler.h"
#include "gatt_server.h"
#include "esp_bt_main.h"
#include "nvs_flash.h"
#include "esp_log.h"
#include "esp_bt.h"
#include "esp_gatts_api.h"
#include "ble_defs.h"

void ble_init() {
    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_LOGI(BLE_IMU_TAG, "NVS partition truncated, erasing...");
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    ESP_ERROR_CHECK(ret);

    esp_bt_controller_config_t bt_cfg = BT_CONTROLLER_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_bt_controller_init(&bt_cfg));
    init_ble_service();
    if (esp_bt_controller_get_status() != ESP_BT_CONTROLLER_STATUS_ENABLED) {
        ESP_ERROR_CHECK(esp_bt_controller_enable(ESP_BT_MODE_BLE));
    }

    if (!esp_bluedroid_get_status()) {
        ESP_ERROR_CHECK(esp_bluedroid_init());
        ESP_ERROR_CHECK(esp_bluedroid_enable());
    }

    ESP_ERROR_CHECK(esp_ble_gap_register_callback(gap_event_handler));
    ESP_ERROR_CHECK(esp_ble_gatts_register_callback(gatt_event_handler));

    ret = esp_ble_gatts_app_register(PROFILE_APP_ID);
    if (ret == ESP_OK) {
        ESP_LOGI(GATT_SERVER_TAG, "GATT application registered successfully.");
    } else {
        ESP_LOGE(GATT_SERVER_TAG, "Failed to register GATT application. Error: %s", esp_err_to_name(ret));
    }
}

void ble_enable() {
    ESP_ERROR_CHECK(esp_ble_gap_set_device_name("ESP32 BLE"));
    ESP_ERROR_CHECK(esp_ble_gap_config_adv_data(&adv_data));

    static uint8_t adv_raw_data[] = {
        0x02, 0x01, 0x06,         
        0x0C, 0x09, 'E', 'S', 'P', '3', '2', ' ', 'B', 'L', 'E', '5', '0' 
    };
    ESP_ERROR_CHECK(esp_ble_gap_config_adv_data_raw(adv_raw_data, sizeof(adv_raw_data)));

    ESP_ERROR_CHECK(esp_ble_gap_start_advertising(&adv_params));

    ESP_LOGI(BLE_IMU_TAG, "Bluetooth initialized and advertising started.");
}

void ble_register_callbacks() {
    ESP_ERROR_CHECK(esp_ble_gap_register_callback(gap_event_handler));
    ESP_ERROR_CHECK(esp_ble_gatts_register_callback(gatt_event_handler));
}