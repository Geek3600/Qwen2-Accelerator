#include "mimic_sdk.h"
#include "mimic_api.h"
#include "ipc_flock.h"
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>

int main(int argc, char* argv[])
{
    MmSession  mmsession_handle;
    mmerrno ret_result;
    unsigned    card_count;
    int i;

    char str[100] = {0};

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
    if (argc == 1) {    
	
	ipc_flock_t* file_lock_tmp[card_count];
	ipc_flock_t* file_lock_pow[card_count];

	for (i = 0; i < card_count; i++) {
		sprintf(str, "/tmp/fpga_cracker/mimic_dirver_tem%d.lock", i);
		file_lock_tmp[i] = ipc_flock_open(str);
		ipc_flock_lock(file_lock_tmp[i]);
	}
	for (i = 0; i < card_count; i++) {
		sprintf(str, "/tmp/fpga_cracker/mimic_dirver_power%d.lock", i);
		file_lock_pow[i] = ipc_flock_open(str);
		ipc_flock_lock(file_lock_pow[i]);
	}
	//
	for (i = 0; i < card_count; i++) {
		sys_reset(&mmsession_handle, i);
		dma_reset(&mmsession_handle, i);
	}

	for (i = 0; i < card_count; i++) {

		system("rmmod xdma");
		system("insmod /lib/modules/`uname -r`/extra/xdma.ko");
		for (i = 0; i < card_count; i++) {
		    if (is_gtx_interconnect_safe(&mmsession_handle, i) == MM_API_SUCCESS)
			printf("card %d chip2chip link up!\n", i);
		    else 
			printf("card %d chip2chip link down!\n", i);
		}

    }

	for (i = 0; i < card_count; i++) {
		ipc_flock_unlock(file_lock_pow[i]);
		ipc_flock_close(file_lock_pow[i]);
	}
	for (i = 0; i < card_count; i++) {
		ipc_flock_unlock(file_lock_tmp[i]);
		ipc_flock_close(file_lock_tmp[i]);
	}



    } else {
       	int card_id = strtol(argv[1], NULL, 10);
		if (card_id > (card_count - 1))
		{
		    printf("Input card ID exceed the available card number, exit!\n");
		    return 0;
		}



		//

		sprintf(str, "/tmp/fpga_cracker/mimic_dirver_tem%d.lock", card_id);
		ipc_flock_t* file_lock_tmp = ipc_flock_open(str);
		ipc_flock_lock(file_lock_tmp);
		
		sprintf(str, "/tmp/fpga_cracker/mimic_dirver_power%d.lock", card_id);
		ipc_flock_t* file_lock_pow = ipc_flock_open(str);
		ipc_flock_lock(file_lock_pow);
		//

		sys_reset(&mmsession_handle, card_id);
		dma_reset(&mmsession_handle, card_id);
		system("rmmod xdma");
		system("insmod /lib/modules/`uname -r`/extra/xdma.ko");
		if (is_gtx_interconnect_safe(&mmsession_handle, card_id) == MM_API_SUCCESS)
		    printf("card %d chip2chip link up!\n", card_id);
		else 
		    printf("card %d chip2chip link down!\n", card_id);

		//
		ipc_flock_unlock(file_lock_pow);
		ipc_flock_close(file_lock_pow);
		
		ipc_flock_unlock(file_lock_tmp);
		ipc_flock_close(file_lock_tmp);

    }
    return 0;
}
