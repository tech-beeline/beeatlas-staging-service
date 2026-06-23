package ru.beeline.staging.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.minidev.json.JSONObject;

import java.util.Base64;
import java.util.Map;

@Slf4j
public class JwtUtils {

    public static Map<String, Object> decodeJWT(String token) {
        try {
            String[] parts = token.split("\\.");
            Base64.Decoder decoder = Base64.getUrlDecoder();
            String payload = new String(decoder.decode(parts[1]));
            String jsonObject = JSONObject.escape(payload).replace("\\", "");
            return new ObjectMapper().readValue(jsonObject, Map.class);
        } catch (Exception e) {
            log.error("Failed to decode JWT: {}", e.getMessage());
        }
        return null;
    }
}
