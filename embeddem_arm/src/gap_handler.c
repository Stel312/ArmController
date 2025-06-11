#include "gap_handler.h"
#include "ble_defs.h"
#include "esp_log.h"


void gap_event_handler(esp_gap_ble_cb_event_t event, esp_ble_gap_cb_param_t *param) {
    switch (event) {
        case ESP_GAP_BLE_ADV_DATA_SET_COMPLETE_EVT:
            ESP_LOGI(GAP_HANDLER_TAG, "[GAP] Advertising data set successfully!");
            esp_ble_gap_start_advertising(&adv_params);
            break;
        case ESP_GAP_BLE_SCAN_RSP_DATA_SET_COMPLETE_EVT:
            ESP_LOGI(GAP_HANDLER_TAG, "[GAP] Scan response data set successfully!");
            esp_ble_gap_start_advertising(&adv_params);
            break;
        case ESP_GAP_BLE_ADV_DATA_RAW_SET_COMPLETE_EVT:
            ESP_LOGI(GAP_HANDLER_TAG, "[GAP] Raw advertising data set successfully!");
            esp_ble_gap_start_advertising(&adv_params);
            break;
        case ESP_GAP_BLE_ADV_START_COMPLETE_EVT:
            if (param->adv_start_cmpl.status == ESP_BT_STATUS_SUCCESS) {
                ESP_LOGI(GAP_HANDLER_TAG, "[GAP] BLE advertising started successfully!");
            } else {
                ESP_LOGE(GAP_HANDLER_TAG, "[GAP] BLE advertising failed to start: %s", esp_err_to_name(param->adv_start_cmpl.status));
            }
            break;
        case ESP_GAP_BLE_ADV_STOP_COMPLETE_EVT:
            ESP_LOGI(GAP_HANDLER_TAG, "[GAP] BLE advertising stopped.");
            break;
        case ESP_GAP_BLE_UPDATE_CONN_PARAMS_EVT:
            ESP_LOGI(GAP_HANDLER_TAG, "[GAP] Connection parameters updated! Min interval: %d, Max interval: %d, Latency: %d, Timeout: %d",
                     param->update_conn_params.min_int, param->update_conn_params.max_int,
                     param->update_conn_params.latency, param->update_conn_params.timeout);
            break;
        case ESP_GAP_BLE_SET_PKT_LENGTH_COMPLETE_EVT:
            ESP_LOGI(GAP_HANDLER_TAG, "[GAP] Packet length set successfully! TX packet length: %d",
                    param->pkt_data_length_cmpl.params.tx_len);
            break;

        default:
            ESP_LOGW(GAP_HANDLER_TAG, "[GAP] Unhandled GAP event: %d", event);
            break;
    }
}


void start_advertising() {
    ESP_LOGI(GAP_HANDLER_TAG, "Starting BLE advertising...");
    esp_err_t ret = esp_ble_gap_start_advertising(&adv_params);
    ESP_LOGI(GAP_HANDLER_TAG, "Advertising start status: %s", esp_err_to_name(ret));
}

