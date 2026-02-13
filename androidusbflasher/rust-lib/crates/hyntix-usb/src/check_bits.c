#include <stdio.h>
#include <asm-generic/ioctl.h>
int main() {
    printf("READ:  0x%X\n", _IOC_READ);
    printf("WRITE: 0x%X\n", _IOC_WRITE);
    return 0;
}
