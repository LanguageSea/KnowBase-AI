package com.knowbase.kb;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 知识库 DTO（docs/API_SPEC.md §2）。
 *
 * <p>对外 JSON 一律 camelCase；时间走 ISO-8601（由 Jackson 统一序列化）。
 * Stage 1 只返回 owner 本人的 ACTIVE 库（T2.5 再扩展成员授权）。
 */
public final class KbDtos {

    private KbDtos() {
    }

    /** POST /knowledge-bases 请求体。 */
    public record CreateRequest(
            @NotBlank(message = "知识库名称不能为空")
            @Size(max = 128, message = "知识库名称不能超过 128 个字符")
            String name,

            @Size(max = 1024, message = "描述不能超过 1024 个字符")
            String description) {
    }

    /** 单个知识库（含 docCount）。 */
    public record KbItem(long id, long ownerId, String name, String description, long docCount,
            boolean canWrite, boolean canManage, String createdAt) {
    }

    /** GET /knowledge-bases 响应 data。 */
    public record KbListResponse(List<KbItem> items, long total) {
    }
}
