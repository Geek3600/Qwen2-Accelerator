#include "mimic_sdk.h"
#include "mimic_api.h"
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>


#define	MTD0_LEN	31588352
#define MTD1_LEN	524288
#define MTD2_LEN	33554432
#define	MTD3_LEN	2596

int main(int argc, char* argv[])
{
    MmSession  mmsession_handle;
    mmerrno ret_result;
    unsigned    card_count;

    
    memset(&mmsession_handle, 0, sizeof(MmSession));
    ret_result  = MmSession_Open(&mmsession_handle);
   
    if (ret_result != MM_API_SUCCESS)
    {
        printf("session open failed!\n");
        printf("error code is %d\n", ret_result);
        return -1;
    }

    ret_result = MmSession_ScanFpgaDeviceNum(&mmsession_handle, &card_count);

    if (ret_result != MM_API_SUCCESS)
    {
        printf("session scan fpga failed!\n");
        printf("error code is %d\n", ret_result);
        return -1;
    }
	
	if (argc > 1)
	{
		printf("flash bin file: %s\n", argv[1]);		
	} 
	else 
	{
		printf("flash bin file should provided, exit!\n");
		return 0;
	}

	//

	FILE* fp = fopen(argv[1], "rb");
	if (fp == NULL) {
		printf("file open failed\n");
		return -1;
	}
	uint64_t data_buff_addr = C6_PL_DDR_BASE_ADDR;
	uint64_t flash_burn_addr = 0x10000;

	uint32_t mtd0_len = MTD0_LEN;
	uint32_t mtd1_len = MTD1_LEN;
	uint32_t mtd2_len = MTD2_LEN;
	uint32_t mtd3_len = MTD3_LEN;

	uint32_t mtd_total_len = mtd0_len + mtd1_len + mtd2_len + mtd3_len;
	
	//
	printf("mtd0 len = %d\n", mtd0_len);
	printf("mtd1 len = %d\n", mtd1_len);
	printf("mtd2 len = %d\n", mtd2_len);
	printf("mtd3 len = %d\n", mtd3_len);
	printf("mtd total len = %d\n", mtd_total_len);

	//

	uint64_t mtd0_len_addr = 0x4;
	uint64_t mtd1_len_addr = mtd0_len_addr + 4;
	uint64_t mtd2_len_addr = mtd1_len_addr + 4;
	uint64_t mtd3_len_addr = mtd2_len_addr + 4;
	uint64_t mtd_total_addr = mtd3_len_addr + 4;
	uint64_t mtd_boot_ddraddr = mtd_total_addr + 4;
	uint64_t flash_burn_req = 0x0;
	uint64_t flash_burn_code = 0x0;
	uint32_t status = 0 ;
	int i;
	uint32_t mtd_total_len_orig = mtd_total_len;

	if (mtd_total_len%16 != 0)
		mtd_total_len += 16 - (mtd_total_len%16);

	if (mtd3_len % 16 != 0)
		mtd3_len += 16 - (mtd3_len%16);
	
	char *buffer = (char *)malloc(sizeof(char)*mtd_total_len);
	memset(buffer, 255, mtd_total_len);
	fread(buffer, sizeof(char), mtd_total_len_orig, fp);
	fclose(fp);

	if (argc == 2) {
		for (i = 0; i < card_count; i++) {
			dma_to_device(&mmsession_handle, i, 0, buffer, mtd_total_len, data_buff_addr);
			reg_readwrite(&mmsession_handle, i, flash_burn_addr + mtd0_len_addr, mtd0_len, NULL, false);	
			reg_readwrite(&mmsession_handle, i, flash_burn_addr + mtd1_len_addr, mtd1_len, NULL, false);	
			reg_readwrite(&mmsession_handle, i, flash_burn_addr + mtd2_len_addr, mtd2_len, NULL, false);	
			reg_readwrite(&mmsession_handle, i, flash_burn_addr + mtd3_len_addr, mtd3_len, NULL, false);	
			reg_readwrite(&mmsession_handle, i, flash_burn_addr + mtd_total_addr, mtd_total_len, NULL, false);	
			reg_readwrite(&mmsession_handle, i, flash_burn_addr + mtd_boot_ddraddr, 0x0, NULL, false);	
			reg_readwrite(&mmsession_handle, i, flash_burn_addr + flash_burn_req, 0x1 << 1, NULL, false);

			printf("It will take 2 mins, please wait.\n");
			// wait for program done!
			while(1) {
				reg_readwrite(&mmsession_handle, i, flash_burn_addr + flash_burn_code, 0, &status, true);
				if ((status >> 2) == 0x1) {
					printf("flash burn OK\n");
					reg_readwrite(&mmsession_handle, i, flash_burn_addr + flash_burn_code, 1, NULL, false); // clear burn code
					break;
				} else if ((status >> 2) != 0) {
					printf("flash burn ERROR\n");
					break;
				} else { 
					sleep(1);
				}
			}
		}
	} else {
		int fpga_index = strtol(argv[2], NULL, 10);
		dma_to_device(&mmsession_handle, fpga_index, 0, buffer, mtd_total_len, data_buff_addr);
		reg_readwrite(&mmsession_handle, fpga_index, flash_burn_addr + mtd0_len_addr, mtd0_len, NULL, false);	
		reg_readwrite(&mmsession_handle, fpga_index, flash_burn_addr + mtd1_len_addr, mtd1_len, NULL, false);	
		reg_readwrite(&mmsession_handle, fpga_index, flash_burn_addr + mtd2_len_addr, mtd2_len, NULL, false);	
		reg_readwrite(&mmsession_handle, fpga_index, flash_burn_addr + mtd3_len_addr, mtd3_len, NULL, false);	
		reg_readwrite(&mmsession_handle, fpga_index, flash_burn_addr + mtd_total_addr, mtd_total_len, NULL, false);	
		reg_readwrite(&mmsession_handle, fpga_index, flash_burn_addr + mtd_boot_ddraddr, 0x0, NULL, false);	
		reg_readwrite(&mmsession_handle, fpga_index, flash_burn_addr + flash_burn_req, 0x1 << 1, NULL, false);
		
		printf("It will take 2 mins, please wait.\n");
		// wait for program done!
		while(1) {
			reg_readwrite(&mmsession_handle, fpga_index, flash_burn_addr + flash_burn_code, 0, &status, true);
			if ((status >> 2) == 0x1) {
				printf("flash burn OK\n");
				printf("please cold reboot your computer to rescan the flash.\n");
				reg_readwrite(&mmsession_handle, fpga_index, flash_burn_addr + flash_burn_code, 1, NULL, false); // clear burn code
				break;
			} else if ((status >> 2) != 0) {
				printf("flash burn ERROR\n");
				reg_readwrite(&mmsession_handle, fpga_index, flash_burn_addr + flash_burn_code, 1, NULL, false); // clear burn code
				break;
			} else { 
				sleep(1);
			}
		}

	}

    return 0;
}
