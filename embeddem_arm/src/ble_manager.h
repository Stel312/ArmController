#ifndef BLE_MANAGER_H
#define BLE_MANAGER_H

#include "esp_gap_ble_api.h"
#include "esp_gatts_api.h"
#define TAG "BLEManager"
void ble_init();
void ble_enable();
void ble_register_callbacks();

#endif // BLE_MANAGER_H
