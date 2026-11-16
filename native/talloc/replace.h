/* Minimal stand-in for Samba's lib/replace, enough to build talloc.c
 * statically against bionic (Android API >= 29). */
#ifndef LINUXXR_TALLOC_REPLACE_H
#define LINUXXR_TALLOC_REPLACE_H

#include <errno.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <unistd.h>

#define HAVE_VA_COPY 1
#define HAVE_INTPTR_T 1
#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#define HAVE_SYS_AUXV_H 1
#define HAVE_GETAUXVAL 1

#ifndef likely
#define likely(x) __builtin_expect(!!(x), 1)
#endif
#ifndef unlikely
#define unlikely(x) __builtin_expect(!!(x), 0)
#endif
#ifndef MIN
#define MIN(a, b) ((a) < (b) ? (a) : (b))
#endif

#define discard_const(ptr) ((void *)((uintptr_t)(ptr)))
#define discard_const_p(type, ptr) ((type *)discard_const(ptr))

/* bionic lacks C11 Annex K; talloc only uses it to zero grown memory. */
static inline int memset_s(void *dest, size_t destsz, int ch, size_t count)
{
	if (count > destsz)
		count = destsz;
	memset(dest, ch, count);
	__asm__ __volatile__("" : : "r"(dest) : "memory");
	return 0;
}

#define LXR_STR(x) #x
#define LXR_XSTR(x) LXR_STR(x)
#define __location__ __FILE__ ":" LXR_XSTR(__LINE__)

#endif
