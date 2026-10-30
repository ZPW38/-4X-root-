#pragma once

/* 华为 4.14.116 (NIK-AL00 / Kirin 990) 厂商定制 futex hash —— 精确还原版
 *
 * 背景：上游 Linux 的 hash_futex() 用 jhash2()，本机内核（华为深度定制，
 * 4.14.116）改写成了另一套算法。仓库 kernelsnitch/futex_hash.h 里仍是上游
 * jhash2 版本，因此在 4.14.116 上算出来的桶号与内核不符 —— 这是
 * kernelsnitch 找不到 mm_struct 的根本原因。
 *
 * 本文件内容逐条对照本机 vmlinux.elf 中 hash_futex 的机器码还原：
 *   hash_futex   @ 0xffffff8008153d28 .. 0xffffff8008153e08
 *   get_futex_key @ 0xffffff8008157d70
 *
 * 汇编要点（vmlinux.elf，无 DWARF，靠反汇编推导）：
 *
 * 1) key 布局（私有 futex，get_futex_key）
 *    [0x00] uaddr & ~0xfff   → u32[0] = lo, u32[1] = hi
 *    [0x08] current->mm      → u32[2] = lo, u32[3] = hi
 *    [0x10] uaddr & 0xfff    → u32[4]
 *    注意：仓库 futex_hash.h 的 private_key 把 mm / address 的先后顺序弄反了。
 *    证据：get_futex_key 私有分支 0xffffff80081580a8 处
 *          `stp x22, x19, [x20]` —— x22(=uaddr&~0xfff) 落在 key+0x00，
 *          x19(=current->mm) 落在 key+0x08。
 *
 * 2) hash 主流程（0xffffff8008153d28 起）
 *    ldp  w12, w8, [x0, #0xc]   → w12 = key[3], w8 = key[4]
 *    ldp  w10, w11, [x0]         → w10 = key[0], w11 = key[1]
 *    ldr  w9,  [x0, #8]          → w9  = key[2]
 *    init = key[4] + 0xdeadbeff
 *    a = key[0] + init;  b = key[1] + init;  c = key[2] + init
 *
 * 3) 5 轮 mix（上游 jhash2 是 6 轮，厂商砍到 5 轮）：
 *      a-=c; a^=rol(c,4);  c+=b;
 *      b-=a; b^=rol(a,6);  a+=c;
 *      c-=b; c^=rol(b,8);  b+=a;
 *      a-=c; a^=rol(c,16); c+=b;
 *      b-=a; b^=rol(a,19); a+=c;
 *    注意机器码写的是 `eor wX, wY, wZ, ror #N`：
 *      ror #28 == rol #4, ror #26 == rol #6, ror #24 == rol #8,
 *      ror #16 == rol #16, ror #13 == rol #19
 *    与上游 __jhash_mix 完全一致。
 *
 * 4) 厂商重写的 24 步 finalizer（与上游 __jhash_final 完全不同）。
 *    逐条对照机器码（w10=a, w11=b, w8=c, w12=tail）：
 *      0x3d88  add    w12, w10, w12      → tail = a + key[3]
 *      0x3d8c  sub    w8,  w8,  w11       → c -= b
 *      0x3d90  add    w10, w11, w10      → a += b
 *      0x3d94  eor    w8,  w10, w8        → c = a ^ c
 *      0x3d98  eor    w8,  w8,  w11, ror #28 → c ^= ror(b, 28)
 *      0x3d9c  ror    w11, w10, #0x12     → b = ror(a, 18)
 *      0x3da0  sub    w8,  w8,  w11       → c -= b
 *      0x3da4  eor    w11, w8,  w12       → b = c ^ tail
 *      0x3da8  ror    w12, w8,  #0x15     → tail = ror(c, 21)   ★★★
 *      0x3dac  sub    w11, w11, w12      → b -= tail
 *      0x3db0  eor    w10, w11, w10      → a = b ^ a
 *      0x3db4  ror    w12, w11, #7       → tail = ror(b, 7)    ★★★
 *      0x3db8  sub    w10, w10, w12      → a -= tail
 *      0x3dbc  eor    w8,  w10, w8        → c = a ^ c
 *      0x3dc0  ror    w12, w10, #0x10     → tail = ror(a, 16)
 *      0x3dc4  sub    w8,  w8,  w12       → c -= tail
 *      0x3dc8  eor    w11, w8,  w11       → b = c ^ b
 *      0x3dcc  ror    w12, w8,  #0x1c     → tail = ror(c, 28)
 *      0x3dd0  sub    w11, w11, w12      → b -= tail
 *      0x3de0  eor    w10, w11, w10      → a = b ^ a
 *      0x3de4  ror    w11, w11, #0x12     → b = ror(b, 18)
 *      0x3dec  sub    w10, w10, w11      → a -= b
 *      0x3df0  eor    w8,  w10, w8        → c = a ^ c
 *      0x3df4  ror    w10, w10, #8       → a = ror(a, 8)      ★★★
 *      0x3df8  sub    w8,  w8,  w10       → c -= a
 *    返回 c；桶号 = c & (futex_hashsize - 1)
 *
 *    ★★★ 三处曾长期写错的地方（都直接导致 8 个碰撞地址低位永远对不上、
 *        mm 反查 0 候选）：
 *      (a) 0x3da8 的源操作数是 w8(c)，不是 w11(b) → tail = ror(c, 21)
 *      (b) 0x3db4 的源操作数是 w11(b)，不是 w10(a) → tail = ror(b, 7)
 *      (c) 0x3df4 是 ror(a, 8)，不是 rol(a, 8)
 *      上一版注释里写的是「ror w12, w11, #0x15」「ror w12, w10, #7」，
 *      与真实机器码不符，属于"照着错注释修"，越修越错。
 *
 * 5) 桶大小：roundup_pow_of_two(256 * possible_cpus)。本机 possible = 0-3（4 核）
 *    → 表大小 1024。注意：设备上 sysconf(_SC_NPROCESSORS_CONF) 在受限环境下
 *    会误报为 1，导致仓库算出 256 —— 实际必须是 1024（低 10 位才对应桶号）。
 */

namespace ghostlock::kernelsnitch {

    static inline uint32_t vh_ror32(uint32_t w, uint32_t s) {
        return (w >> s) | (w << (32 - s));
    }

    static inline uint32_t vh_rol32(uint32_t w, uint32_t s) {
        return (w << s) | (w >> (32 - s));
    }

    /* 输入为 key 的 5 个 LE u32：[0]=addr_lo [1]=addr_hi [2]=mm_lo [3]=mm_hi [4]=offset */
    static inline uint32_t vendor_hash_futex_u32(const uint32_t *key) {
        uint32_t tail = key[3];
        const uint32_t init = key[4] + 0xdeadbeffu;
        uint32_t a = key[0] + init;
        uint32_t b = key[1] + init;
        uint32_t c = key[2] + init;

        /* 5 轮 mix */
        a -= c; a ^= vh_rol32(c, 4);  c += b;
        b -= a; b ^= vh_rol32(a, 6);  a += c;
        c -= b; c ^= vh_rol32(b, 8);  b += a;
        a -= c; a ^= vh_rol32(c, 16); c += b;
        b -= a; b ^= vh_rol32(a, 19); a += c;

        /* 厂商 finalizer —— 严格按 0xffffff8008153d88..0x3df8 的机器码 */
        tail = a + tail;
        c = c - b;  a = a + b;  c = a ^ c;  c = c ^ vh_ror32(b, 28);
        b = vh_ror32(a, 18);  c = c - b;  b = c ^ tail;  tail = vh_ror32(c, 21);
        b = b - tail;  a = b ^ a;  tail = vh_ror32(b, 7);  a = a - tail;
        c = a ^ c;  tail = vh_ror32(a, 16);  c = c - tail;  b = c ^ b;
        tail = vh_ror32(c, 28);  b = b - tail;  a = b ^ a;  b = vh_ror32(b, 18);
        a = a - b;  c = a ^ c;  a = vh_ror32(a, 8);  c = c - a;
        return c;
    }

    /* 直接由 (用户地址, mm 指针) 算桶号。
     * mm_ptr 只需低 32 位参与哈希（见上方 mix：c 的初值来自 mm_lo，
     * 而 mm_hi 只在 tail 中出现），但仍传入完整 64 位指针以保证一致性。 */
    static inline uint32_t vendor_hash_bucket(size_t addr, size_t mm_ptr, uint32_t table_size) {
        const uint64_t aligned = (uint64_t)addr & ~0xfffULL;
        uint32_t key[5];
        key[0] = (uint32_t)(aligned & 0xffffffffULL);
        key[1] = (uint32_t)(aligned >> 32);
        key[2] = (uint32_t)((uint64_t)mm_ptr & 0xffffffffULL);
        key[3] = (uint32_t)((uint64_t)mm_ptr >> 32);
        key[4] = (uint32_t)((uint64_t)addr & 0xfffULL);
        return vendor_hash_futex_u32(key) & (table_size - 1);
    }

} // namespace ghostlock::kernelsnitch
