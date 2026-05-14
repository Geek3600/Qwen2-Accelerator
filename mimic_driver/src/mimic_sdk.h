#ifndef MIMIC_SDK_H
#define MIMIC_SDK_H

#ifdef	__cplusplus
extern "C" {
#endif

/*! \def DBDF_LEN
\brief A macro which defines the length of DBDF string.*/
#define DBDF_LEN                                                        16

/*! \def DRIVER_ID_LEN 
\brief A macro which defines the length of driver handle leading string.
*/
#define DRIVER_ID_LEN                                                   5

/*! \def MAX_NUM_OF_FPGA_PER_HOST
\brief A macro which defines the maximum number of FPGA in a host.*/
#define MAX_NUM_OF_FPGA_PER_HOST                                        8

/*! \def MAX_PCIE_FUNC_CHANNEL_PER_CARD 
\brief A macro which defines the maximum channel number of PCIE. */
#define MAX_PCIE_FUNC_CHANNEL_PER_CARD                                  4

/*! \def MM_INTERFACE
\brief A macro that defines the alias for extern fmerror enum. */
#define MM_INTERFACE extern mmerrno

/*! \def MM_VENDOR 
\brief A macro that defines the vendor number of target MIMIC device enumeration.  */
#define MM_VENDOR   			                                        0x10ee

/*! \def MM_DEVICE
\brief A macro that defines the device number of target MIMIC device enumeration.  */
#define MM_DEVICE                                                       0x9038

/*! \def MM_DEVICE
\brief A macro that defines the function number of target MIMIC device enumeration. */

#define MM_FUNCTION 			                                        0x0
/*! \def MM_DEVICE
\brief A macro that defines the sub-system device number of target MIMIC device enumeration. 
       0xEEC3 -> MIMIC, 3.0 card
       0xEEC5 -> MIMIC, 5.0 card 
*/

#define SUB_SYSTEM_DEVICE_C3                                          0xEEC3

#define SUB_SYSTEM_DEVICE_C6                                          0xEEC6

#define SDK_VERSION_V4												  0xeec6_0400

/*! \def MMSDK_LOGFILE
\brief A macro that defines the log file path */
#define MMSDK_LOGFILE   "./mmsdk.log"

#define TRUE             1
#define FALSE            0
/**
 * @brief Data structure of an MIMIC API Session
 * 
 * Data can be feteched from API \ref Mimic_StartSession
*/

typedef struct 
{
    unsigned    Allocated;                                                          /*!< The session is allocated or not */
    unsigned    CardNum;                                                            /*!< Card number in the host */
    unsigned    CoreNum[MAX_NUM_OF_FPGA_PER_HOST];                                  /*!< Core number on each fpga device */
    unsigned    CardIdentifiers_version[MAX_NUM_OF_FPGA_PER_HOST];                  /*!< Card identifier version of current  */
    unsigned    FpgaNum[MAX_NUM_OF_FPGA_PER_HOST];                                  /*!< FPGA number in each card, index is card device index*/
    unsigned    PcieChannelNum[MAX_NUM_OF_FPGA_PER_HOST];                           /*!< Pcie function channel number in each card, index is card device index*/
    unsigned    DeviceIdx[MAX_NUM_OF_FPGA_PER_HOST];                                /*!< Device index data array, index is card slot*/
    char        DBDF[MAX_NUM_OF_FPGA_PER_HOST][DBDF_LEN+1];                         /*!< The PCIE DBDF of the device, index is card device index*/
    char        Driver_identifer[MAX_NUM_OF_FPGA_PER_HOST][DRIVER_ID_LEN+1];        /*!< The driver handle leading string, index is card device index*/
} MmSession;


/**
  * @brief Enumerate of API error code :fmerrno 
  *
*/
typedef enum
{
    MM_API_SUCCESS = 0,                         /*!< \n  Invoking API successfully */
    MM_API_ERR_LIB_NOT_INIT,                    /*!< \n  General API error: API was not initiated */
    MM_API_ERR_DRV_INIT_ERR,                    /*!< \n  General API error: driver was not initiated */
    MM_API_ERR_LOG_INIT_ERR,                    /*!< \n  General API error: MmSDK log was not initiated */
    MM_API_ERR_VER_MISMATCH,                    /*!< \n  General API error: API header version mismatched library version */
    MM_API_ERR_NULL_INPUT,                      /*!< \n  General API error: input parameter is null*/
    MM_API_ERR_DUP_SESSION_OPEN,                /*!< \n  General API error: open a dumplicated session */
    MM_API_ERR_ILLEGAL_INPUT,                   /*!< \n  General API error: illegal API parameter */
    MM_API_ERR_FILE_NOT_EXISTS,                 /*!< \n  General API error: file does not exist */
    MM_API_ERR_PROGRAM_FAILED,                  /*!< \n  General API error£ºprogram fpga failed */
    MM_API_ERR_CARD_NUM_OVERFLOW,               /*!< \n  General API error: fpga card number exceed the maximum number */
    MM_API_ERR_CARD_NUM_EQ0,                    /*!< \n  General API error: fpga number equal 0 */
    MM_API_ERR_ACQUIRE_DEVICE_MUTEX_ERR,        /*!< \n  General API error: acquring multithreads mutex failed*/
    MM_API_ERR_RELEASE_DEVICE_MUTEX_ERR,        /*!< \n  General API error: releasing multithreads mutex failed */
    MM_API_ERR_GET_IDENTIFIERS_ERR,             /*!< \n  Identifiers API error: failed getting FPGA identifiers */
    MM_API_ERR_GET_FIRMWARE_ERR,                /*!< \n  Identifiers API error: failed getting firmware information  */
    MM_API_ERR_MALLOC_ERR,                      /*!< \n  General API error: memory malloc failed */
    MM_API_ERR_MEMSET_ERR,                      /*!< \n  General API error: memory memset failed */
    MM_API_ERR_NOT_SUPPORT,                     /*!< \n  General API error: parameters were not supported */
    MM_API_ERR_GENERAL_ERR,                     /*!< \n  General API error: general error */
    MM_API_ERR_RESET_FPGA_ERR,                  /*!< \n  Reset API error: failed resetting fpga error */
    MM_API_ERR_EEPROM_ERR,                      /*!< \n  EEPROM API error: eeprom hardware error */
    MM_API_INDEX_ERR,                           /*!< \n  General API error: index was out of range */
    MM_API_BDF_NULL_ERR,                        /*!< \n  General API error: bdf string parameter was null */
    MM_API_FPGA_STATUS_ERR,                     /*!< \n  General API error: FPGA device was disabled */
    MM_API_ERR_UPGRADE_FIRMWARE_ERR,            /*!< \n  General API error: failed upgrading firmware */
    MM_API_ERR_GET_OVERHEAT_STATE_ERR,          /*!< \n  Config API error:  failed getting overheat state */
    MM_API_ERR_SET_OVERHEAT_STATE_ERR,          /*!< \n  Config API error:  failed setting overheat state */
    MM_API_ERR_READ_MAC_ADDR_ERR,               /*!< \n  EEPROM API error:  failed reading mac address */
    MM_API_ERR_WRITE_MAC_ADDR_ERR,              /*!< \n  EEPROM API error:  failed writing mac address */
    MM_API_ERR_READ_EEPROM_DATA_ERR,            /*!< \n  EEPROM API error:  failed reading eeprom data */
    MM_API_ERR_WRITE_EEPROM_DATA_ERR,           /*!< \n  EEPROM API error:  failed writing eepromg data */
    MM_API_ERR_WRITE_DMA_FAILED_ERR,            /*!< \n  PCIE IO error: failed writing data to dst address */
    MM_API_ERR_READ_DMA_FAILED_ERR,             /*!< \n  PCIE IO error: failed reading data from src address */
    MM_API_ERR_CHIP2CHIP_STATUS_ERR,             /*!< \n  PCIE IO error: failed reading data from src address */
    MM_API_ERR_CODE_EINVAL = 127                /*!< \n  General API error: default API error for error initiation */
} mmerrno;


/**
 * \brief initial every things about card information.
 * \param[in, out] MmSession_handle A pointer to a Mimic session handle (data pointer).
 * \return \ref MM_INTERFACE
 * \retval \ref MM_API_SUCCESS The Mimic session API started successfully.
 * \retval \ref MM_API_ERR_LOG_INIT_ERR The function failed because log initiation failed.
*/
MM_INTERFACE MmSession_Open(MmSession* MmSession_handle);

/**
 * \brief Scan Mimic Devices number 
 * \remarks This must be called after \ref MmSession_initial, or MM_API_ERR_LIB_NOT_INIT will be returned.
 * \return \ref MM_INTERFACE
 * \retval \ref MM_API_SUCCESS
 * \retval \ref MM_API_ERR_LIB_NOT_INIT The function failed because API system had not been initialized.
 * \retval \ref MM_API_NULL_INPUT The input MmSession_handle or Count is NULL.
 * \retval \ref MM_API_ERR_DRV_INIT_ERR The function detect Mimic device card, but driver is not loaded.
 * \retval \ref MM_API_ERR_GET_IDENTIFIERS_ERR The function failed to detect Mimic device card.
*/

MM_INTERFACE MmSession_ScanFpgaDeviceNum(MmSession *MmSession_handle, unsigned *Count);

MM_INTERFACE MmSession_Close(MmSession* MmSession_handle);

#ifdef	__cplusplus
}
#endif

#endif
