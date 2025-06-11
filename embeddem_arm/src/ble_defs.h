#ifndef GAP_HANDLER_TAG
#define GAP_HANDLER_TAG "GAPHandler"
#endif

#ifndef GATT_SERVER_TAG
#define GATT_SERVER_TAG "GATTServer"
#endif

#ifndef BLE_IMU_TAG
#define BLE_IMU_TAG "BLE_IMU_EXAMPLE"
#endif

#ifndef BLE_IMU_DEFS_H
#define BLE_IMU_DEFS_H

#include "esp_gap_ble_api.h"
#include "esp_gatts_api.h"
#include "esp_bt_defs.h"

#define BLE_IMU_TAG "BLE_IMU_EXAMPLE"
#define PROFILE_NUM 1
#define PROFILE_APP_ID 1
#define DEFAULT_MTU 23
// IMU Service UUID
static esp_bt_uuid_t var_imu_service_uuid = {
    .len = ESP_UUID_LEN_128,
    .uuid.uuid128 = {0xa1, 0xb2, 0xc3, 0xd4, 0xe5, 0xf6, 0x78, 0x90,
                     0x12, 0x34, 0x56, 0x78, 0x90, 0xab, 0xcd, 0xef}
};


static esp_gatt_srvc_id_t imu_service_id ;
static esp_bt_uuid_t accel_char_uuid = {
    .len = ESP_UUID_LEN_128,
    .uuid.uuid128 = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                     0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10}
};

// Rotation (Gyro) Characteristic UUID
static esp_bt_uuid_t gyro_char_uuid = {
    .len = ESP_UUID_LEN_128,
    .uuid.uuid128 = {0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
                     0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F, 0x20}
};

// Characteristic Properties
static esp_gatt_char_prop_t imu_char_props = ESP_GATT_CHAR_PROP_BIT_READ |
    ESP_GATT_CHAR_PROP_BIT_NOTIFY | ESP_GATT_CHAR_PROP_BIT_WRITE;

// Default IMU Data
static uint8_t imu_accel_val[6] = { 0x00, 0x01, 0x02, 0x03, 0x04, 0x05 };
static uint8_t imu_gyro_val[6] = { 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B };

// GATT attribute values
static esp_attr_value_t accel_attr_val = {
    .attr_max_len = sizeof(imu_accel_val),
    .attr_len = sizeof(imu_accel_val),
    .attr_value = imu_accel_val,
};

static esp_attr_value_t gyro_attr_val = {
    .attr_max_len = sizeof(imu_gyro_val),
    .attr_len = sizeof(imu_gyro_val),
    .attr_value = imu_gyro_val,
};

// Characteristic User Description UUID
static esp_bt_uuid_t cud_desc_uuid = {
    .len = ESP_UUID_LEN_16,
    .uuid.uuid16 = ESP_GATT_UUID_CHAR_DESCRIPTION
};

// Descriptor Attributes
static uint8_t accel_cud_desc_val[] = "Linear Acceleration (X, Y, Z)";
static uint8_t gyro_cud_desc_val[] = "Rotation (X, Y, Z)";

static esp_attr_value_t accel_cud_desc_attr = {
    .attr_max_len = sizeof(accel_cud_desc_val),
    .attr_len = sizeof(accel_cud_desc_val),
    .attr_value = accel_cud_desc_val,
};

static esp_attr_value_t gyro_cud_desc_attr = {
    .attr_max_len = sizeof(gyro_cud_desc_val),
    .attr_len = sizeof(gyro_cud_desc_val),
    .attr_value = gyro_cud_desc_val,
};

// Advertising Data
static esp_ble_adv_data_t adv_data = {
    .set_scan_rsp = true,
    .include_name = true,
    .service_data_len = 16,  
    .p_service_uuid = var_imu_service_uuid.uuid.uuid128, 
};

// Advertising Parameters
static esp_ble_adv_params_t adv_params = {
    .adv_int_min = 0x20,
    .adv_int_max = 0x60,
    .adv_type = ADV_TYPE_IND,
    .own_addr_type = BLE_ADDR_TYPE_PUBLIC,
    .channel_map = ADV_CHNL_ALL,
    .adv_filter_policy = ADV_FILTER_ALLOW_SCAN_ANY_CON_ANY,
};


extern uint16_t imu_service_handle;
extern uint16_t accel_char_handle;
extern uint16_t gyro_char_handle;
extern esp_gatt_if_t gatt_if;

// IMU Service Table
static const esp_gatts_attr_db_t imu_gatt_db[] = {
    [0] = {
        {ESP_GATT_AUTO_RSP},
        {ESP_UUID_LEN_128, var_imu_service_uuid.uuid.uuid128, ESP_GATT_PERM_READ,
         sizeof(uint16_t), sizeof(uint16_t), NULL} // Check if this correctly references var_imu_service_uuid
    }
};


// Function to Initialize Service ID
static inline void init_ble_service() {
    imu_service_id.id.uuid = var_imu_service_uuid;
    imu_service_id.id.inst_id = 0;
    imu_service_id.is_primary = true;
}

#endif // BLE_IMU_DEFS_H
