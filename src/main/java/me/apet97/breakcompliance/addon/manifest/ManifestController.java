package me.apet97.breakcompliance.addon.manifest;

import com.cake.clockify.addonsdk.clockify.model.ClockifyManifest;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ManifestController {

    private static final MediaType APPLICATION_JSON_UTF8 =
            new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);
    private static final String CLOCKIFY_SCHEMA_VERSION = "1.5";

    private final ClockifyManifest manifest;
    private final Gson gson = new Gson();

    public ManifestController(ClockifyManifest manifest) {
        this.manifest = manifest;
    }

    @GetMapping("/manifest")
    public ResponseEntity<byte[]> manifest() {
        // Force UTF-8 explicitly. Several setting descriptions and preset
        // labels embed non-ASCII glyphs (e.g. "§" in "Germany (ArbZG §3 +
        // §4)"); the default String → Latin-1 encoding path in Spring
        // mangles those into mojibake when the response Content-Type
        // doesn't declare a charset.
        JsonObject manifestJson = gson.toJsonTree(manifest).getAsJsonObject();
        manifestJson.addProperty("schemaVersion", CLOCKIFY_SCHEMA_VERSION);
        byte[] body = gson.toJson(manifestJson).getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(APPLICATION_JSON_UTF8);
        return new ResponseEntity<>(body, headers, 200);
    }
}
