package com.praxedo.securefiles.infrastructure.web.file.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Where to go next — the contract's {@code FileLinks}.
 *
 * <p>{@code content} is <strong>absent</strong> unless the file is downloadable.
 * That is a deliberate second expression of the same server-side decision as
 * {@code downloadable}: a client that offered a download because a link existed
 * would still be right. The link is never a permission in itself — the download
 * path re-checks the state when it is called — but publishing one for a file
 * that cannot be served would be an invitation to a {@code 409}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FileLinksResponse(String self, String content) {
}
