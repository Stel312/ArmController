#include <stdio.h>
#include <inttypes.h>   // Recommended for PRIu32 and fixed format specifiers
#include "esp_log.h"
#include "nvs_flash.h"
#include "esp_gap_ble_api.h"
#include "esp_gatts_api.h"
#include "esp_bt_main.h"
#include "driver/ledc.h"
#include "esp_err.h"
#include "esp_bt.h"

// Device information and UUID definitions
#define DEVICE_NAME         "ESP32S3_BLE"
#define SERVICE_UUID        0x180D
#define QUATERNION_UUID     0x2A37 // Used for X and Y angles (2 x uint16_t)
#define ACCELERATION_UUID   0x2A38
#define CLAW_UUID           0x2A39 // New UUID for Claw control (1 x uint16_t)
#define ESC_COMMAND_UUID    0x2A40 // New UUID for two PWM ESCs (2 x uint16_t)
#define INSTANCE_ID         0x01

static uint16_t service_handle;
static uint16_t quaternion_handle = 0;
static uint16_t acceleration_handle = 0;
static uint16_t claw_handle = 0; // New handle for claw characteristic
static uint16_t esc_command_handle = 0; // New handle for ESC command characteristic
static esp_gatt_if_t gatts_if;

// BLE Advertising Parameters
static esp_ble_adv_params_t adv_params = {
    .adv_int_min = 0x20,
    .adv_int_max = 0x40,
    .adv_type = ADV_TYPE_IND,
    .own_addr_type = BLE_ADDR_TYPE_PUBLIC,
    .peer_addr = {0},
    .peer_addr_type = BLE_ADDR_TYPE_PUBLIC,
    .channel_map = ADV_CHNL_ALL,
    .adv_filter_policy = ADV_FILTER_ALLOW_SCAN_ANY_CON_ANY,
};

/***************************************
 * Servo PWM via LEDC configuration
 ***************************************/

// Use LEDC_LOW_SPEED_MODE because ESP32-S3 supports only one mode.
#define LEDC_SPEED_MODE       LEDC_LOW_SPEED_MODE
#define LEDC_TIMER            LEDC_TIMER_0
#define LEDC_CHANNEL_X        LEDC_CHANNEL_0  // Servo on GPIO 18
#define LEDC_CHANNEL_Y        LEDC_CHANNEL_1  // Servo on GPIO 19
#define LEDC_CHANNEL_CLAW     LEDC_CHANNEL_2  // Claw on GPIO 17
#define LEDC_CHANNEL_ESC_LEFT LEDC_CHANNEL_3  // ESC Left on GPIO 20
#define LEDC_CHANNEL_ESC_RIGHT LEDC_CHANNEL_4 // ESC Right on GPIO 21
#define LEDC_TIMER_BIT        LEDC_TIMER_13_BIT   // 13-bit resolution (up to 8191)
#define LEDC_FREQUENCY        50   // 50 Hz update rate for servos and ESCs

// Initialize LEDC timer and channels for the servos and ESCs
void servo_init(void)
{
    // Configure LEDC timer
    ledc_timer_config_t ledc_timer = {
        .duty_resolution = LEDC_TIMER_BIT,
        .freq_hz = LEDC_FREQUENCY,
        .speed_mode = LEDC_SPEED_MODE,
        .timer_num = LEDC_TIMER,
        .clk_cfg = LEDC_AUTO_CLK,
    };
    ESP_ERROR_CHECK(ledc_timer_config(&ledc_timer));

    // Configure LEDC channel for servo X (GPIO 18)
    ledc_channel_config_t ledc_channel_x = {
        .channel    = LEDC_CHANNEL_X,
        .duty       = 0,            // Duty value will be updated later
        .gpio_num   = 18,
        .speed_mode = LEDC_SPEED_MODE,
        .hpoint     = 0,
        .timer_sel  = LEDC_TIMER,
    };
    ESP_ERROR_CHECK(ledc_channel_config(&ledc_channel_x));

    // Configure LEDC channel for servo Y (GPIO 19)
    ledc_channel_config_t ledc_channel_y = {
        .channel    = LEDC_CHANNEL_Y,
        .duty       = 0,            // Duty value will be updated later
        .gpio_num   = 19,
        .speed_mode = LEDC_SPEED_MODE,
        .hpoint     = 0,
        .timer_sel  = LEDC_TIMER,
    };
    ESP_ERROR_CHECK(ledc_channel_config(&ledc_channel_y));

    // Configure LEDC channel for claw (GPIO 17)
    ledc_channel_config_t ledc_channel_claw = {
        .channel    = LEDC_CHANNEL_CLAW,
        .duty       = 0,            // Duty value will be updated later
        .gpio_num   = 17,
        .speed_mode = LEDC_SPEED_MODE,
        .hpoint     = 0,
        .timer_sel  = LEDC_TIMER,
    };
    ESP_ERROR_CHECK(ledc_channel_config(&ledc_channel_claw));

    // Configure LEDC channel for ESC Left (GPIO 20)
    ledc_channel_config_t ledc_channel_esc_left = {
        .channel    = LEDC_CHANNEL_ESC_LEFT,
        .duty       = 0,            // Duty value will be updated later (e.g., neutral)
        .gpio_num   = 20,
        .speed_mode = LEDC_SPEED_MODE,
        .hpoint     = 0,
        .timer_sel  = LEDC_TIMER,
    };
    ESP_ERROR_CHECK(ledc_channel_config(&ledc_channel_esc_left));

    // Configure LEDC channel for ESC Right (GPIO 21)
    ledc_channel_config_t ledc_channel_esc_right = {
        .channel    = LEDC_CHANNEL_ESC_RIGHT,
        .duty       = 0,            // Duty value will be updated later (e.g., neutral)
        .gpio_num   = 21,
        .speed_mode = LEDC_SPEED_MODE,
        .hpoint     = 0,
        .timer_sel  = LEDC_TIMER,
    };
    ESP_ERROR_CHECK(ledc_channel_config(&ledc_channel_esc_right));

    ESP_LOGI("SERVO", "PWM initialized on GPIO 18 (X), 19 (Y), 17 (Claw), 20 (ESC Left), and 21 (ESC Right)");
}

/*
 * Update PWM duty cycle from incoming X and Y angles.
 *
 * The incoming x and y values are specified in hundredths of a degree.
 * (e.g., 9000 means 90.00°). We clamp each value to a maximum of 18000 (i.e., 180°).
 *
 * The typical servo expects a pulse width from ~1 ms (min) to ~2 ms (max)
 * over a 20 ms period (50 Hz). With a 13-bit resolution (max 8191),
 * these translate roughly into duty values of about 410 (1 ms) and 819 (2 ms).
 */
void update_xy_servo_positions(uint16_t x_value, uint16_t y_value)
{
    if (x_value > 18000) {
        x_value = 18000;
    }
    if (y_value > 18000) {
        y_value = 18000;
    }

    const uint32_t min_duty_servo = 410;  // ~1 ms pulse width
    const uint32_t max_duty_servo = 819;  // ~2 ms pulse width

    // Map the input range [0, 18000] to [min_duty_servo, max_duty_servo] linearly.
    uint32_t duty_x = min_duty_servo + ((max_duty_servo - min_duty_servo) * x_value) / 18000;
    uint32_t duty_y = min_duty_servo + ((max_duty_servo - min_duty_servo) * y_value) / 18000;

    // Use %u to print unsigned integers
    ESP_LOGI("SERVO", "Updating X/Y servos: X duty = %u, Y duty = %u for angles (%.2f°, %.2f°)",
             (unsigned int)duty_x, (unsigned int)duty_y, x_value / 100.0, y_value / 100.0);

    // Update the PWM duty cycle for the servo outputs
    ESP_ERROR_CHECK(ledc_set_duty(LEDC_SPEED_MODE, LEDC_CHANNEL_X, duty_x));
    ESP_ERROR_CHECK(ledc_update_duty(LEDC_SPEED_MODE, LEDC_CHANNEL_X));

    ESP_ERROR_CHECK(ledc_set_duty(LEDC_SPEED_MODE, LEDC_CHANNEL_Y, duty_y));
    ESP_ERROR_CHECK(ledc_update_duty(LEDC_SPEED_MODE, LEDC_CHANNEL_Y));
}

/*
 * Update PWM duty cycle for the claw from incoming angle.
 *
 * The incoming claw_value is specified in hundredths of a degree.
 * (e.g., 9000 means 90.00°). We clamp the value to a maximum of 18000 (i.e., 180°).
 *
 * The duty cycle range for the claw might be different from the X/Y servos.
 * Adjust min_duty_claw and max_duty_claw as needed for your specific claw servo.
 */
void update_claw_position(uint16_t claw_value)
{
    if (claw_value > 18000) {
        claw_value = 18000;
    }

    // Define specific min/max duty cycles for the claw servo if it's different
    // These are example values; you might need to tune them for your servo.
    const uint32_t min_duty_claw = 410; // Example: ~1 ms for fully open
    const uint32_t max_duty_claw = 819; // Example: ~2 ms for fully closed (or vice-versa)

    // Map the input range [0, 18000] to [min_duty_claw, max_duty_claw] linearly.
    uint32_t duty_claw = min_duty_claw + ((max_duty_claw - min_duty_claw) * claw_value) / 18000;

    ESP_LOGI("SERVO", "Updating Claw servo: Claw duty = %u for angle (%.2f°)",
             (unsigned int)duty_claw, claw_value / 100.0);

    ESP_ERROR_CHECK(ledc_set_duty(LEDC_SPEED_MODE, LEDC_CHANNEL_CLAW, duty_claw));
    ESP_ERROR_CHECK(ledc_update_duty(LEDC_SPEED_MODE, LEDC_CHANNEL_CLAW));
}

/*
 * Update PWM duty cycles for two ESCs (left and right).
 *
 * The incoming values (left_speed, right_speed) are expected to be uint16_t.
 * A common range for ESCs is 1000us (stopped) to 2000us (full throttle),
 * with 1500us being neutral. With 13-bit resolution (8191 max duty),
 * 1000us is approx. 410, 1500us is approx. 614, 2000us is approx. 819.
 *
 * For simplicity, we'll map an input range of 0-18000 (similar to servo angles)
 * where 0 is min, 9000 is neutral, and 18000 is max. You will need to calibrate
 * these values precisely for your ESCs.
 */
void update_esc_speeds(uint16_t left_speed, uint16_t right_speed)
{
    // Clamp values to the expected range if necessary
    if (left_speed > 18000) {
        left_speed = 18000;
    }
    if (right_speed > 18000) {
        right_speed = 18000;
    }

    // ESC pulse width typically from 1000us to 2000us
    // With 13-bit resolution (period = 20ms = 20000us)
    // Min duty (~1000us) = (1000us / 20000us) * 8191 = 409.55 -> ~410
    // Mid duty (~1500us) = (1500us / 20000us) * 8191 = 614.325 -> ~614
    // Max duty (~2000us) = (2000us / 20000us) * 8191 = 819.1 -> ~819
    const uint32_t esc_min_duty = 410; // Corresponds to ~1ms pulse
    const uint32_t esc_max_duty = 819; // Corresponds to ~2ms pulse

    // Map input (0-18000) to ESC duty cycle range (410-819)
    uint32_t duty_left = esc_min_duty + ((esc_max_duty - esc_min_duty) * left_speed) / 18000;
    uint32_t duty_right = esc_min_duty + ((esc_max_duty - esc_min_duty) * right_speed) / 18000;

    ESP_LOGI("ESC", "Updating ESCs: Left duty = %u, Right duty = %u for speeds (%u, %u)",
             (unsigned int)duty_left, (unsigned int)duty_right, left_speed, right_speed);

    ESP_ERROR_CHECK(ledc_set_duty(LEDC_SPEED_MODE, LEDC_CHANNEL_ESC_LEFT, duty_left));
    ESP_ERROR_CHECK(ledc_update_duty(LEDC_SPEED_MODE, LEDC_CHANNEL_ESC_LEFT));

    ESP_ERROR_CHECK(ledc_set_duty(LEDC_SPEED_MODE, LEDC_CHANNEL_ESC_RIGHT, duty_right));
    ESP_ERROR_CHECK(ledc_update_duty(LEDC_SPEED_MODE, LEDC_CHANNEL_ESC_RIGHT));
}


/***************************************
 * BLE Event Handlers
 ***************************************/

// GAP Event Handler
static void gap_event_handler(esp_gap_ble_cb_event_t event, esp_ble_gap_cb_param_t *param)
{
    ESP_LOGI("BLE", "GAP Event: %d", event);
    switch (event) {
        case ESP_GAP_BLE_ADV_DATA_SET_COMPLETE_EVT:
            ESP_LOGI("BLE", "Advertising data set, starting advertising...");
            esp_ble_gap_start_advertising(&adv_params);
            break;
        case ESP_GAP_BLE_AUTH_CMPL_EVT:
            ESP_LOGI("BLE", "BLE Authentication Completed, Status: %d",
                             param->ble_security.auth_cmpl.success);
            break;
        case ESP_GAP_BLE_UPDATE_CONN_PARAMS_EVT:
            ESP_LOGI("BLE", "Connection Parameters Updated: Min Interval: %d, Max Interval: %d, Latency: %d, Timeout: %d",
                             param->update_conn_params.min_int,
                             param->update_conn_params.max_int,
                             param->update_conn_params.latency,
                             param->update_conn_params.timeout);
            break;
        default:
            ESP_LOGW("BLE", "Unhandled GAP event: %d", event);
            break;
    }
}

// GATT Server Event Handler
static void gatts_event_handler(esp_gatts_cb_event_t event, esp_gatt_if_t gatt_if_param,
                                 esp_ble_gatts_cb_param_t *param)
{
    //ESP_LOGI("BLE", "GATTS Event: %d", event); // Uncomment for verbose event logging
    switch (event) {
        case ESP_GATTS_REG_EVT: {
            ESP_LOGI("BLE", "GATT server registered, creating service...");
            gatts_if = gatt_if_param;
            // Increased service handle count to accommodate new characteristics
            esp_gatt_srvc_id_t service_id = {
                .id.uuid.len = ESP_UUID_LEN_16,
                .id.uuid.uuid.uuid16 = SERVICE_UUID,
                .is_primary = true,
                .id.inst_id = INSTANCE_ID
            };
            esp_err_t ret = esp_ble_gatts_create_service(gatts_if, &service_id, 14); // Increased space again
            ESP_LOGI("BLE", "Service creation status: %x", ret);
            break;
        }
        case ESP_GATTS_CREATE_EVT: {
            ESP_LOGI("BLE", "Service created, adding characteristics...");
            service_handle = param->create.service_handle;
            ESP_LOGI("BLE", "Service Handle: %d", service_handle);

            // Quaternion characteristic (angles in hundredths of a degree: X, Y)
            // It expects 2 uint16_t values (4 bytes total).
            esp_gatt_char_prop_t quaternion_property = ESP_GATT_CHAR_PROP_BIT_READ |
                                                       ESP_GATT_CHAR_PROP_BIT_WRITE |
                                                       ESP_GATT_CHAR_PROP_BIT_NOTIFY;
            uint16_t quaternion_value[2] = {0, 0}; // X, Y angles
            esp_attr_value_t quaternion_attr_value = {
                .attr_max_len = sizeof(quaternion_value), // Max length for 2 uint16_t values (4 bytes)
                .attr_len = sizeof(quaternion_value),     // Initial length
                .attr_value = (uint8_t *)quaternion_value
            };
            esp_err_t ret = esp_ble_gatts_add_char(service_handle,
                                     &(esp_bt_uuid_t){.len = ESP_UUID_LEN_16, .uuid.uuid16 = QUATERNION_UUID},
                                     ESP_GATT_PERM_READ | ESP_GATT_PERM_WRITE,
                                     quaternion_property,
                                     &quaternion_attr_value,
                                     NULL);
            ESP_LOGE("BLE", "Quaternion characteristic add status: %x", ret);

            // Acceleration characteristic (unchanged)
            esp_gatt_char_prop_t acceleration_property = ESP_GATT_CHAR_PROP_BIT_READ |
                                                         ESP_GATT_CHAR_PROP_BIT_WRITE |
                                                         ESP_GATT_CHAR_PROP_BIT_NOTIFY;
            uint16_t acceleration_value[3] = {0, 0, 0};
            esp_attr_value_t acceleration_attr_value = {
                .attr_max_len = sizeof(acceleration_value),
                .attr_len = sizeof(acceleration_value),
                .attr_value = (uint8_t *)acceleration_value
            };
            ret = esp_ble_gatts_add_char(service_handle,
                                     &(esp_bt_uuid_t){.len = ESP_UUID_LEN_16, .uuid.uuid16 = ACCELERATION_UUID},
                                     ESP_GATT_PERM_READ | ESP_GATT_PERM_WRITE,
                                     acceleration_property,
                                     &acceleration_attr_value,
                                     NULL);
            ESP_LOGE("BLE", "Acceleration characteristic add status: %x", ret);

            // Claw characteristic (angle in hundredths of a degree: 1 x uint16_t)
            esp_gatt_char_prop_t claw_property = ESP_GATT_CHAR_PROP_BIT_READ |
                                                 ESP_GATT_CHAR_PROP_BIT_WRITE |
                                                 ESP_GATT_CHAR_PROP_BIT_NOTIFY;
            uint16_t claw_value[1] = {0}; // Single angle for claw
            esp_attr_value_t claw_attr_value = {
                .attr_max_len = sizeof(claw_value), // Max length for 1 uint16_t value (2 bytes)
                .attr_len = sizeof(claw_value),     // Initial length
                .attr_value = (uint8_t *)claw_value
            };
            ret = esp_ble_gatts_add_char(service_handle,
                                     &(esp_bt_uuid_t){.len = ESP_UUID_LEN_16, .uuid.uuid16 = CLAW_UUID},
                                     ESP_GATT_PERM_READ | ESP_GATT_PERM_WRITE,
                                     claw_property,
                                     &claw_attr_value,
                                     NULL);
            ESP_LOGE("BLE", "Claw characteristic add status: %x", ret);

            // New ESC Command characteristic (2 x uint16_t for Left and Right speeds/commands)
            esp_gatt_char_prop_t esc_command_property = ESP_GATT_CHAR_PROP_BIT_READ |
                                                        ESP_GATT_CHAR_PROP_BIT_WRITE |
                                                        ESP_GATT_CHAR_PROP_BIT_NOTIFY;
            uint16_t esc_command_value[2] = {0, 0}; // Left speed, Right speed
            esp_attr_value_t esc_command_attr_value = {
                .attr_max_len = sizeof(esc_command_value), // Max length for 2 uint16_t values (4 bytes)
                .attr_len = sizeof(esc_command_value),     // Initial length
                .attr_value = (uint8_t *)esc_command_value
            };
            ret = esp_ble_gatts_add_char(service_handle,
                                     &(esp_bt_uuid_t){.len = ESP_UUID_LEN_16, .uuid.uuid16 = ESC_COMMAND_UUID},
                                     ESP_GATT_PERM_READ | ESP_GATT_PERM_WRITE,
                                     esc_command_property,
                                     &esc_command_attr_value,
                                     NULL);
            ESP_LOGE("BLE", "ESC Command characteristic add status: %x", ret);

            ret = esp_ble_gatts_start_service(service_handle);
            ESP_LOGE("BLE", "Service start status: %x", ret);
            break;
        }
        case ESP_GATTS_ADD_CHAR_EVT: {
            ESP_LOGI("BLE", "Characteristic Added, Handle: %d", param->add_char.attr_handle);
            if (param->add_char.char_uuid.len == ESP_UUID_LEN_16) {
                if (param->add_char.char_uuid.uuid.uuid16 == QUATERNION_UUID) {
                    quaternion_handle = param->add_char.attr_handle;
                    ESP_LOGI("BLE", "Quaternion characteristic handle: %d", quaternion_handle);
                } else if (param->add_char.char_uuid.uuid.uuid16 == ACCELERATION_UUID) {
                    acceleration_handle = param->add_char.attr_handle;
                    ESP_LOGI("BLE", "Acceleration characteristic handle: %d", acceleration_handle);
                } else if (param->add_char.char_uuid.uuid.uuid16 == CLAW_UUID) {
                    claw_handle = param->add_char.attr_handle;
                    ESP_LOGI("BLE", "Claw characteristic handle: %d", claw_handle);
                } else if (param->add_char.char_uuid.uuid.uuid16 == ESC_COMMAND_UUID) { // Handle new ESC char
                    esc_command_handle = param->add_char.attr_handle;
                    ESP_LOGI("BLE", "ESC Command characteristic handle: %d", esc_command_handle);
                }
            }
            break;
        }
        case ESP_GATTS_WRITE_EVT: {
            // Log raw data for any characteristic write
            ESP_LOGI("BLE", "Write Event received. Handle: %d, Length: %d", param->write.handle, param->write.len);
            ESP_LOG_BUFFER_HEX("BLE Write Data (Raw)", param->write.value, param->write.len);

            // Check for quaternion handle and ensure 4 bytes for X, Y (2 x uint16_t)
            if (param->write.handle == quaternion_handle && param->write.len >= 4) {
                uint16_t x = (uint16_t)(param->write.value[0] | (param->write.value[1] << 8));
                uint16_t y = (uint16_t)(param->write.value[2] | (param->write.value[3] << 8));
                ESP_LOGI("BLE", "Received X/Y angles: X = %d (%.2f°), Y = %d (%.2f°)",
                                 x, x / 100.0, y, y / 100.0);
                update_xy_servo_positions(x, y);
            } else if (param->write.handle == claw_handle && param->write.len >= 2) {
                uint16_t claw_angle = (uint16_t)(param->write.value[0] | (param->write.value[1] << 8));
                ESP_LOGI("BLE", "Received Claw angle: %d (%.2f°)", claw_angle, claw_angle / 100.0);
                update_claw_position(claw_angle);
            } else if (param->write.handle == esc_command_handle && param->write.len >= 4) { // Handle ESC characteristic
                uint16_t left_speed = (uint16_t)(param->write.value[0] | (param->write.value[1] << 8));
                uint16_t right_speed = (uint16_t)(param->write.value[2] | (param->write.value[3] << 8));
                ESP_LOGI("BLE", "Received ESC speeds: Left = %u, Right = %u", left_speed, right_speed);
                update_esc_speeds(left_speed, right_speed);
            }
            else if (param->write.handle == acceleration_handle) {
                // Log acceleration data specifically
                ESP_LOGI("BLE", "Received Acceleration data on handle %d", acceleration_handle);
                // Further parsing/processing of acceleration data can be added here if needed
            }
            // IMPORTANT: Send a write response for PROPERTY_WRITE characteristics
            esp_ble_gatts_send_response(gatt_if_param, param->write.conn_id, param->write.trans_id,
                                         ESP_GATT_OK, NULL);
            break;
        }
        case ESP_GATTS_START_EVT:
            ESP_LOGI("BLE", "GATT service started successfully!");
            break;
        case ESP_GATTS_DISCONNECT_EVT:
            ESP_LOGI("BLE", "Client disconnected, restarting advertising...");
            esp_ble_gap_start_advertising(&adv_params);
            break;
        default:
            ESP_LOGW("BLE", "Unhandled GATTS event: %d", event);
            break;
    }
}

// BLE Initialization
void ble_app_init(void)
{
    esp_err_t ret;
    esp_bt_controller_config_t bt_cfg = BT_CONTROLLER_INIT_CONFIG_DEFAULT();
    
    ret = esp_bt_controller_init(&bt_cfg);
    if (ret != ESP_OK) {
        ESP_LOGE("BLE", "Bluetooth controller init failed: %x", ret);
        return;
    }
    
    // Release Classic BT memory if not needed to save RAM
    ret = esp_bt_controller_mem_release(ESP_BT_MODE_CLASSIC_BT);
    ESP_LOGE("BLE", "Bluetooth memory release status: %x", ret);

    ret = esp_bt_controller_enable(ESP_BT_MODE_BLE);
    ESP_LOGE("BLE", "Bluetooth enable status: %x", ret);

    ret = esp_bluedroid_init();
    ESP_LOGE("BLE", "Bluedroid init status: %x", ret);

    ret = esp_bluedroid_enable();
    ESP_LOGE("BLE", "Bluedroid enable status: %x", ret);

    ret = esp_ble_gatts_register_callback(gatts_event_handler);
    ESP_LOGE("BLE", "GATTS callback registration status: %x", ret);

    ret = esp_ble_gap_register_callback(gap_event_handler);
    ESP_LOGE("BLE", "GAP callback registration status: %x", ret);

    esp_ble_gap_set_device_name(DEVICE_NAME);

    // Set advertising data
    esp_ble_adv_data_t adv_data = {
        .set_scan_rsp = false,
        .include_name = true,
        .flag = ESP_BLE_ADV_FLAG_GEN_DISC | ESP_BLE_ADV_FLAG_BREDR_NOT_SPT,
        // Add service UUID to advertisement data if desired for discovery
        // .p_service_uuid = (uint8_t *)&SERVICE_UUID,
        // .service_uuid_len = ESP_UUID_LEN_16,
    };

    ret = esp_ble_gap_config_adv_data(&adv_data);
    ESP_LOGE("BLE", "Config adv data status: %x", ret);

    ESP_LOGI("BLE", "Bluedroid initialized successfully");

    ret = esp_ble_gatts_app_register(INSTANCE_ID);
    ESP_LOGE("BLE", "GATTS app register status: %x", ret);
}

void app_main(void)
{
    ESP_LOGI("BLE", "Starting BLE application");
    // Initialize NVS (Non-Volatile Storage) for Wi-Fi and Bluetooth configuration
    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    ESP_ERROR_CHECK(ret);

    servo_init(); // Initialize LEDC for servos and ESCs
    ble_app_init(); // Initialize BLE
}
