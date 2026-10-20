package io.github.xiaowuiot.protocol.jt808.body;

import io.github.xiaowuiot.protocol.jt808.Jt808Header;
import io.netty.buffer.ByteBuf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 数据压缩上报 0x0901：压缩消息长度 DWORD | 压缩消息体（GZIP）。
 * <p>
 * 终端把一大段数据（比如盲区补传的位置）用 GZIP 压缩后上传，省流量。解压后的内容是什么由双方约定，
 * 这里只负责解压，结果放在 {@link #getData()}。
 * <p>
 * 解压后的长度有上限，防止"压缩炸弹"：几 KB 的压缩数据解压出几个 G，把平台内存撑爆。
 */
public class CompressedData implements Jt808Body {

    /**
     * 解压后最多 10 MB
     */
    public static final int MAX_UNCOMPRESSED = 10 * 1024 * 1024;

    private final byte[] data;

    public CompressedData(byte[] data) {
        this.data = data;
    }

    public static CompressedData decode(ByteBuf b, Jt808Header h) {
        long len = b.readUnsignedInt();
        if (len > b.readableBytes()) {
            throw new IllegalArgumentException("压缩消息长度 " + len + " 超过消息体剩余长度 " + b.readableBytes());
        }
        byte[] compressed = new byte[(int) len];
        b.readBytes(compressed);
        return new CompressedData(gunzip(compressed));
    }

    @Override
    public void encode(ByteBuf out, Jt808Header h) {
        byte[] compressed = gzip(data);
        out.writeInt(compressed.length);
        out.writeBytes(compressed);
    }

    /**
     * 解压后的数据
     */
    public byte[] getData() {
        return data;
    }

    static byte[] gunzip(byte[] compressed) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (out.size() + n > MAX_UNCOMPRESSED) {
                    throw new IllegalArgumentException("解压后超过 " + MAX_UNCOMPRESSED + " 字节");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("GZIP 解压失败：" + e.getMessage(), e);
        }
    }

    static byte[] gzip(byte[] data) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    @Override
    public String toString() {
        return "CompressedData{" + data.length + " 字节}";
    }
}
