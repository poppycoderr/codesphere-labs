#include <stdio.h>
#include "config.h"

int main(void) {
#ifdef USE_V2
    int n = v2_total(MAX_ITEMS);
#else
    int n = MAX_ITEMS;
#endif
    printf("%d\n", n);
    return 0;
}
