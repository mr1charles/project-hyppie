/* Project Hyppie: libshim_idiv0 for the A13 fingerprint blobs (after LineageOS device-common/shims/idiv0_shim).
 * libsynaFpSensorTestNwd.so imports the ARM EABI division helpers and __srget, which current bionic
 * no longer exports. Built for armv7ve so the divisions use the hardware sdiv/udiv instructions. */
typedef unsigned long long uint64_t;  /* no host headers: this is an ARM/bionic library */
typedef struct __sFILE FILE;

int __aeabi_idiv(int n, int d) { return d == 0 ? 0 : n / d; }
unsigned __aeabi_uidiv(unsigned n, unsigned d) { return d == 0 ? 0 : n / d; }
/* EABI: quotient in r0, remainder in r1 -> returned as one 64-bit value (low = quotient). */
uint64_t __aeabi_uidivmod(unsigned n, unsigned d) {
    if (d == 0) return 0;
    return ((uint64_t)(n % d) << 32) | (n / d);
}
int __srget(FILE *stream) { (void)stream; return 0; }
