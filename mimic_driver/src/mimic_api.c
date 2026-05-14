
#include "mimic_api.h"
#include "ipc_flock.h"
#include <stdlib.h>
#include <math.h>
#include <string.h>

void sys_reset(MmSession *mmsession_handle, int card_id)
{
    bool read_flag = false;  // read -> true; write -> false
    uint32_t read_data = 0;
    uint64_t done_clear_addr = C6_FPGA_SYS_RESET_ADDR;
    reg_readwrite(mmsession_handle, card_id, done_clear_addr, 0x1, &read_data, read_flag); // write 0x1 to done_clear_addr
    usleep(2000000); // sleep 500 ms
    reg_readwrite(mmsession_handle, card_id, done_clear_addr, 0x0, &read_data, read_flag); // write 0x0 to done_clear_addr
}

void dma_reset(MmSession *mmsession_handle, int card_id)
{
    bool read_flag = false;  // read -> true; write -> false
    uint32_t read_data = 0;
    uint64_t done_clear_addr = C6_FPGA_SYS_RESET_ADDR;
    reg_readwrite(mmsession_handle, card_id, done_clear_addr, 0x2, &read_data, read_flag);
    usleep(500000); // 500 ms
    reg_readwrite(mmsession_handle, card_id, done_clear_addr, 0x0, &read_data, read_flag);
}

mmerrno is_gtx_interconnect_safe(MmSession *mmsession_handle, int card_id)
{
    bool read_flag = true;
    uint64_t addr = C6_FPGA_PROGRAM_CONFIG_ADDR + 0x4*4;
    uint32_t read_data = 0;
    reg_readwrite(mmsession_handle, card_id, addr, 0, &read_data, read_flag);
    if ((read_data & 0xff) == (C6_FPGA_CHIP2CHIP_LINK_OK & 0xff))
        return MM_API_SUCCESS;
    else 
        return MM_API_ERR_CHIP2CHIP_STATUS_ERR;

}

void program_fpga_clear(MmSession *mmsession_handle, int card_id, int chipsel)
{
    /*
        # chipsel:   1      ->     A
        # chipsel:   2      ->     B
        # chipsel:   4      ->     C
        # chipsel:   3      ->     BA
        # chipsel:   5      ->     CA
        # chipsel:   6      ->     CB
        # chipsel:   7      ->     CBA
    */
    bool read_flag = false;
    uint32_t read_data = 0;
    uint64_t config_ip_offset = C6_FPGA_PROGRAM_CONFIG_ADDR;
    uint64_t program_b_addr = config_ip_offset;
    uint64_t fpga_select_addr = config_ip_offset + 8;
    

    reg_readwrite(mmsession_handle, card_id, fpga_select_addr, chipsel & 7, &read_data, read_flag);
    reg_readwrite(mmsession_handle, card_id, program_b_addr, 5, &read_data, read_flag);
    usleep(100000); // 100 ms
    reg_readwrite(mmsession_handle, card_id, program_b_addr, 0, &read_data, read_flag);
}

mmerrno program_fpga(MmSession *mmsession_handle, int card_id, int channel_id, int chipsel,  const char *bitstream_path)
{
    /*
        # chipsel:   1      ->     A
        # chipsel:   2      ->     B
        # chipsel:   4      ->     C
        # chipsel:   3      ->     BA
        # chipsel:   5      ->     CA
        # chipsel:   6      ->     CB
        # chipsel:   7      ->     CBA
    */
    FILE* file = fopen(bitstream_path, "rb");
    mmerrno result;
    uint64_t    data_buff_addr = C6_PL_DDR_BASE_ADDR;
    uint64_t    config_ip_offset = C6_FPGA_PROGRAM_CONFIG_ADDR;
    uint64_t    program_b_addr = config_ip_offset; // 0x0
    uint64_t    done_clear_addr = program_b_addr + 4; // 0x4
    uint64_t    init_b_addr = done_clear_addr + 4; // 0x8
    uint64_t    fpga_select_addr = done_clear_addr + 4; // 0x8
    uint64_t    done_addr = init_b_addr + 4; // 0xc
    uint64_t    file_len_addr = done_addr + 4;

    bool        read_flag = false;
    uint32_t    read_data = 0;
    const int   DETECT_TIME = 10;
    uint64_t    filesize = -1;
    if (file) 
    {
        
        uint64_t cur_offset = ftell(file);
        if (cur_offset == -1)
        {
            printf("ftell failed : %s\n", strerror(errno));
            return MM_API_ERR_FILE_NOT_EXISTS;
        }
        if (fseek(file, 0, SEEK_END) != 0)
        {
            printf("fseek failed: %s\n", strerror(errno));
            return MM_API_ERR_FILE_NOT_EXISTS;
        }
        filesize = ftell(file);
        if (filesize == -1)
        {
            printf("ftell failed: %s\n", strerror(errno));
        }
        if (fseek(file, cur_offset, SEEK_SET) != 0)
        {
            printf("fseek failed: %s\n", strerror(errno));
            return MM_API_ERR_FILE_NOT_EXISTS;
        }

        // read file to buffer
        uint64_t filesize_extend = ceil(filesize/32.0)*32;
        char *buffer = (char *) malloc(sizeof(char) * filesize_extend);
        memset(buffer, 0, filesize_extend);
        fread(buffer, sizeof(char), filesize, file);
        fclose(file);


        result = dma_to_device(mmsession_handle, card_id, channel_id, buffer, filesize_extend, data_buff_addr);
        if (result != MM_API_SUCCESS)
        {
            printf("failed to dma bitstream to DDR4 on 7EV, errcode = %d\n", result);
            return MM_API_ERR_WRITE_DMA_FAILED_ERR;
        }

        reg_readwrite(mmsession_handle, card_id, done_clear_addr, 0x1, NULL, read_flag);
        reg_readwrite(mmsession_handle, card_id, done_clear_addr, 0x0, NULL, read_flag);
        reg_readwrite(mmsession_handle, card_id, file_len_addr, filesize/4, NULL, read_flag);
        reg_readwrite(mmsession_handle, card_id, fpga_select_addr, chipsel & 0x7, NULL, read_flag);
        reg_readwrite(mmsession_handle, card_id, program_b_addr, 2, NULL, read_flag);
        reg_readwrite(mmsession_handle, card_id, program_b_addr, 1, NULL, read_flag);
        usleep(100000); // 100 ms 
        reg_readwrite(mmsession_handle, card_id, program_b_addr, 0, NULL, read_flag);

        // wait for init_b
        read_flag = true;
        while(true)
        {
            reg_readwrite(mmsession_handle, card_id, init_b_addr, 0, &read_data, read_flag);
            if (read_data != 0)
                break;
            else    
                usleep(100000); // 100 ms
        }

        read_data = 0;
        result = MM_API_ERR_PROGRAM_FAILED;
        int i;
        for (i = 0; i < DETECT_TIME; i++ )
        {
            reg_readwrite(mmsession_handle, card_id, done_addr, 0, &read_data, read_flag);
            if ((read_data & 0x7 & chipsel) == chipsel)
            {
                result = MM_API_SUCCESS;
                break;
            } else 
                usleep(500000); // 500 ms
        }
        read_flag = false;
        reg_readwrite(mmsession_handle, card_id, done_clear_addr, 0x1, NULL, read_flag);
        reg_readwrite(mmsession_handle, card_id, done_clear_addr, 0x0, NULL, read_flag);

        free(buffer);

    } else 
        result = MM_API_ERR_FILE_NOT_EXISTS;
    return result;
}

#ifdef SDK_VERSION_V4

float temperature_detect_7EV(MmSession *mmsession_handle, int card_id)
{
	uint64_t	target_addr = C6_FPGA_PROGRAM_CONFIG_ADDR;
	uint32_t	temp;
	reg_readwrite(mmsession_handle, card_id, target_addr + 0x4, 0, &temp, true);
	float  real_temp = ((float)temp * 509.314)/1024.0 - 280.231;
	return real_temp;
}

uint32_t report_version(MmSession *mmsession_handle, int card_id)
{
	uint64_t	target_addr = C6_FPGA_PROGRAM_CONFIG_ADDR;
	uint32_t	version;
	reg_readwrite(mmsession_handle, card_id, target_addr + 0x0, 0, &version, true);
	return version;
}

float temperature_detect(MmSession *mmsession_handle, int card_id)
{
	uint64_t	config_ip_offset = C6_FPGA_PROGRAM_CONFIG_ADDR;
    char str[100] = {0};
	sprintf(str, C6_FILE_LOCK_STRING, card_id);
	ipc_flock_t* file_lock = ipc_flock_open(str);
	ipc_flock_lock(file_lock);

	//
	uint32_t read_data;
	reg_readwrite(mmsession_handle, card_id, config_ip_offset + 0x14, 0, &read_data, true);
	read_data = read_data >> 16;
	
	ipc_flock_unlock(file_lock);
	ipc_flock_close(file_lock);

	return read_data/128.0;
	
}

float power_monitor(MmSession *mmsession_handle, int card_id)
{
	uint64_t	config_ip_offset = C6_FPGA_PROGRAM_CONFIG_ADDR;
    char str[100] = {0};
	sprintf(str, C6_FILE_LOCK_STRING, card_id);
	ipc_flock_t* file_lock = ipc_flock_open(str);
	ipc_flock_lock(file_lock);

	//
	uint32_t read_data;
	reg_readwrite(mmsession_handle, card_id, config_ip_offset + 0x14, 0, &read_data, true);
	read_data = read_data & 0xffff;
	
	ipc_flock_unlock(file_lock);
	ipc_flock_close(file_lock);

	return (read_data*1000.0 + 2908.0)/5501.0;
}

#else

float temperature_detect(MmSession *mmsession_handle, int card_id)
{
    uint64_t        config_ip_offset = C6_FPGA_TEMP_SENSOR_ADDR;
    uint64_t        iic_addr_offset = config_ip_offset;

    char str[100] = {0};
	sprintf(str, "/tmp/fpga_cracker/mimic_dirver_tem%d.lock", card_id);
	ipc_flock_t* file_lock = ipc_flock_open(str);
	ipc_flock_lock(file_lock);
    // initialization
    // soft reset
    reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x40 , 0xa, NULL, false);
    // set rx_fifo depth to maximum
    reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x120, 0xf, NULL, false);
    // reset tx_fifo
    reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x100, 0x3, NULL, false);
    // enable axi_iic, remove tx_fifo reset, disable gc
    reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x100, 0x1, NULL, false);
    // read input temperatures
    reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x108, 0x194, NULL, false);
    reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x108, 0x00, NULL, false);
    reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x108, 0x195, NULL, false);
    reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x108, 0x202, NULL, false);

    int i = 0;
    uint32_t in_v[2] = {0};
    uint32_t read_data = 0;
    uint32_t timeout = 0;
    while( i < 2)
    {
        reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x104, 0, &read_data, true);
        if ( (read_data & 0x40) == 0 )
        {
            reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x10c, 0, &in_v[i], true);
            i++;
        } else {
            sleep(1);
	    timeout++;
	}
    }

    float temperature = (in_v[0] * 256 + in_v[1])/128.0;
    ipc_flock_unlock(file_lock);
    ipc_flock_close(file_lock);
	
    if (timeout == 3)
		return 75.0;
    else 
    	return temperature;
}

float power_monitor(MmSession *mmsession_handle, int card_id)
{

    uint64_t     config_ip_offset = C6_FPGA_SYS_SENSOR_ADDR;
    uint64_t     iic_addr_offset  = config_ip_offset;
    
    char str[100] = {0};
	sprintf(str, "/tmp/fpga_cracker/mimic_dirver_power%d.lock", card_id);
	ipc_flock_t* file_lock = ipc_flock_open(str);
	ipc_flock_lock(file_lock);
    //initialization
    //soft reset 
    reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x40, 0xa, NULL, false);
    //set rx_fifo depth to maximum 
    reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x120, 0xf, NULL, false);
    //reset tx_fifo
    reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x100, 0x3, NULL, false);
    //enable axi_ic, remove tx_fifo_reset, disable gc
    reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x100, 0x1, NULL, false);

    
    // read power
    reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x108, 0x1A4, NULL, false);
    reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x108, 0xD2, NULL, false);
    reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x108, 0x1A5, NULL, false);
    reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x108, 0x202, NULL, false);

    int i = 0;
    uint32_t in_p[2] = {0};
    uint32_t read_data = 0;
    uint32_t timeout = 0;
    while (i < 2) 
    {
        reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x104, 0, &read_data, true);
        if ( (read_data & 0x40) == 0)
        {
            reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x10c, 0, &in_p[i], true);
            i++;
        } else {
            sleep(1);
	    timeout++;
	}

	if (timeout == 3)
		break;
    }
    ipc_flock_unlock(file_lock);
    ipc_flock_close(file_lock);
    if (timeout == 3)
		return 85.4;
    else 
    	return ((in_p[0] + in_p[1]*256)*1000+2908)/5501.0;
}

void system_monitor(MmSession *mmsession_handle, int card_id, Environment *env_handle)
{
    uint64_t     config_ip_offset = C6_FPGA_SYS_SENSOR_ADDR;
    uint64_t     iic_addr_offset  = config_ip_offset;

    //initialization
    //soft reset 
    reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x40, 0xa, NULL, false);
    //set rx_fifo depth to maximum 
    reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x120, 0xf, NULL, false);
    //reset tx_fifo
    reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x100, 0x3, NULL, false);
    //enable axi_ic, remove tx_fifo_reset, disable gc
    reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x100, 0x1, NULL, false);


    // read input voltage
    reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x108, 0x1A4, NULL, false);
    reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x108, 0x88, NULL, false);
    reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x108, 0x1A5, NULL, false);
    reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x108, 0x202, NULL, false);

    int i = 0;
    uint32_t in_v[2] = {0};
    uint32_t read_data = 0;
    while ( i < 2 )
    {
        reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x104, 0, &read_data, true);
        if ( (read_data & 0x40) == 0)
        {
            reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x10c, 0, &in_v[i], true);
            i++;
        } else 
            sleep(1);
    }

    env_handle->voltage = in_v[0] + in_v[1]*256;
    env_handle->voltage = ((env_handle->voltage)*100 - 1343)/16296.0;

    // read input current
    reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x108, 0x1A4, NULL, false);
    reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x108, 0xD1, NULL, false);
    reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x108, 0x1A5, NULL, false);
    reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x108, 0x202, NULL, false);

    i = 0;
    read_data = 0;
    while ( i < 2 )
    {
        reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x104, 0, &read_data, true);
        if ( (read_data & 0x40) == 0 )
        {
            reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x10c, 0, &in_v[i], true);
            i++;
        } else 
            sleep(1);
    }
    env_handle->current = in_v[0] + in_v[1]*256;
    env_handle->current = ((env_handle->current) * 100 + 1833)/13797.0;

    env_handle->power = (env_handle->current) * (env_handle->voltage);

    // read temperature
    reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x108, 0x1A4, NULL, false);
    reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x108, 0x8d, NULL, false);
    reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x108, 0x1A5, NULL, false);
    reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x108, 0x202, NULL, false);

    i = 0;
    read_data = 0;
    while ( i < 2)
    {
        reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x104, 0, &read_data, true);
        if ( (read_data & 0x40) == 0)
        {
            reg_readwrite(mmsession_handle, card_id, iic_addr_offset + 0x10c, 0, &in_v[i], true);
            i++;
        } else 
            sleep(1);
    }
    env_handle->temperature = in_v[0] + in_v[1]*256;
    env_handle->temperature = ((env_handle->temperature) * 100 + 14500)/1580.0;
}
#endif

mmerrno mm_dma_read (MmSession *mmsession_handle, int card_id, int channel_id, uint8_t *buf, uint64_t offset_addr, uint32_t data_size)
{
    uint64_t    buf_addr = C6_FPGA_AURORA_CH0_OFFSET_ADDR + offset_addr;
    mmerrno     ret;
    ret = dma_from_device(mmsession_handle, card_id, channel_id, (char *)buf, data_size, buf_addr);
 
    return ret;
}

mmerrno mm_dma_write(MmSession *mmsession_handle, int card_id, int channel_id, uint8_t *buf, uint64_t offset_addr, uint32_t data_size)
{
 

    uint64_t    buf_addr = C6_FPGA_AURORA_CH0_OFFSET_ADDR + offset_addr;
    mmerrno     ret; 

    ret = dma_to_device(mmsession_handle, card_id, channel_id, (char*)buf, data_size, buf_addr);
    return ret;
}


mmerrno mm_fpga_read(MmSession *mmsession_handle, int card_id, int fpga_id, int channel_id, uint64_t offset_addr, uint32_t* read_data)
{
    return mm_dma_read(mmsession_handle, card_id, channel_id, read_data, offset_addr, 4);
}

mmerrno mm_fpga_write(MmSession *mmsession_handle, int card_id, int fpga_id, int channel_id, uint64_t offset_addr, uint32_t write_data)
{
    return mm_dma_write(mmsession_handle, card_id, channel_id, &write_data, offset_addr, 4);
}
