package com.bindass.backend.dto.response;

import lombok.*;
import java.util.List;

// Wraps paginated results with metadata
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PagedResponse<T> {
    private List<T> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private boolean last;
}