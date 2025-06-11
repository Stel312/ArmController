#include "gatt_server.h"
#include "gap_handler.h"
#include "ble_defs.h"
#include "esp_log.h"
#include "esp_gatts_api.h"
#include "esp_gattc_api.h"
#include <string.h>

#define IMU_SERVICE_ATTR_COUNT 5 // Number of attributes in imu_gatt_db[]

uint16_t imu_service_handle = 0;
uint16_t accel_char_handle = 0;
uint16_t gyro_char_handle = 0;
esp_gatt_if_t gatt_if = ESP_GATT_IF_NONE;

void gatt_event_handler(esp_gatts_cb_event_t event, esp_gatt_if_t gatts_if, esp_ble_gatts_cb_param_t *param) {
    switch (event) {
        case ESP_GATTS_REG_EVT:
            ESP_LOGI(GATT_SERVER_TAG, "[GATT] Registering IMU service...");
            gatt_if = gatts_if;

            imu_service_id.id.uuid = var_imu_service_uuid;  // Ensure UUID is assigned
            imu_service_id.id.inst_id = 0;
            imu_service_id.is_primary = true;
            
            ESP_LOGI(GATT_SERVER_TAG, "[GATT] IMU Service UUID: %02X%02X%02X%02X-%02X%02X-%02X%02X-%02X%02X-%02X%02X%02X%02X%02X%02X",
            imu_service_id.id.uuid.uuid.uuid128[0], imu_service_id.id.uuid.uuid.uuid128[1],
            imu_service_id.id.uuid.uuid.uuid128[2], imu_service_id.id.uuid.uuid.uuid128[3],
            imu_service_id.id.uuid.uuid.uuid128[4], imu_service_id.id.uuid.uuid.uuid128[5],
            imu_service_id.id.uuid.uuid.uuid128[6], imu_service_id.id.uuid.uuid.uuid128[7],
            imu_service_id.id.uuid.uuid.uuid128[8], imu_service_id.id.uuid.uuid.uuid128[9],
            imu_service_id.id.uuid.uuid.uuid128[10], imu_service_id.id.uuid.uuid.uuid128[11],
            imu_service_id.id.uuid.uuid.uuid128[12], imu_service_id.id.uuid.uuid.uuid128[13],
            imu_service_id.id.uuid.uuid.uuid128[14], imu_service_id.id.uuid.uuid.uuid128[15]);

            ESP_LOGI(GATT_SERVER_TAG, "[GATT] IMU Service Instance ID: %d", imu_service_id.id.inst_id);
            ESP_LOGI(GATT_SERVER_TAG, "[GATT] IMU Service Primary Flag: %d", imu_service_id.is_primary);



            ESP_LOGI(GATT_SERVER_TAG, "[GATT] Printing IMU GATT Table...");
    
            for (int i = 0; i < IMU_SERVICE_ATTR_COUNT; i++) {
                ESP_LOGI(GATT_SERVER_TAG, "[GATT] Entry[%d]:", i);
                ESP_LOGI(GATT_SERVER_TAG, "  UUID Length: %d", imu_gatt_db[i].att_desc.uuid_length);
                
                if (imu_gatt_db[i].att_desc.uuid_length == ESP_UUID_LEN_128) {
                    ESP_LOGI(GATT_SERVER_TAG, "  UUID: %02X%02X%02X%02X-%02X%02X-%02X%02X-%02X%02X-%02X%02X-%02X%02X-%02X%02X",
                        imu_gatt_db[i].att_desc.uuid_p[0], imu_gatt_db[i].att_desc.uuid_p[1],
                        imu_gatt_db[i].att_desc.uuid_p[2], imu_gatt_db[i].att_desc.uuid_p[3],
                        imu_gatt_db[i].att_desc.uuid_p[4], imu_gatt_db[i].att_desc.uuid_p[5],
                        imu_gatt_db[i].att_desc.uuid_p[6], imu_gatt_db[i].att_desc.uuid_p[7],
                        imu_gatt_db[i].att_desc.uuid_p[8], imu_gatt_db[i].att_desc.uuid_p[9],
                        imu_gatt_db[i].att_desc.uuid_p[10], imu_gatt_db[i].att_desc.uuid_p[11],
                        imu_gatt_db[i].att_desc.uuid_p[12], imu_gatt_db[i].att_desc.uuid_p[13],
                        imu_gatt_db[i].att_desc.uuid_p[14], imu_gatt_db[i].att_desc.uuid_p[15]);
                } else {
                    ESP_LOGI(GATT_SERVER_TAG, "  UUID: %04X", *(uint16_t*)imu_gatt_db[i].att_desc.uuid_p);
                }

                ESP_LOGI(GATT_SERVER_TAG, "  Permissions: %d", imu_gatt_db[i].att_desc.perm);
                ESP_LOGI(GATT_SERVER_TAG, "  Max Length: %d", imu_gatt_db[i].att_desc.max_length);
                ESP_LOGI(GATT_SERVER_TAG, "  Current Length: %d", imu_gatt_db[i].att_desc.length);
                ESP_LOGI(GATT_SERVER_TAG, "  Value Pointer: %p", imu_gatt_db[i].att_desc.value);
            }

            esp_err_t status = esp_ble_gatts_create_attr_tab(imu_gatt_db, gatts_if, IMU_SERVICE_ATTR_COUNT, PROFILE_APP_ID);
            ESP_LOGI(GATT_SERVER_TAG, "[GATT] Attempted Service Table Registration. Status: %s", esp_err_to_name(status));
            break;

        case ESP_GATTS_CREAT_ATTR_TAB_EVT:
            ESP_LOGI(GATT_SERVER_TAG, "[GATT] Attribute Table Created!");
            ESP_LOGI(GATT_SERVER_TAG, "[GATT] Assigned GATT Interface: %d", gatt_if);
            char uuid_str[37]; // UUIDs are typically 36 characters + null terminator
            esp_bt_uuid_t *uuid = &param->add_attr_tab.svc_uuid;
            snprintf(uuid_str, sizeof(uuid_str), "%02X%02X%02X%02X-%02X%02X-%02X%02X-%02X%02X-%02X%02X%02X%02X%02X%02X",
                    uuid->uuid.uuid128[0], uuid->uuid.uuid128[1], uuid->uuid.uuid128[2], uuid->uuid.uuid128[3],
                    uuid->uuid.uuid128[4], uuid->uuid.uuid128[5], uuid->uuid.uuid128[6], uuid->uuid.uuid128[7],
                    uuid->uuid.uuid128[8], uuid->uuid.uuid128[9], uuid->uuid.uuid128[10], uuid->uuid.uuid128[11],
                    uuid->uuid.uuid128[12], uuid->uuid.uuid128[13], uuid->uuid.uuid128[14], uuid->uuid.uuid128[15]);

            ESP_LOGI(GATT_SERVER_TAG, "[GATT] Service UUID: %s", uuid_str);

            if (param->add_attr_tab.status != ESP_GATT_OK) {
                ESP_LOGE(GATT_SERVER_TAG, "[GATT] Failed to create attribute table! Status: %d", param->add_attr_tab.status);
                return;
            }

            ESP_LOGI(GATT_SERVER_TAG, "[GATT] Total Attributes Received: %d", param->add_attr_tab.num_handle);

            for (int i = 0; i < param->add_attr_tab.num_handle; i++) {
                ESP_LOGI(GATT_SERVER_TAG, "[GATT] Handle[%d]: %d", i, param->add_attr_tab.handles[i]);
            }

            // Ensure correct number of attributes before assigning handles
            if (param->add_attr_tab.num_handle < IMU_SERVICE_ATTR_COUNT) {
                ESP_LOGE(GATT_SERVER_TAG, "[GATT] Received attribute count is incorrect! Expected: %d, Received: %d", 
                        IMU_SERVICE_ATTR_COUNT, param->add_attr_tab.num_handle);
                return;
            }

            imu_service_handle = param->add_attr_tab.handles[0];
            accel_char_handle = param->add_attr_tab.handles[1];
            gyro_char_handle = param->add_attr_tab.handles[3];

            if (imu_service_handle == 0 || accel_char_handle == 0 || gyro_char_handle == 0) {
                ESP_LOGE(GATT_SERVER_TAG, "[GATT] Attribute table created, but handles are invalid!");
                return;
            }

            ESP_LOGI(GATT_SERVER_TAG, "[GATT] IMU Service Handle: %d", imu_service_handle);
            ESP_LOGI(GATT_SERVER_TAG, "[GATT] Acceleration Characteristic Handle: %d", accel_char_handle);
            ESP_LOGI(GATT_SERVER_TAG, "[GATT] Gyroscope Characteristic Handle: %d", gyro_char_handle);

            esp_err_t ret = esp_ble_gatts_start_service(imu_service_handle);
            ESP_LOGI(GATT_SERVER_TAG, "[GATT] Starting service... Status: %s", esp_err_to_name(ret));
            break;


        case ESP_GATTS_START_EVT:
            ESP_LOGI(GATT_SERVER_TAG, "IMU Service started, handle: %d", param->start.service_handle);
            break;

        case ESP_GATTS_CONNECT_EVT:
            ESP_LOGI(GATT_SERVER_TAG, "Device connected, conn_id: %d", param->connect.conn_id);
            break;

        case ESP_GATTS_DISCONNECT_EVT:
            ESP_LOGI(GATT_SERVER_TAG, "Device disconnected, restarting advertising...");
            start_advertising();
            break;

        case ESP_GATTS_READ_EVT:
            ESP_LOGI(GATT_SERVER_TAG, "Read request received, handle: %d", param->read.handle);
            break;

        case ESP_GATTS_WRITE_EVT:
            ESP_LOGI(GATT_SERVER_TAG, "Write request received, handle: %d", param->write.handle);
            break;

        case ESP_GATTS_EXEC_WRITE_EVT:
            ESP_LOGI(GATT_SERVER_TAG, "Execute write request received");
            break;

        default:
            ESP_LOGW(GATT_SERVER_TAG, "Unhandled event: %d", event);
            break;
    }
}
