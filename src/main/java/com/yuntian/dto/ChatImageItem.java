package com.yuntian.dto;

/**
 * 图片项
 * @param file image文件名
 * @param summary image摘要
 * @param url imageURL
 */
public record ChatImageItem(
        String file,
        String summary,
        String url
) {
}
