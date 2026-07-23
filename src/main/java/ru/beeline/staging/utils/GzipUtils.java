package ru.beeline.staging.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class GzipUtils {

    private GzipUtils() {}

    public static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(bos)) {
            gos.write(data);
        }
        return bos.toByteArray();
    }

    public static String gunzipToString(byte[] gzipBytes) throws IOException {
        return new String(gunzip(gzipBytes), StandardCharsets.UTF_8);
    }

    public static byte[] gunzip(byte[] gzipBytes) throws IOException {
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(gzipBytes));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            gis.transferTo(out);
            return out.toByteArray();
        }
    }

    public static boolean isGzip(byte[] data) {
        return data != null && data.length >= 2
                && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B;
    }
}
