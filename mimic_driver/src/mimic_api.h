#ifndef MIMIC_API_H_
#define MIMIC_API_H_

#include "mimic_dma.h"
#include "mimic_sdk.h"
#include <stdbool.h>
#include <unistd.h>
#include <errno.h>

#ifdef	__cplusplus
extern "C" {
#endif


// C5 -> Card 5.0 parameters
/*
#define     C5_PL_DDR_BASE_ADDR                     0x100000000
#define     C5_FPGA_PROGRAM_CONFIG_ADDR             0x0
#define     C5_FPGA_TEMP_SENSOR_ADDR                0x20000
#define     C5_FPGA_SYS_SENSOR_ADDR                 0x10000
#define     C5_FPGA_SYS_RESET_ADDR                  (C5_FPGA_PROGRAM_CONFIG_ADDR + 0xc)
#define     C5_FPGA_LOGIC_RESET_ADDR                (C5_FPGA_PROGRAM_CONFIG_ADDR + 0xc)
#define     C5_FPGA_LVDS_CONFIG_ADDR                0x30000
#define     C5_FPGA_LVDS_BASE_ADDR                  0x200000000
#define     C5_FPGA_LVDS_A_OFFSET_ADDR              0x0
#define     C5_FPGA_LVDS_C_OFFSET_ADDR              0x10000000
#define     C5_FPGA_9P_BRAM_OFFSET_ADDR             0x08000000
*/

// C3 -> Card 3.0 parameters
// ... coming soon


// C6 -> Card 6.0 parameters
#define     C6_PL_DDR_BASE_ADDR                     0x100000000
#define     C6_FPGA_PROGRAM_CONFIG_ADDR             0x0
#define     C6_FPGA_TEMP_SENSOR_ADDR                0x6000
#define     C6_FPGA_SYS_SENSOR_ADDR                 0x4000
#define     C6_FPGA_SYS_RESET_ADDR                  (C6_FPGA_PROGRAM_CONFIG_ADDR + 0xc)
#define     C6_FPGA_LOGIC_RESET_ADDR                (C6_FPGA_PROGRAM_CONFIG_ADDR + 0xc)
#define     C6_FPGA_9P_BRAM_OFFSET_ADDR             0x2000
#define     C6_FPGA_AURORA_CH0_OFFSET_ADDR          0x2000000000
#define     C6_FPGA_AURORA_CH1_OFFSET_ADDR          0x4000000000

#define     C6_FPGA_CHIP2CHIP_LINK_OK               0x00000303


#define		C6_FILE_LOCK_STRING						"/tmp/fpga_cracker/mimic_driver_tmp%d.lock"


typedef struct 
{
    float voltage;
    float current;
    float power;
    float temperature;
} Environment;

 

// reset api

void sys_reset(MmSession *mmsession_handle, int card_id);

void dma_reset(MmSession *mmsession_handle, int card_id);

mmerrno is_gtx_interconnect_safe(MmSession *mmsession_handle, int card_id);

// program fpga api 

void program_fpga_clear(MmSession *mmsession_handle, int card_id, int chipsel);

mmerrno program_fpga(MmSession *mmsession_handle, int card_id, int channel_id, int chipsel, const char *bitstream_path);

// temperature api 
float temperature_detect(MmSession *mmsession_handle, int card_id);
float temperature_detect_7EV(MmSession *mmsession_handle, int card_id);
uint32_t report_version(MmSession *mmsession_handle, int card_id);
float power_monitor(MmSession *mmsession_handle, int card_id);
void system_monitor(MmSession *mmsession_handle, int card_id, Environment *env_handle);

// 9P operation

mmerrno mm_dma_read(MmSession *mmsession_handle, int card_id, int channel_id, uint8_t *buf, uint64_t offset_addr, uint32_t data_size);

mmerrno mm_dma_write(MmSession *mmsession_handle, int card_id, int channel_id, uint8_t *buf, uint64_t offset_addr, uint32_t data_size);

mmerrno mm_fpga_write(MmSession *mmsession_handle, int card_id, int fpga_id, int channel_id, uint64_t offset_addr, uint32_t write_data);        // fpga_id is not used

mmerrno mm_fpga_read(MmSession *mmsession_handle, int card_id, int fpga_id, int channel_id, uint64_t offset_addr, uint32_t *read_data);         // fpga_id is not used




#ifdef	__cplusplus
}
#endif

#endif // MIMIC_API_H_
