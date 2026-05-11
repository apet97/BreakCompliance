package me.apet97.breakcompliance.addon.manifest;

import com.cake.clockify.addonsdk.clockify.model.ClockifyManifest;
import com.google.gson.Gson;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ManifestController {

    private final ClockifyManifest manifest;
    private final Gson gson = new Gson();

    public ManifestController(ClockifyManifest manifest) {
        this.manifest = manifest;
    }

    @GetMapping(value = "/manifest", produces = MediaType.APPLICATION_JSON_VALUE)
    public String manifest() {
        return gson.toJson(manifest);
    }
}
