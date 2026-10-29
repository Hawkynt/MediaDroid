// libplacebo's pl_filter_sample for ewa_lanczossharp, with glibc's j1: weights at x = 0, 0.05 .. 3.25.
#include <math.h>
#include <stdio.h>
#define JINC_ZERO3 3.2383154841662362076499
static double jinc(double x) { if (x < 1e-8) return 1.0; x *= M_PI; return 2.0 * j1(x) / x; }
int main(void) {
    const double blur = 0.98125058372237073562493, jr = 1.2196698912665045;
    const float radius = JINC_ZERO3 * blur;
    for (int i = 0; i <= 65; i++) {
        double x = i * 0.05, k;
        if (x > radius) k = 0.0;
        else k = jinc(x / blur) * jinc(x / radius * jr);
        printf("%.2f %.9f\n", x, k);
    }
}
