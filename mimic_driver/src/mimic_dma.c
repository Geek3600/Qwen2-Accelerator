
#include "mimic_dma.h"
#include <stdlib.h>

//using std::string;


ssize_t read_to_buffer(int fd, char *buffer, uint64_t size, uint64_t base)
{
	ssize_t rc;
	uint64_t count = 0;
	char *buf = buffer;
	off_t offset = base;

	while (count < size) {
		uint64_t bytes = size - count;

		if (bytes > RW_MAX_SIZE)
			bytes = RW_MAX_SIZE;

		if (offset) {
			rc = lseek(fd, offset, SEEK_SET);
			if (rc != offset) {
            #ifndef DEBUG
				fprintf(stderr, "seek off 0x%lx != 0x%lx.\n", rc, offset);
				perror("seek file");
            #endif
				return -EIO;
			}
		}

		/* read data from file into memory buffer */
		rc = read(fd, buf, bytes);
		if (rc != bytes) {
            #ifndef DEBUG
			fprintf(stderr, "R off 0x%lx, 0x%lx != 0x%lx.\n",
				count, rc, bytes);
				perror("read file");
            #endif
			return -EIO;
		}

		count += bytes;
		buf += bytes;
		offset += bytes;
	}	 

	if (count != size) {
        #ifndef DEBUG
		fprintf(stderr, "R failed 0x%lx != 0x%lx.\n",
				count, size);
        #endif
		return -EIO;
	}
	return count;
}

ssize_t write_from_buffer(int fd, char *buffer, uint64_t size, uint64_t base)
{
	ssize_t rc;
	uint64_t count = 0;
	char *buf = buffer;
	off_t offset = base;

	while (count < size) {
		uint64_t bytes = size - count;

		if (bytes > RW_MAX_SIZE)
			bytes = RW_MAX_SIZE;

		if (offset) {
			rc = lseek(fd, offset, SEEK_SET);
			if (rc != offset) {
            #ifndef DEBUG
				fprintf(stderr, "seek off 0x%lx != 0x%lx.\n",
					rc, offset);
				perror("seek file");
            #endif
				return -EIO;
			}
		}

		/* write data to file from memory buffer */
		rc = write(fd, buf, bytes);
		if (rc != bytes) {
            #ifndef DEBUG
			fprintf(stderr, "W off 0x%lx, 0x%lx != 0x%lx.\n",
				offset, rc, bytes);
				perror("write file");
            #endif
			return -EIO;
		}

		count += bytes;
		buf += bytes;
		offset += bytes;
	}	 

	if (count != size) {
        #ifndef DEBUG
		fprintf(stderr, "R failed 0x%lx != 0x%lx.\n",
				count, size);
        #endif
		return -EIO;
	}
	return count;
}

mmerrno dma_from_device(MmSession* Mmsession_handle, int card_id, int channel_id, char* buf, uint64_t size, uint64_t base)
{
	mmerrno result = MM_API_ERR_CODE_EINVAL;
	char *mm_file_recv;

	mm_file_recv = (char *) malloc(sizeof(char) * 17);
	sprintf(mm_file_recv, "/dev/%s_c2h_%d", Mmsession_handle->Driver_identifer[card_id], channel_id);
    ssize_t rc;
    //uint64_t count = 0;
    //char *buf = a.get_data();
    char *allocated = NULL;
    char *buffer = NULL;
    int fpga_fd = open(mm_file_recv, O_RDWR | O_NONBLOCK);
    //uint64_t size = arr_size(a);
    uint64_t offset = 0; 
    

    if(fpga_fd < 0) FATAL;

    posix_memalign((void **)&allocated, MM_PAGE_SIZE, size + MM_PAGE_SIZE);
    if(!allocated) {
        fprintf(stderr, "OOM %lu.\n", size + MM_PAGE_SIZE);
        result = MM_API_ERR_MALLOC_ERR;
        goto out;
    }
    
    buffer = allocated + offset;

    rc = read_to_buffer(fpga_fd, buffer, size, base);
    
    if(rc < 0)
	{
		result = MM_API_ERR_READ_DMA_FAILED_ERR;
        goto out;
	}
    /* copy data from allocated to output buffer */
    memcpy(buf, buffer, size);
	result = MM_API_SUCCESS;
out:
    close(fpga_fd);
    free(allocated);
	free(mm_file_recv);
    return result;
}

mmerrno dma_to_device(MmSession* Mmsession_handle, int card_id, int channel_id, char* buf, uint64_t size, uint64_t base)
{
	mmerrno result = MM_API_ERR_CODE_EINVAL;
	char *mm_file_send;
    ssize_t rc;
    //uint64_t count = 0;
    //char *buf = a.get_data();
    char *allocated = NULL;
    char *buffer = NULL;
    //uint64_t size = arr_size(a);
    uint64_t offset = 0; 
    
	mm_file_send = (char *) malloc(sizeof(char) * 17);
	sprintf(mm_file_send, "/dev/%s_h2c_%d", Mmsession_handle->Driver_identifer[card_id], channel_id);

    int fpga_fd = open(mm_file_send, O_RDWR);

    if(fpga_fd < 0) FATAL;

    posix_memalign((void **)&allocated, MM_PAGE_SIZE, size + MM_PAGE_SIZE);
    if(!allocated) {
        fprintf(stdout, "OOM %lu.\n", size + MM_PAGE_SIZE);
        result = MM_API_ERR_MALLOC_ERR;
        goto out;
    }
    buffer = allocated + offset;
    /* copy data from input buffer to allocated */
    memcpy(buffer, buf, size);

    rc = write_from_buffer(fpga_fd, buffer, size, base);
    
	if(rc < 0)
	{
		result = MM_API_ERR_READ_DMA_FAILED_ERR;
        goto out;
	}
	result = MM_API_SUCCESS;
out:
    close(fpga_fd);
    free(allocated);
	free(mm_file_send);
    return result;
}



mmerrno reg_readwrite(MmSession* Mmsession_handle, int card_id, uint64_t addr, uint32_t wr_data,  uint32_t *rd_data, bool rd_or_wr)
{
	mmerrno result = MM_API_ERR_CODE_EINVAL;
	off_t	target;
	void	*virt_addr;
	void	*map_base;
	uint32_t	read_result, writeval;
	char *mm_file_reg;
    int fd_reg_;

	mm_file_reg = (char *) malloc(sizeof(char) * 16);
	sprintf(mm_file_reg, "/dev/%s_user", Mmsession_handle->Driver_identifer[card_id]);

	if((fd_reg_ = open(mm_file_reg, O_RDWR | O_SYNC)) < 0) 
        FATAL;

	target = addr;
	map_base = mmap(0, MAP_SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, fd_reg_, target & ~MAP_MASK);
	if(map_base == (void *) -1) FATAL;
	

	virt_addr = map_base + (target & MAP_MASK);
    if(rd_or_wr) {  // rd_or_wr == true -> read operation
        read_result = *((unsigned *) virt_addr);
		*rd_data = read_result;
    } else {		// rd_or_wr == false -> write operation
        writeval = wr_data;
        *((unsigned *) virt_addr) = writeval;
    }
    close(fd_reg_);
    munmap(map_base, MAP_SIZE);

	result = MM_API_SUCCESS;
	free(mm_file_reg);
	return result;
}

