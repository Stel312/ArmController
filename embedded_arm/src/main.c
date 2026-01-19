#include <stdio.h>
#include <inttypes.h>
#include "esp_log.h"
#include "nvs_flash.h"
#include "esp_gap_ble_api.h"
#include "esp_gatts_api.h"
#include "esp_bt_main.h"
#include "driver/ledc.h"
#include "esp_err.h"
#include "esp_bt.h"
#include "esp_timer.h"
#include <math.h> // For M_PI, cos, sin, etc.
#include <string.h>

// Device information and UUID definitions
#define DEVICE_NAME         "ESP32S3_BLE"
#define SERVICE_UUID        0x180D
#define QUATERNION_UUID     0x2A37
#define ACCELERATION_UUID   0x2A38
#define CLAW_UUID           0x2A39
#define ESC_COMMAND_UUID    0x2A40
#define STEPPER_UUID        0x2A41
#define ARM_ANGLE_UUID      0x2A42


#define INSTANCE_ID         0x01
#define SERVO1_START 0
#define SERVO2_START 270
#define SERVO3_START 0

#define SERVO_MIN_PULSEWIDTH  500
#define SERVO_MAX_PULSEWIDTH  2500
#define SERVO_MAX_ANGLE       270

// Your arm's link lengths in mm
#define L1   247.0
#define L2   247.0
#define L3   247.0


//Bluetooth stack connection info
static esp_gatt_if_t gatts_if;
static  uint16_t conn_id;
static int64_t last_write_time = 0; // Track time of last characteristic write


static uint16_t service_handle;
static uint16_t quaternion_handle = 0;
static uint16_t acceleration_handle = 0;
static uint16_t claw_handle = 0; // New handle for claw characteristic
static uint16_t esc_command_handle = 0; // New handle for ESC command characteristic
static uint16_t arm_angle_handle = 0;




static uint16_t servo_angle_1 = 0;
static uint16_t servo_angle_2 = 0;
static uint16_t servo_angle_3 = 0;


// --- Control Loop Global Variables ---
// Filtered acceleration and velocity estimates
static float filtered_accel_y = 0.0;
static float filtered_accel_z = 0.0;
static float estimated_vel_y = 0.0;
static float estimated_vel_z = 0.0;

// Current target position for the robot arm's end-effector
// Initial position set in app_main
static float target_pos_y = 0.0;
static float target_pos_z = 0.0;
static float target_phi_deg = 0.0;

// --- Constants for tuning ---
#define ALPHA 0.1 // Low-pass filter constant (0 to 1, lower is smoother)
#define TIME_STEP 0.02 // Time step in seconds (e.g., 20ms assuming 50Hz updates)
#define VEL_SCALE 150.0 // Adjust to change sensitivity of arm movement
#define DRIFT_THRESHOLD 0.2 // Accel threshold to detect "still" state


esp_ble_gap_ext_adv_t adv_inst = {
    .instance = 0,
    .duration = 0,
    .max_events =0,
};

esp_ble_gap_ext_adv_params_t ext_adv_params_2M = {
  .type = ESP_BLE_GAP_SET_EXT_ADV_PROP_CONNECTABLE,
  .interval_min = 0x40,
  .interval_max = 0x40,
  .channel_map = ADV_CHNL_ALL,
  .own_addr_type = BLE_ADDR_TYPE_PUBLIC,
  .peer_addr_type = BLE_ADDR_TYPE_PUBLIC,
  .peer_addr = {0, 0, 0, 0, 0, 0},
  .filter_policy = ADV_FILTER_ALLOW_SCAN_ANY_CON_ANY,
  .tx_power = EXT_ADV_TX_PWR_NO_PREFERENCE,
  .primary_phy = ESP_BLE_GAP_PHY_1M,
  .max_skip = 0,
  .secondary_phy = ESP_BLE_GAP_PHY_2M,
  .sid = 1,
  .scan_req_notif = false,
};
/*
// BLE Advertising Parameters
static esp_ble_adv_params_t adv_params = {
    .adv_int_min = 0x20,
    .adv_int_max = 0x40,
    .adv_type = ADV_TYPE_IND,
    .own_addr_type = BLE_ADDR_TYPE_PUBLIC,
    .peer_addr_type = BLE_ADDR_TYPE_PUBLIC,
    .peer_addr = {0}, //peer address
    .channel_map = ADV_CHNL_ALL,
    .adv_filter_policy = ADV_FILTER_ALLOW_SCAN_ANY_CON_ANY,
    
};
*/

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

#define LEDC_CHANNEL_SERVO_1 LEDC_CHANNEL_5 // Lowest Servo 
#define LEDC_CHANNEL_SERVO_2 LEDC_CHANNEL_6 // medium servo
#define LEDC_CHANNEL_SERVO_3 LEDC_CHANNEL_7 // Highest Servo

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

    ledc_channel_config_t ledc_channel_servo_1 = {
        .channel    = LEDC_CHANNEL_SERVO_1,
        .duty       = 0,            // Duty value will be updated later (e.g., neutral)
        .gpio_num   = 14,
        .speed_mode = LEDC_SPEED_MODE,
        .hpoint     = 0,
        .timer_sel  = LEDC_TIMER,
    };
    ESP_ERROR_CHECK(ledc_channel_config(&ledc_channel_servo_1));

    ledc_channel_config_t ledc_channel_servo_2 = {
        .channel    = LEDC_CHANNEL_SERVO_2,
        .duty       = 0,            // Duty value will be updated later (e.g., neutral)
        .gpio_num   = 15,
        .speed_mode = LEDC_SPEED_MODE,
        .hpoint     = 0,
        .timer_sel  = LEDC_TIMER,
    };
    ESP_ERROR_CHECK(ledc_channel_config(&ledc_channel_servo_2));

    ledc_channel_config_t ledc_channel_servo_3 = {
        .channel    = LEDC_CHANNEL_SERVO_3,
        .duty       = 0,            // Duty value will be updated later (e.g., neutral)
        .gpio_num   = 16,
        .speed_mode = LEDC_SPEED_MODE,
        .hpoint     = 0,
        .timer_sel  = LEDC_TIMER,
    };
    ESP_ERROR_CHECK(ledc_channel_config(&ledc_channel_servo_3));




    ESP_LOGI("SERVO", "PWM initialized on GPIO 18 (X), 19 (Y), 17 (Claw), 20 (ESC Left), and 21 (ESC Right)");
}


void set_servo_angle(ledc_channel_t channel, int angle)
{
    // Step 1: Map angle to pulse width
    int pulse_width_us = SERVO_MIN_PULSEWIDTH + ((SERVO_MAX_PULSEWIDTH - SERVO_MIN_PULSEWIDTH) * angle) / SERVO_MAX_ANGLE;

    // Step 2: Convert pulse width to LEDC duty cycle value
    // Assuming a 50Hz frequency and 13-bit duty resolution (8192-1)
    // Period = 1,000,000 us / 50 Hz = 20,000 us
    uint32_t duty_value = (pulse_width_us * 8191) / 20000;
    printf("Setting servo on channel %d to angle %d (pulse width: %d us, duty: %u)\n", channel, angle, pulse_width_us, (unsigned int)duty_value);
    // Update the PWM duty cycle
    ESP_ERROR_CHECK(ledc_set_duty(LEDC_SPEED_MODE, channel, duty_value));
    ESP_ERROR_CHECK(ledc_update_duty(LEDC_SPEED_MODE, channel));
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
void update_xy_servo_positions(int16_t x_value, int16_t y_value)
{   
    set_servo_angle(LEDC_CHANNEL_X, x_value);
    set_servo_angle(LEDC_CHANNEL_Y, y_value);

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
    set_servo_angle(LEDC_CHANNEL_CLAW, claw_value);
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

void init_servo_arm_position()
{
    servo_angle_1 = SERVO1_START;
    servo_angle_2 = SERVO2_START;
    servo_angle_3 = SERVO3_START;

    set_servo_angle(LEDC_CHANNEL_SERVO_1, SERVO1_START);
    set_servo_angle(LEDC_CHANNEL_SERVO_2, SERVO2_START);
    set_servo_angle(LEDC_CHANNEL_SERVO_3, SERVO3_START);


}

// --- Inverse Kinematics for a 3-DOF Arm on the Y-Z plane ---
// Solves for the 3 joint angles given a target (y,z) and orientation (phi)
bool solve_inverse_kinematics_yz(float y_target, float z_target, float phi_target_rad, float* theta1_deg, float* theta2_deg, float* theta3_deg) {
    // 1. Calculate the wrist position
    float y_wrist = y_target - L3 * cos(phi_target_rad);
    float z_wrist = z_target - L3 * sin(phi_target_rad);

    // 2. Solve for the second joint angle (theta2) using Law of Cosines
    float D = sqrt(y_wrist * y_wrist + z_wrist * z_wrist);
    if (D > (L1 + L2) || D < fabs(L1 - L2)) {
        return false; // Position is unreachable
    }

    float cos_theta2_prime = (L1*L1 + L2*L2 - D*D) / (2 * L1 * L2);
    if (cos_theta2_prime > 1.0) cos_theta2_prime = 1.0;
    if (cos_theta2_prime < -1.0) cos_theta2_prime = -1.0;

    float theta2_prime_rad = acos(cos_theta2_prime);
    float theta2_rad = M_PI - theta2_prime_rad;

    // 3. Solve for the first joint angle (theta1)
    float alpha = atan2(z_wrist, y_wrist);
    float beta = atan2(L2 * sin(theta2_rad), L1 + L2 * cos(theta2_rad));
    float theta1_rad = alpha - beta;

    // 4. Solve for the third joint angle (theta3)
    float theta3_rad = phi_target_rad - (theta1_rad + theta2_rad);

    // 5. Convert to degrees and normalize
    *theta1_deg = fmod(theta1_rad * 180.0 / M_PI, 360.0);
    if (*theta1_deg < 0) *theta1_deg += 360.0;
    
    *theta2_deg = fmod(theta2_rad * 180.0 / M_PI, 360.0);
    if (*theta2_deg < 0) *theta2_deg += 360.0;

    *theta3_deg = fmod(theta3_rad * 180.0 / M_PI, 360.0);
    if (*theta3_deg < 0) *theta3_deg += 360.0;

    return true;
}

// --- Update robot arm position based on filtered acceleration ---
void update_arm_position_from_accel(float raw_accel_y, float raw_accel_z) {
    // 1. Apply a low-pass filter to the raw acceleration data
    filtered_accel_y = ALPHA * raw_accel_y + (1 - ALPHA) * filtered_accel_y;
    filtered_accel_z = ALPHA * raw_accel_z + (1 - ALPHA) * filtered_accel_z;

    // 2. Check for a "still" state to correct for drift
    if (fabs(filtered_accel_y) < DRIFT_THRESHOLD && fabs(filtered_accel_z) < DRIFT_THRESHOLD) {
        estimated_vel_y = 0.0;
        estimated_vel_z = 0.0;
    } else {
        // 3. Integrate filtered acceleration to get velocity
        estimated_vel_y += filtered_accel_y * TIME_STEP * VEL_SCALE;
        estimated_vel_z += filtered_accel_z * TIME_STEP * VEL_SCALE;

        // 4. Integrate velocity to update the target position
        target_pos_y += estimated_vel_y * TIME_STEP;
        target_pos_z += estimated_vel_z * TIME_STEP;
    }

    // 5. Clamp the target position to a safe workspace
    // These values must be determined based on your arm's reach
    if (target_pos_y > 400.0) target_pos_y = 400.0;
    if (target_pos_y < 0.0) target_pos_y = 0.0;
    if (target_pos_z > 400.0) target_pos_z = 400.0;
    if (target_pos_z < 0.0) target_pos_z = 0.0;

    // 6. Use inverse kinematics to update the servos
    float angle1, angle2, angle3;
    if (solve_inverse_kinematics_yz(target_pos_y, target_pos_z, target_phi_deg * M_PI / 180.0, &angle1, &angle2, &angle3)) {
        set_servo_angle(LEDC_CHANNEL_SERVO_1, (int)angle1);
        set_servo_angle(LEDC_CHANNEL_SERVO_2, (int)angle2);
        set_servo_angle(LEDC_CHANNEL_SERVO_3, (int)angle3);
    } else {
        ESP_LOGE("IK", "Target position (%.2f, %.2f) is unreachable!", target_pos_y, target_pos_z);
    }
}

/***************************************
 * BLE Event Handlers
 ***************************************/


void set_ext_name(uint8_t instance, const char* name) {
    uint8_t buffer[50]; // Plenty for a name
    int pos = 0;

    // 1. Add Flags (Standard)
    buffer[pos++] = 0x02; // Length
    buffer[pos++] = 0x01; // Type: Flags
    buffer[pos++] = 0x06; // Value

    // 2. Add Name Automatically
    uint8_t name_len = strlen(name);
    buffer[pos++] = name_len + 1; // Length of data + type byte
    buffer[pos++] = 0x09;        // Type: Complete Local Name
    memcpy(&buffer[pos], name, name_len);
    pos += name_len;

    // Send the constructed buffer to the stack
    esp_ble_gap_config_ext_adv_data_raw(instance, pos, buffer);
}
// GAP Event Handler
static void gap_event_handler(esp_gap_ble_cb_event_t event, esp_ble_gap_cb_param_t *param)
{
    ESP_LOGI("BLE", "GAP Event: %d", event);
    switch (event) {
        case ESP_GAP_BLE_ADV_DATA_SET_COMPLETE_EVT:
            ESP_LOGI("BLE", "Advertising data set, starting advertising...");
            //esp_ble_gap_start_advertising(&adv_params);
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
        case ESP_GAP_BLE_SET_PREFERRED_PHY_COMPLETE_EVT:
            ESP_LOGI("BLE", "Set prefered Phy layer Status: %d", param->set_perf_phy.status);
            break;
        case ESP_GAP_BLE_PHY_UPDATE_COMPLETE_EVT:
            ESP_LOGI("BLE", "Set prefered Phy layer Rx: %d, Tx: %d, Status: %d", param->phy_update.rx_phy, param->phy_update.tx_phy, param->phy_update.status);
            break;
        case ESP_GAP_BLE_ADV_TERMINATED_EVT:

            break;
        case ESP_GAP_BLE_EXT_ADV_SET_PARAMS_COMPLETE_EVT:
            ESP_LOGI("BLE", "Params set. Setting name...");
            // Call the helper function below to avoid writing hex by hand
            if (param->ext_adv_set_params.status == ESP_BT_STATUS_SUCCESS) {
                set_ext_name(0, "EMBEDDEM_ARM"); 
            }
            break;
        case ESP_GAP_BLE_EXT_ADV_DATA_SET_COMPLETE_EVT:
            ESP_LOGI("BLE", "Set advertising Data");
            //esp_ble_gap_ext_adv_set_params(0, &ext_adv_params_2M);
            esp_ble_gap_ext_adv_start(1, &adv_inst);
            break;
        case ESP_GAP_BLE_EXT_ADV_START_COMPLETE_EVT:
            ESP_LOGI("BLE", "Start advertising");
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
                
            esp_gatt_char_prop_t arm_angle_property = ESP_GATT_CHAR_PROP_BIT_READ |
                                                        ESP_GATT_CHAR_PROP_BIT_WRITE |
                                                        ESP_GATT_CHAR_PROP_BIT_NOTIFY;
            uint16_t arm_angle_value[3] = {0, 0, 0};
            esp_attr_value_t arm_angle_attr_value = {
                .attr_max_len = sizeof(arm_angle_value), // Max length for 3 uint16_t values (6 bytes)
                .attr_len = sizeof(arm_angle_value),     // Initial length
                .attr_value = (uint8_t *)arm_angle_value
            };
            ret = esp_ble_gatts_add_char(service_handle,
                                     &(esp_bt_uuid_t){.len = ESP_UUID_LEN_16, .uuid.uuid16 = ARM_ANGLE_UUID},
                                     ESP_GATT_PERM_READ | ESP_GATT_PERM_WRITE,
                                     arm_angle_property,
                                     &arm_angle_attr_value,
                                     NULL);
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
            // Get current time in microseconds
            int64_t current_time = esp_timer_get_time();
            
            // Calculate and print time since last write
            if (last_write_time != 0) {
                int64_t time_delta_us = current_time - last_write_time;
                int64_t time_delta_ms = time_delta_us / 1000;
                ESP_LOGI("BLE", "Time since last write: %" PRId64 " ms (%" PRId64 " us)", time_delta_ms, time_delta_us);
            }
            last_write_time = current_time;
            
            // Log raw data for any characteristic write
            ESP_LOGI("BLE", "Write Event received. Handle: %d, Length: %d", param->write.handle, param->write.len);
            ESP_LOG_BUFFER_HEX("BLE Write Data (Raw)", param->write.value, param->write.len);

            // Check for quaternion handle and ensure 4 bytes for X, Y (2 x uint16_t)
            if (param->write.handle == quaternion_handle) {
                int16_t x = (uint16_t)(param->write.value[0] | (param->write.value[1] << 8));
                int16_t y = (uint16_t)(param->write.value[2] | (param->write.value[3] << 8));
                ESP_LOGI("BLE", "Received X/Y angles: X = %d , Y = %d",
                                 x, y);
                update_xy_servo_positions(x, y);
                
                uint16_t x_arm_angle = (uint16_t)(param->write.value[4] | (param->write.value[5] << 8));
                uint16_t y_arm_angle = (uint16_t)(param->write.value[6] | (param->write.value[7] << 8));
                uint16_t z_arm_angle = (uint16_t)(param->write.value[8] | (param->write.value[9] << 8));
                set_servo_angle(LEDC_CHANNEL_SERVO_1, x_arm_angle);
                set_servo_angle(LEDC_CHANNEL_SERVO_2, y_arm_angle);
                set_servo_angle(LEDC_CHANNEL_SERVO_3, z_arm_angle);

                uint16_t claw_angle = (uint16_t)((param->write.value[10]));
                ESP_LOGI("BLE", "Received Claw angle: %d ", claw_angle);
                update_claw_position(claw_angle);

            } else if (param->write.handle == esc_command_handle && param->write.len >= 4) { // Handle ESC characteristic
                uint16_t left_speed = (uint16_t)(param->write.value[0] | (param->write.value[1] << 8));
                uint16_t right_speed = (uint16_t)(param->write.value[2] | (param->write.value[3] << 8));
                ESP_LOGI("BLE", "Received ESC speeds: Left = %u, Right = %u", left_speed, right_speed);
                update_esc_speeds(left_speed, right_speed);
            }
            // IMPORTANT: Send a write response for PROPERTY_WRITE characteristics
            esp_ble_gatts_send_response(gatt_if_param, param->write.conn_id, param->write.trans_id,
                                         ESP_GATT_OK, NULL);
            break;
        }
        case ESP_GATTS_START_EVT:
            ESP_LOGI("BLE", "GATT service started successfully!");
            break;

        case ESP_GATTS_CONNECT_EVT:
            conn_id = param->connect.conn_id;
            ESP_LOGI("BLE", "Client connected");
            esp_ble_gap_set_preferred_phy(param->connect.remote_bda, 0, ESP_BLE_GAP_PHY_2M_PREF_MASK, ESP_BLE_GAP_PHY_2M_PREF_MASK, ESP_BLE_GAP_PHY_OPTIONS_PREF_S2_CODING);
            uint16_t arm_angles[3] = {servo_angle_1, servo_angle_2, servo_angle_3};
    
            // Send an indication or notification with the current angles
            esp_err_t ret = esp_ble_gatts_send_indicate(gatt_if_param, conn_id, arm_angle_handle,
                                                 sizeof(arm_angles), (uint8_t *)arm_angles, false);
            break;
        case ESP_GATTS_DISCONNECT_EVT:
            ESP_LOGI("BLE", "Client disconnected, restarting advertising...");
            
            esp_ble_gap_ext_adv_start(1, &adv_inst);
            //esp_ble_gap_start_advertising(&adv_params);
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
    
    // Release Classic BT memory if not needed to save RAM
    ret = esp_bt_controller_mem_release(ESP_BT_MODE_CLASSIC_BT);
    ESP_LOGE("BLE", "Bluetooth memory release status: %x", ret);


    ret = esp_bt_controller_init(&bt_cfg);
    if (ret != ESP_OK) {
        ESP_LOGE("BLE", "Bluetooth controller init failed: %x", ret);
        return;
    }
    
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

    esp_ble_gap_set_device_name(DEVICE_NAME); // this only local
    
    uint8_t bt_mac[6];
    //esp_bt_dev_get_address(bt_mac);
    //ESP_LOGI("BLE", "BLE MAC: %02X:%02X:%02X:%02X:%02X:%02X",bt_mac[0], bt_mac[1], bt_mac[2], bt_mac[3], bt_mac[4], bt_mac[5]);

    // Set advertising data
    /*esp_ble_adv_data_t adv_data = {
        .set_scan_rsp = false,
        .include_name = true,
        .flag = ESP_BLE_ADV_FLAG_GEN_DISC | ESP_BLE_ADV_FLAG_BREDR_NOT_SPT,
        // Add service UUID to advertisement data if desired for discovery
        // .p_service_uuid = (uint8_t *)&SERVICE_UUID,
        // .service_uuid_len = ESP_UUID_LEN_16,
    }; */

    //ret = esp_ble_gap_config_adv_data(&adv_data);
    
    ret = esp_ble_gap_ext_adv_set_params(0, &ext_adv_params_2M);
    /*
    ESP_LOGE("BLE", "Config adv data status: %x", ret);
    static uint8_t ext_adv_raw_data[] = {
        // Flags (General Discoverable Mode & BR/EDR Not Supported)
        0x02, 0x01, 0x06, 
        
        // Length: 12 (1 type byte + 11 chars), Type: 0x09 (Complete Local Name)
        0x0C, 0x09, 'E', 'S', 'P', '_', 'E', 'X', 'T', '_', 'A', 'D', 'V'
    };
    esp_ble_gap_config_ext_adv_data_raw(0, sizeof(ext_adv_raw_data), ext_adv_raw_data); */
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
    init_servo_arm_position();
    ble_app_init(); // Initialize BLE
    
}