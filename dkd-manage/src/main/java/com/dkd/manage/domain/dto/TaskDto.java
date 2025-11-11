package com.dkd.manage.domain.dto;

import lombok.Data;

import java.util.List;

@Data
public class TaskDto {

    //创建类型
    private Long createType;

    //设备编号
    private String innerCode;

    //执行人id
    private Long userId;

    //指派人id
    private Long assignorId;

    //工单类型id，1：投放工单，2：补货工单，3：维修工单，4：撤机工单
    private Long productTypeId;

    //描述信息
    private String desc;

    //补货信息（补货工单才有）
    private List<TaskDetailsDto> details;
}
