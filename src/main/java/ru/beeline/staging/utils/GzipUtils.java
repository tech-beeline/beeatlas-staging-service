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
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(gzipBytes));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            gis.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
