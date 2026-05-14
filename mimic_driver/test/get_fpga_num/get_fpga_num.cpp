#include "mimic_sdk.h"
#include <iostream>
#include <string.h>

int main(int argc, char*argv[])
{
    MmSession  mmsession_handle;
    mmerrno session_open_result;
    mmerrno scan_result;
    unsigned fpga_count = 0;
    memset(&mmsession_handle, 0, sizeof(mmsession_handle));

    session_open_result  = MmSession_Open(&mmsession_handle);
    if (session_open_result == MM_API_SUCCESS)
    {
        scan_result = MmSession_ScanFpgaDeviceNum(&mmsession_handle, &fpga_count);
        if (scan_result == MM_API_SUCCESS)
        {
            std::cout << "FPGA number is " << fpga_count << std::endl;
            for(int i = 0; i < fpga_count; i++)
            {
                std::cout << "dbdf of FPGA device " << i << ": "<< mmsession_handle.DBDF[i] << "; driver handle " << mmsession_handle.Driver_identifer[i] << std::endl;
            }
        }
        else
            std::cout << "FPGA scan error due to input invalid" << std::endl;
    }
    else 
    {
        std::cout << "session open failed!" << std::endl;
        std::cout << "error code is " << session_open_result << std::endl;
    }
    return 0;
}