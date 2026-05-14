#ifndef MMSESSION_DMA_H_
#define MMSESSION_DMA_H_

#include <stdio.h>
#include <fcntl.h>
#include <string.h>
//#include <string>
#include <unistd.h>
#include <assert.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/mman.h>
#include <stdint.h>
#include <errno.h>
#include <stdbool.h>

#include "mimic_sdk.h"
//using namespace std;


#ifdef	__cplusplus
extern "C" {
#endif

#define RW_MAX_SIZE	0x7ffff000

#define FATAL do {                                                  \
    fprintf(stderr, "Error at line %d, file %s (%d) [%s]\n",        \
            __LINE__, __FILE__, errno, strerror(errno));            \
    exit(1);                                                        \
} while(0)                                                          

#define MAP_SIZE 4096UL
#define MAP_MASK (MAP_SIZE - 1)

/*
 * read data from file to buffer
*/
ssize_t read_to_buffer(int fd, char *buffer, uint64_t size, uint64_t base);

/*
 * write data from buffer to file
*/

ssize_t write_from_buffer(int fd, char *buffer, uint64_t size, uint64_t base);

mmerrno dma_from_device(MmSession* Mmsession_handle, int card_id, int channel_id, char* buf, uint64_t size, uint64_t base);
mmerrno dma_to_device(MmSession* Mmsession_handle, int card_id, int channel_id, char* buf, uint64_t size, uint64_t base);
mmerrno reg_readwrite(MmSession* Mmsession_handle, int card_id, uint64_t addr, uint32_t wr_data, uint32_t *rd_data, bool rd_or_wr);

#define MM_PAGE_SIZE 4096
/*
class MmSession_dma {
public:
	MmSession_dma(MmSession* Mmsession_handle, uint8_t card_id, uint8_t channel_id); 
	~MmSession_dma();
	mmerrno dma_from_device(char* buf, uint64_t size, uint64_t base);
	mmerrno dma_to_device(char* buf, uint64_t size, uint64_t base);
	mmerrno reg_readwrite(uint64_t addr, uint32_t wr_data, uint32_t *rd_data, bool rd_or_wr);

private:

	const int mm_page_size = 4096;
	//int fd_reg_;
	char *mm_file_send, *mm_file_recv, *mm_file_reg;
	uint8_t mm_card_id, mm_channel_id;
};
*/

#ifdef	__cplusplus
}
#endif

#endif // MMSESSION_DMA_H_
