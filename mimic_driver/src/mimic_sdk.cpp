#include "mimic_sdk.h"
#include <vector>
#include <stdint.h>		// uint32...
#include <string>
#include <dirent.h>
#include <fstream>		// ifstream
#include <iomanip> 		// setbase()
#include <vector>
#include <cstring>

using namespace std;


MM_INTERFACE    MmSession_Open(MmSession *MmSession_handle)
{
	DIR *dir;
	string err = string();
	vector<pair<string, string>> bdf_found;
	pair<string, string> bdf_found_pair_tmp;
	string bdf_found_tmp;
    unsigned int  card_num = 0;

    if (MmSession_handle->Allocated)
        return MM_API_ERR_DUP_SESSION_OPEN;
        

    ofstream ofile;
    ofile.open(MMSDK_LOGFILE, ios::app);
    if (!ofile)
        return MM_API_ERR_LOG_INIT_ERR;

    if ((dir = opendir("/sys/bus/pci/devices")) == nullptr) {
		return MM_API_ERR_FILE_NOT_EXISTS;
	}

	// iterate over all PCIe devices
	struct dirent *d;
	ifstream ifstr;
	

	while ((d = readdir(dir)) != nullptr) {

		// only consider actual device folders, not ./ and ../
		if (strstr(d->d_name, "0000:") != nullptr) {
			bdf_found_tmp = string(d->d_name);

			// Continue only if the function number matches
			if ((unsigned int)(bdf_found_tmp.back() - '0') != MM_FUNCTION)
				continue;

			string path("/sys/bus/pci/devices/" + bdf_found_tmp);

			// read vendor id
			ifstr.open(path + "/vendor");
			string tmp((istreambuf_iterator<char>(ifstr)),
						istreambuf_iterator<char>());
			ifstr.close();

			// check if vendor id is correct
			if (std::stoul(tmp, nullptr, 16) == MM_VENDOR) {

				// read device id
				ifstr.open(path + "/device");
				std::string tmp((std::istreambuf_iterator<char>(ifstr)),
								std::istreambuf_iterator<char>());
				ifstr.close();

				// check if device also fits
				if (std::stoul(tmp, nullptr, 16) == MM_DEVICE)
				{
					// read subsystem_device id 
					ifstr.open(path + "/subsystem_device");
					string tmp((std::istreambuf_iterator<char>(ifstr)),
								std::istreambuf_iterator<char>());
					ifstr.close();

					if (std::stoul(tmp, nullptr, 16) == SUB_SYSTEM_DEVICE_C3 || 
                        std::stoul(tmp, nullptr, 16) == SUB_SYSTEM_DEVICE_C6 )  // MIMIC Card
					{
						DIR *driver_dir;
						string new_path = path + "/xdma";
						struct dirent *dd; 

                        bdf_found_tmp.copy(MmSession_handle->DBDF[card_num], bdf_found_tmp.length(), 0);
                        MmSession_handle->DeviceIdx[card_num] = card_num;
                        MmSession_handle->CardIdentifiers_version[card_num] = std::stoul(tmp, nullptr, 16)  & 0xFF;
                        MmSession_handle->FpgaNum[card_num] = (std::stoul(tmp, nullptr, 16)  == SUB_SYSTEM_DEVICE_C6) ? 1 : 1;
                        MmSession_handle->PcieChannelNum[card_num] = 4;
                        MmSession_handle->CoreNum[card_num] = 3;

                        ofile << "[INFO]: detect Mimic Card [" << card_num << "] with bdbf : " << bdf_found_tmp << "\n" << endl;
                        ofile << "[INFO]: the identifier of card " << bdf_found_tmp << "is " << std::hex << std::stoul(tmp, nullptr, 16) << "\n" << endl;

						driver_dir = opendir(new_path.c_str());
						if (driver_dir != nullptr)
						{
							while((dd = readdir(driver_dir)) != nullptr)
							{
								if (strstr(dd->d_name, "xdma") != nullptr)
								{
									string driver_handle_name(dd->d_name);
									driver_handle_name = driver_handle_name.substr(0, 5);
                                    driver_handle_name.copy(MmSession_handle->Driver_identifer[card_num], DRIVER_ID_LEN, 0);
									break;
								}
							}
							closedir(driver_dir);
						}
                        if (0 == strcmp(MmSession_handle->Driver_identifer[card_num], ""))
                        {
                            ofile << "[ERR]: card [" << card_num << "]" << " can't detect driver!\n" << endl;
                            return MM_API_ERR_DRV_INIT_ERR;
                        }
                        card_num ++;
					}
				}
					
			}
		}
	}

	closedir(dir);
    ofile.close();
    
    if (card_num > MAX_NUM_OF_FPGA_PER_HOST)
        return MM_API_ERR_CARD_NUM_OVERFLOW;
    else if (card_num == 0)
        return MM_API_ERR_CARD_NUM_EQ0;
    else 
    {
        MmSession_handle->CardNum = card_num;
        MmSession_handle->Allocated = TRUE;
        return MM_API_SUCCESS;   
    }
}

MM_INTERFACE    MmSession_ScanFpgaDeviceNum(MmSession *MmSession_handle, unsigned *Count)
{
    if (!MmSession_handle->Allocated)
        return MM_API_ERR_NULL_INPUT;

    if (Count == nullptr)
        return MM_API_ERR_NULL_INPUT;
    
    *Count = MmSession_handle->CardNum;
    return MM_API_SUCCESS;

}

MM_INTERFACE MmSession_Close(MmSession* MmSession_handle)
{
    memset(MmSession_handle, 0x0, sizeof(MmSession));
	return MM_API_SUCCESS;
}
