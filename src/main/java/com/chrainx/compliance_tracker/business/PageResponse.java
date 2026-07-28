package com.chrainx.compliance_tracker.business;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;

// A deliberately explicit, custom pagination envelope (issue #49) rather than serializing Spring
// Data's own Page<T> directly - Page's default JSON shape leaks Spring-internal fields
// (pageable, sort, etc.) that aren't part of this API's actual contract, the same reasoning that
// led to BusinessResponse/WorkPassResponse existing at all (issue #46).
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    // Shared page/size clamping for every paginated endpoint - negative page numbers and
    // oversized/zero page sizes would otherwise either throw (PageRequest.of rejects negative
    // values outright, which would surface as an unhandled 500 with no validation in front of
    // it) or let a client request an unreasonably huge page in one call. Silently clamping
    // rather than rejecting with a 400 - the stakes here are just "how much data comes back,"
    // not a business-rule violation worth a client-visible error.
    private static final int MAX_PAGE_SIZE = 100;

    public static Pageable pageable(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
    }
}
