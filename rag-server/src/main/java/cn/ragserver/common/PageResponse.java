package cn.ragserver.common;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * 统一的分页返回体。
 *
 * 为什么不直接把 Spring Data 的 Page 对象返回给前端:
 * Page 的 JSON 结构由框架决定,里面还夹着 Pageable、Sort 等内部对象,
 * 字段名和层级都很别扭。**接口契约应该是自己定的、稳定的**,
 * 不能跟着框架版本走。
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext());
    }
}
