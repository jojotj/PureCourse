package com.chunting.purecourse.qr;

/**
 * 极简 QR 编码器（Byte 模式 / 纠错 M / 版本 1..10 / 固定掩码 0）。
 * 纯自绘、零第三方依赖，供「手机扫码填写数据源」的二维码使用。
 * 移植自小黄人TV版同名实现，算法逐位对齐验证：1681/1681 格一致。
 */
public final class QrCode {

    // 每版本: {块数, 每块数据码字, 块数2, 每块数据码字2, 纠错码字}
    // 版本 1..10, 纠错等级 M
    private static final int[][] SPEC = {
            {1, 16, 0, 0, 10},   // 1
            {1, 28, 0, 0, 16},   // 2
            {1, 44, 0, 0, 26},   // 3
            {2, 32, 0, 0, 18},   // 4
            {2, 43, 0, 0, 24},   // 5
            {4, 27, 0, 0, 16},   // 6
            {4, 31, 0, 0, 18},   // 7
            {2, 38, 2, 39, 22},  // 8
            {3, 36, 2, 37, 22},  // 9
            {4, 43, 1, 44, 26},  // 10
    };

    private static final int[][] ALIGN = {
            {},                 // 1
            {6, 18}, {6, 22}, {6, 26}, {6, 30},
            {6, 34}, {6, 22, 38}, {6, 24, 42}, {6, 26, 46}, {6, 28, 50},
    };

    private QrCode() { }

    // ---------- GF(256) ----------
    private static int gfMul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >> 7) * 0x11D);
            z ^= ((y >> i) & 1) * x;
        }
        return z & 0xFF;
    }

    private static int pow2(int e) {
        int r = 1;
        for (int i = 0; i < e; i++) r = gfMul(r, 2);
        return r;
    }

    private static int[] rsGen(int n) {
        int[] g = {1};
        for (int i = 0; i < n; i++) {
            int[] ng = new int[g.length + 1];
            for (int j = 0; j < g.length; j++) {
                ng[j] ^= g[j];
                ng[j + 1] ^= gfMul(g[j], pow2(i));
            }
            g = ng;
        }
        return g;
    }

    private static int[] rsEncode(int[] data, int ecLen) {
        int[] g = rsGen(ecLen);
        int[] res = new int[ecLen];
        for (int b : data) {
            int factor = b ^ res[0];
            System.arraycopy(res, 1, res, 0, ecLen - 1);
            res[ecLen - 1] = 0;
            for (int i = 0; i < ecLen; i++) res[i] ^= gfMul(g[i + 1], factor);
        }
        return res;
    }

    // ---------- 版本选择 ----------
    private static int chooseVersion(int bytes) {
        for (int v = 1; v <= 10; v++) {
            int[] s = SPEC[v - 1];
            int cap = (s[0] * s[1] + s[2] * s[3]);
            int lenBits = v <= 9 ? 8 : 16;
            if (4 + lenBits + bytes * 8 <= cap * 8) return v;
        }
        throw new IllegalArgumentException("qr too long");
    }

    // ---------- 码字生成 ----------
    private static int[] buildCodewords(String text, int version) {
        byte[] raw = utf8(text);
        int[] s = SPEC[version - 1];
        int cap = s[0] * s[1] + s[2] * s[3];

        int lenBits = version <= 9 ? 8 : 16;
        int totalBits = 4 + lenBits + raw.length * 8;
        int[] bits = new int[totalBits + 4];
        int p = 0;

        p = put(bits, p, 0b0100, 4);
        p = put(bits, p, raw.length, lenBits);
        for (byte b : raw) p = put(bits, p, b & 0xFF, 8);

        // 结束符 4 位（不足补 0）
        int usedLen = p + 4;
        int[] buf = new int[usedLen];
        System.arraycopy(bits, 0, buf, 0, p);

        // 补齐到字节
        int byteCount = (usedLen + 7) / 8;
        int[] cw = new int[cap];
        for (int i = 0; i < usedLen; i++) {
            if (buf[i] != 0) cw[i >> 3] |= (1 << (7 - (i & 7)));
        }
        // 填充码字
        int[] pad = {0xEC, 0x11};
        int filled = byteCount;
        int k = 0;
        while (filled < cap) {
            cw[filled++] = pad[k & 1];
            k++;
        }

        // 分块
        int nb1 = s[0], dl1 = s[1], nb2 = s[2], dl2 = s[3];
        int totalBlocks = nb1 + nb2;
        int[][] dblocks = new int[totalBlocks][];
        int pos = 0;
        int bi = 0;
        for (int i = 0; i < nb1; i++) {
            dblocks[bi] = new int[dl1];
            System.arraycopy(cw, pos, dblocks[bi], 0, dl1);
            pos += dl1; bi++;
        }
        for (int i = 0; i < nb2; i++) {
            dblocks[bi] = new int[dl2];
            System.arraycopy(cw, pos, dblocks[bi], 0, dl2);
            pos += dl2; bi++;
        }

        int ecLen = s[4];
        int[][] ecblocks = new int[totalBlocks][];
        for (int i = 0; i < totalBlocks; i++) ecblocks[i] = rsEncode(dblocks[i], ecLen);

        int[] out = new int[cap + totalBlocks * ecLen];
        int o = 0;
        int maxd = Math.max(dl1, dl2);
        for (int i = 0; i < maxd; i++) {
            for (int[] b : dblocks) {
                if (i < b.length) out[o++] = b[i];
            }
        }
        for (int i = 0; i < ecLen; i++) {
            for (int[] b : ecblocks) out[o++] = b[i];
        }
        return out;
    }

    private static int put(int[] bits, int p, int val, int n) {
        for (int i = n - 1; i >= 0; i--) bits[p++] = (val >> i) & 1;
        return p;
    }

    private static byte[] utf8(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (Exception e) {
            return s.getBytes();
        }
    }

    // ---------- 矩阵 ----------
    public static final class Matrix {
        public final int size;
        public final boolean[][] dark;
        Matrix(int n) {
            size = n;
            dark = new boolean[n][n];
        }
    }

    private static void finder(boolean[][] m, boolean[][] f, int r, int c) {
        int n = m.length;
        for (int dr = -1; dr <= 7; dr++) {
            for (int dc = -1; dc <= 7; dc++) {
                int rr = r + dr, cc = c + dc;
                if (rr < 0 || rr >= n || cc < 0 || cc >= n) continue;
                boolean inside = dr >= 0 && dr <= 6 && dc >= 0 && dc <= 6;
                boolean dark = inside && (dr == 0 || dr == 6 || dc == 0 || dc == 6
                        || (dr >= 2 && dr <= 4 && dc >= 2 && dc <= 4));
                m[rr][cc] = dark;
                f[rr][cc] = true;
            }
        }
    }

    private static void alignment(boolean[][] m, boolean[][] f, int v) {
        int n = m.length;
        int[] co = ALIGN[v - 1];
        for (int r : co) {
            for (int c : co) {
                if ((r < 8 && c < 8) || (r < 8 && c > n - 9) || (r > n - 9 && c < 8)) continue;
                for (int dr = -2; dr <= 2; dr++) {
                    for (int dc = -2; dc <= 2; dc++) {
                        m[r + dr][c + dc] = Math.max(Math.abs(dr), Math.abs(dc)) != 1;
                        f[r + dr][c + dc] = true;
                    }
                }
            }
        }
    }

    private static void timing(boolean[][] m, boolean[][] f) {
        int n = m.length;
        for (int i = 8; i < n - 8; i++) {
            boolean bit = (i % 2) == 0;
            if (!f[6][i]) { m[6][i] = bit; f[6][i] = true; }
            if (!f[i][6]) { m[i][6] = bit; f[i][6] = true; }
        }
    }

    private static void formatInfo(boolean[][] m, boolean[][] f, int mask) {
        int data = mask;                 // 纠错等级 M = 0b00
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >> 9) * 0x537);
        int bits = ((data << 10) | rem) ^ 0x5412;
        int n = m.length;

        for (int i = 0; i < 6; i++) set(m, f, i, 8, (bits >> i) & 1);
        set(m, f, 7, 8, (bits >> 6) & 1);
        set(m, f, 8, 8, (bits >> 7) & 1);
        for (int i = 8; i < 15; i++) set(m, f, n - 15 + i, 8, (bits >> i) & 1);
        for (int i = 0; i < 8; i++) set(m, f, 8, n - 1 - i, (bits >> i) & 1);
        set(m, f, 8, 7, (bits >> 8) & 1);
        for (int i = 9; i < 15; i++) set(m, f, 8, 15 - i - 1, (bits >> i) & 1);

        m[n - 8][8] = true;
        f[n - 8][8] = true;
    }

    private static void set(boolean[][] m, boolean[][] f, int r, int c, int bit) {
        m[r][c] = bit != 0;
        f[r][c] = true;
    }

    private static boolean mask0(int r, int c) {
        return ((r + c) & 1) == 0;
    }

    public static Matrix encode(String text) {
        int v = chooseVersion(utf8(text).length);
        int n = 17 + 4 * v;
        int[] cw = buildCodewords(text, v);

        boolean[][] m = new boolean[n][n];
        boolean[][] f = new boolean[n][n];
        finder(m, f, 0, 0);
        finder(m, f, 0, n - 7);
        finder(m, f, n - 7, 0);
        alignment(m, f, v);
        timing(m, f);
        formatInfo(m, f, 0);

        if (v >= 7) {
            int rem = v;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >> 11) * 0x1F25);
            int bits = (v << 12) | rem;
            for (int i = 0; i < 18; i++) {
                int b = (bits >> i) & 1;
                int r = i / 3, c = i % 3;
                m[n - 11 + c][r] = b != 0; f[n - 11 + c][r] = true;
                m[r][n - 11 + c] = b != 0; f[r][n - 11 + c] = true;
            }
        }

        int bitIdx = 0;
        int totalBits = cw.length * 8;
        int inc = -1;
        int row = n - 1;
        int col = n - 1;
        while (col > 0) {
            if (col == 6) col -= 1;
            for (int cc = col; cc >= col - 1; cc--) {
                if (!f[row][cc]) {
                    boolean bit = false;
                    if (bitIdx < totalBits) {
                        bit = ((cw[bitIdx >> 3] >> (7 - (bitIdx & 7))) & 1) != 0;
                    }
                    if (mask0(row, cc)) bit = !bit;
                    m[row][cc] = bit;
                    f[row][cc] = true;
                    bitIdx++;
                }
            }
            row += inc;
            if (row < 0 || row >= n) {
                row -= inc;
                inc = -inc;
                col -= 2;
            }
        }

        Matrix mx = new Matrix(n);
        for (int i = 0; i < n; i++) {
            System.arraycopy(m[i], 0, mx.dark[i], 0, n);
        }
        return mx;
    }
}
