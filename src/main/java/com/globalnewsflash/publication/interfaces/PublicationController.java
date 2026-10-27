package com.globalnewsflash.publication.interfaces;

import com.globalnewsflash.publication.port.in.PublicationUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ops")
public class PublicationController {
    private final PublicationUseCase publication;

    public PublicationController(PublicationUseCase publication) {
        this.publication = publication;
    }

    public record ResolutionRequest(@NotBlank String resolution, @Size(max = 128) String messageId,
                                    @NotBlank @Size(max = 1000) String note) {}

    @GetMapping("/publications") public Object publications() { return publication.list(); }
    @GetMapping("/publications/{id}/preview")
    public Object preview(@PathVariable String id) {
        var operation = publication.get(id);
        return Map.of("operation", operation, "telegramText", publication.previewText(id));
    }
    @GetMapping("/publications/{id}/attempts") public Object attempts(@PathVariable String id) { return publication.attempts(id); }
    @GetMapping("/alerts") public Object alerts() { return publication.alerts(); }
    @GetMapping("/publications/{id}/resolutions") public Object resolutions(@PathVariable String id) { return publication.resolutions(id); }
    @PostMapping("/publications/{id}/dispatch") public Object dispatch(@PathVariable String id) { return publication.dispatch(id); }
    @PostMapping("/publications/{id}/resolve")
    public Object resolve(@PathVariable String id, @Valid @RequestBody ResolutionRequest request) {
        return publication.resolveUnknown(id, request.resolution(), request.messageId(), request.note());
    }
}
