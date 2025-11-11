package com.dkd.manage.domain.dto;

import lombok.Data;

@Data
public class TaskDetailsDto {

    //货道编号
    private String channelCode;

    //补货数量
    private Long expectCapacity;

    //商品id
    private Long skuId;

    //商品名称
    private String skuName;

    //商品图片
    private String skuImage;
}
