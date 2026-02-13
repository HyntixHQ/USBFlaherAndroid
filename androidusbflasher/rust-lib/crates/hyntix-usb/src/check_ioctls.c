#include <stdio.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>

int main() {
    printf("SUBMITURB: 0x%08lX\n", (unsigned long)USBDEVFS_SUBMITURB);
    printf("REAPURB:   0x%08lX\n", (unsigned long)USBDEVFS_REAPURB);
    return 0;
}
