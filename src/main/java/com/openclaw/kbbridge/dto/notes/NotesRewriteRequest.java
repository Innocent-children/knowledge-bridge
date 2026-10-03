package com.openclaw.kbbridge.dto.notes;

import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public record NotesRewriteRequest(@NotNull UUID userId,@NotNull UUID noteId,@Positive long sourceRevNo,
        @NotNull UUID rewriteJobId,@Pattern(regexp="[a-f0-9]{64}") @NotNull String sourceSha256,
        @NotBlank @Size(max=2097152) String sourceMarkdown,@NotNull @Size(max=50) List<UUID> allowedAttachmentIds) {}
