package com.yuntian.memory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * L0会话添加数据
 * @param acceptedIds 接受的ID列表
 * @param acceptedVersions 接受的版本列表
 * @param totalCount 总计数
 * */
public record ConversationAddData(
        @JsonProperty("accepted_ids")
        List<String> acceptedIds,

        @JsonProperty("accepted_versions")
        List<String> acceptedVersions,

        @JsonProperty("total_count")
        int totalCount
) {
}
